package dev.harness.core.linux

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What the Linux environment is doing right now. */
sealed interface LinuxState {
    data class Unsupported(val reason: String) : LinuxState
    data object NotInstalled : LinuxState
    data class Working(val stage: String, val progress: Float? = null, val log: List<String> = emptyList()) : LinuxState
    data class Ready(val release: String, val sizeBytes: Long) : LinuxState
    data class Failed(val message: String, val log: List<String> = emptyList()) : LinuxState
}

/** The Ubuntu LTS releases FARAI can install. 24.04 is smaller; 26.04 is the newest. */
enum class UbuntuRelease(val id: String, val label: String, val base: String, val note: String) {
    NOBLE("24.04", "24.04 LTS", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release", "Lighter · ≈100 MB unpacked · Python 3.12"),
    RESOLUTE("26.04", "26.04 LTS", "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release", "Newest · ≈110 MB unpacked, bigger packages · Python 3.14");

    companion object {
        val DEFAULT = NOBLE
        fun parse(s: String?): UbuntuRelease? = entries.firstOrNull { s?.trim()?.startsWith(it.id) == true }
        /** The release a marker like "Ubuntu 24.04.5" belongs to. */
        fun ofMarker(marker: String): UbuntuRelease? =
            Regex("""(\d+\.\d+)""").find(marker)?.groupValues?.get(1)?.let(::parse)
    }
}

/** The Linux distributions FARAI can run under PRoot. Several can be installed; one is active. */
enum class Distro(val id: String, val title: String, val packageManager: String) {
    UBUNTU("ubuntu", "Ubuntu", "apt"),
    ALPINE("alpine", "Alpine Linux", "apk");

    /** Packages a fresh install adds so the agent has python, pip, git and curl. */
    val defaultPackages: List<String> get() = when (this) {
        UBUNTU -> listOf("ca-certificates", "python3", "python3-pip", "python3-venv", "git", "curl", "nano", "less", "xz-utils", "unzip")
        ALPINE -> listOf("bash", "python3", "py3-pip", "git", "curl", "nano")
    }

    fun installCommand(pkgs: List<String>): String = when (this) {
        UBUNTU -> "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${pkgs.joinToString(" ")}"
        ALPINE -> "apk add --no-cache ${pkgs.joinToString(" ")}"
    }

    fun removeCommand(pkgs: List<String>): String = when (this) {
        UBUNTU -> "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${pkgs.joinToString(" ")}"
        ALPINE -> "apk del ${pkgs.joinToString(" ")}"
    }

    companion object {
        fun parse(s: String?): Distro? = when (s?.trim()?.lowercase()) {
            "ubuntu", "ub" -> UBUNTU
            "alpine", "alp" -> ALPINE
            else -> null
        }
    }
}

/**
 * Linux under PRoot (no root needed): Ubuntu (glibc, apt, most compatible) or Alpine (musl, apk,
 * tiny). Both can be installed side by side in filesDir/linux/<id>; the active one runs the bash
 * tool, /linux and local MCP servers.
 *
 * PRoot ships inside the APK as `libproot.so` (+ its loader) so Android extracts it to the
 * app's native library directory, the one place an app may still execute files from on
 * Android 10+. Root filesystems are downloaded on demand, checked against SHA-256 and unpacked
 * into private storage.
 *
 * Inside the guest: the workspace is `/workspace`, shared storage is `/sdcard` (when All files
 * access is granted), and the user is a fake root.
 */
class LinuxRuntime(
    private val context: Context,
    private val http: OkHttpClient,
    private val workspaceRoot: File,
    private val storageRoot: () -> File?,
) : dev.harness.tools.ShellBackend {
    override val label: String get() = (state.value as? LinuxState.Ready)?.release ?: active.value.title
    override val available: Boolean get() = isReady
    override fun processFor(command: String, hostCwd: File): ProcessBuilder = buildProcess(command, hostCwd)

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val prootBinary = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")
    private val loader32 = File(nativeDir, "libproot-loader32.so")
    private val base = File(context.filesDir, "linux")
    private val activeFile = File(base, "active")
    private val tmpDir = File(context.cacheDir, "proot-tmp")
    private val networkRefreshedAt = HashMap<Distro, Long>()
    private val shmDir = File(context.cacheDir, "proot-shm")
    private val installLock = Mutex()

    fun rootfsOf(d: Distro) = File(base, d.id)
    private fun markerOf(d: Distro) = File(rootfsOf(d), ".farai-installed")
    private val ubuntuReleaseFile = File(base, "ubuntu-release")
    private val _ubuntuRelease = MutableStateFlow(
        UbuntuRelease.parse(runCatching { ubuntuReleaseFile.readText() }.getOrNull())
            ?: runCatching { UbuntuRelease.ofMarker(markerOf(Distro.UBUNTU).readText()) }.getOrNull()
            ?: UbuntuRelease.DEFAULT,
    )
    /** The Ubuntu release Install fetches (and that counts as installed). */
    val ubuntuRelease: StateFlow<UbuntuRelease> = _ubuntuRelease.asStateFlow()

    /** Ubuntu release currently unpacked on disk, if any. */
    fun installedUbuntuRelease(): UbuntuRelease? =
        markerOf(Distro.UBUNTU).takeIf { it.isFile }?.let { UbuntuRelease.ofMarker(it.readText()) }

    /** Picks the Ubuntu release the next install uses. Does not touch an existing install. */
    fun setUbuntuRelease(r: UbuntuRelease) {
        base.mkdirs()
        ubuntuReleaseFile.writeText(r.id)
        _ubuntuRelease.value = r
        if (stateFlows.getValue(Distro.UBUNTU).value !is LinuxState.Working) set(Distro.UBUNTU, initialState(Distro.UBUNTU))
    }

    fun installed(d: Distro): Boolean = markerOf(d).isFile && isCurrentRelease(d, markerOf(d).readText(), _ubuntuRelease.value)

    /** The active distro's root filesystem. */
    val rootfs: File get() = rootfsOf(active.value)

    val arch: String? = when (Build.SUPPORTED_ABIS?.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        else -> null
    }

    private val _active = MutableStateFlow(initialActive())
    /** The distro the bash tool uses. Ubuntu unless only Alpine is installed (or chosen). */
    val active: StateFlow<Distro> = _active.asStateFlow()

    private val stateFlows = Distro.entries.associateWith { MutableStateFlow(initialState(it)) }
    private val _states = MutableStateFlow(stateFlows.mapValues { it.value.value })
    /** Every distro's own state, for the environment picker. */
    val states: StateFlow<Map<Distro, LinuxState>> = _states.asStateFlow()

    private val _state = MutableStateFlow(stateFlows.getValue(_active.value).value)
    /** The active distro's state. */
    val state: StateFlow<LinuxState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value is LinuxState.Ready

    private fun set(d: Distro, s: LinuxState) {
        stateFlows.getValue(d).value = s
        _states.value = stateFlows.mapValues { it.value.value }
        if (d == _active.value) _state.value = s
    }

    private fun initialActive(): Distro {
        val saved = Distro.parse(runCatching { activeFile.readText() }.getOrNull())
        if (saved != null) return saved
        return if (installed(Distro.ALPINE) && !installed(Distro.UBUNTU)) Distro.ALPINE else Distro.UBUNTU
    }

    private fun initialState(d: Distro): LinuxState = when {
        arch == null -> LinuxState.Unsupported("CPU ${Build.SUPPORTED_ABIS?.firstOrNull()} is not supported (arm64, armv7 and x86_64 are)")
        !prootBinary.isFile -> LinuxState.Unsupported("PRoot is missing from this build")
        installed(d) -> LinuxState.Ready(markerOf(d).readText().trim().ifEmpty { d.title }, 0L)
        else -> LinuxState.NotInstalled
    }

    /** Makes [d] the environment the agent uses. */
    fun setActive(d: Distro) {
        base.mkdirs()
        activeFile.writeText(d.id)
        _active.value = d
        _state.value = stateFlows.getValue(d).value
    }

    /** Recomputes the disk sizes shown in Settings. Cheap enough to call from a screen. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (d in Distro.entries) {
            if (stateFlows.getValue(d).value is LinuxState.Working) continue
            set(d, when (val init = initialState(d)) {
                is LinuxState.Ready -> init.copy(sizeBytes = dirSize(rootfsOf(d)))
                else -> init
            })
        }
    }

    /** Free space in private storage, for the "before you install" estimate. */
    fun freeBytes(): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(-1L)

    /** Size of the rootfs download, from the server (null when offline). */
    suspend fun downloadSize(d: Distro): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val a = arch ?: return@runCatching null
            val url = resolveRootfs(d, a).url
            http.newCall(Request.Builder().url(url).head().build()).execute().use { r -> r.header("Content-Length")?.toLongOrNull() }
        }.getOrNull()
    }

    // ---- Install ------------------------------------------------------------------------------

    /**
     * Downloads, verifies and unpacks [d], then installs a developer toolset ([packages], or the
     * distro's defaults when null). Safe to call again: an existing install keeps its files and
     * only the packages are added. A fresh install becomes the active environment.
     */
    suspend fun install(d: Distro = active.value, packages: List<String>? = null) = installLock.withLock {
        withContext(Dispatchers.IO) {
            val pkgs = packages ?: d.defaultPackages
            val log = ArrayList<String>()
            fun stage(name: String, p: Float? = null) { set(d, LinuxState.Working(name, p, log.takeLast(LOG_LINES))) }
            fun line(s: String) { log += s; val cur = stateFlows.getValue(d).value; if (cur is LinuxState.Working) set(d, cur.copy(log = log.takeLast(LOG_LINES))) }
            val root = rootfsOf(d)
            val marker = markerOf(d)
            try {
                val a = arch ?: throw IOException("unsupported CPU")
                if (marker.isFile && !isCurrentRelease(d, marker.readText(), _ubuntuRelease.value)) {
                    val to = if (d == Distro.ALPINE) "Alpine $PINNED_BRANCH" else "Ubuntu ${_ubuntuRelease.value.label}"
                    stage("Upgrading to $to…")
                    line("↺ ${marker.readText().trim()} → $to (fresh root filesystem)")
                    deleteTree(root)
                }
                if (!marker.isFile) {
                    stage("Finding the latest ${d.title} release…")
                    val src = resolveRootfs(d, a)
                    line("→ ${src.url}")
                    val need = (src.size ?: 0L) * 4 + 300L * 1024 * 1024
                    val free = freeBytes()
                    if (free in 0 until need) throw IOException("not enough free space: ${free / (1024 * 1024)} MB free, about ${need / (1024 * 1024)} MB needed")
                    val archive = File(context.cacheDir, "${d.id}-rootfs.tar.gz")
                    download(src.url, archive) { p -> stage("Downloading ${d.title} ${src.release}…", p) }
                    if (src.sha256 != null) {
                        stage("Verifying SHA-256…")
                        val actual = sha256(archive)
                        if (!actual.equals(src.sha256, ignoreCase = true)) { archive.delete(); throw IOException("checksum mismatch (expected ${src.sha256}, got $actual)") }
                        line("✓ sha256 $actual")
                    } else line("! no checksum published; skipped verification")
                    stage("Unpacking…", 0f)
                    val staging = File(base, "${d.id}.partial")
                    deleteTree(staging)
                    val total = archive.length().toFloat()
                    val counting = CountingInputStream(archive.inputStream().buffered(256 * 1024))
                    var n = 0
                    TarExtractor(AndroidPlatform).extractTarGz(counting, staging) {
                        if (++n % 200 == 0) stage("Unpacking…", (counting.count / total).coerceIn(0f, 1f))
                    }
                    line("✓ unpacked $n entries")
                    archive.delete()
                    if (!File(staging, "etc").isDirectory) throw IOException("the archive has no /etc; not a root filesystem")
                    deleteTree(root)
                    if (!staging.renameTo(root)) throw IOException("could not move rootfs into place")
                    if (d == Distro.ALPINE) installApkStatic(a, root, ::line)
                    if (d == Distro.UBUNTU) useGnuCoreutils(root, ::line)
                    marker.writeText("${d.title} ${src.release}")
                }
                stage("Configuring ${d.title}…")
                configureGuest(d)
                refreshNetwork(d, force = true)
                probeSeccomp(d, ::line)
                if (pkgs.isNotEmpty()) {
                    updateIndex(d, ::stage, ::line)
                    if (d == Distro.UBUNTU && File(root, "usr/lib/cargo/bin/coreutils").isDirectory) {
                        stage("Switching to GNU coreutils…")
                        exec("apt-get install -y -q --allow-remove-essential coreutils-from-gnu coreutils-from-uutils-", timeoutSeconds = 600, distro = d, onLine = ::line)
                    }
                    stage("Installing ${pkgs.joinToString(" ")}…")
                    val code = exec(d.installCommand(pkgs), timeoutSeconds = 1800, distro = d, onLine = ::line)
                    if (code != 0) throw IOException("${d.packageManager} install failed (exit $code)")
                    if (d == Distro.UBUNTU) exec("apt-get clean", 120, distro = d)
                }
                val release = marker.readText().trim()
                set(d, LinuxState.Ready(release, dirSize(root)))
                // The first environment installed, or one installed while the active one is not
                // ready, becomes the active one.
                if (_active.value != d && !installed(_active.value)) setActive(d)
                else if (_active.value == d) _state.value = stateFlows.getValue(d).value
            } catch (e: Exception) {
                set(d, LinuxState.Failed(e.message ?: e.toString(), log.takeLast(LOG_LINES)))
            }
        }
    }

    /** Deletes [d] entirely (packages, home folder). The workspace is not touched. */
    suspend fun uninstall(d: Distro = active.value) = installLock.withLock {
        withContext(Dispatchers.IO) {
            set(d, LinuxState.Working("Removing ${d.title}…"))
            deleteTree(rootfsOf(d))
            deleteTree(File(base, "${d.id}.partial"))
            if (Distro.entries.none { installed(it) }) { deleteTree(tmpDir); deleteTree(shmDir) }
            set(d, initialState(d))
            if (_active.value == d) Distro.entries.firstOrNull { it != d && installed(it) }?.let(::setActive)
        }
    }

    /**
     * Refreshes the package index. Alpine: the official CDN over HTTPS, then plain HTTP (indexes
     * are signed), then two mirrors. Ubuntu: apt over the default ports/archive mirror, twice.
     * Each failure line is kept so the user sees the real reason.
     */
    /**
     * Alpine 3.23+ ships apk-tools 3, which writes files through O_TMPFILE + linkat(/proc/self/fd)
     * — a path PRoot cannot follow, so every database write fails with EPERM. apk-tools 2.14
     * (static, from the 3.21 branch) still reads the same index and package format, so it is
     * installed as /usr/local/sbin/apk, ahead of /sbin/apk on PATH and safe from apk upgrades.
     */
    private fun installApkStatic(arch: String, root: File, line: (String) -> Unit) {
        val sum = APK_STATIC_SHA256[arch] ?: throw IOException("no apk-tools-static for $arch")
        val pkg = File(context.cacheDir, "apk-tools-static.apk")
        download("https://dl-cdn.alpinelinux.org/alpine/v3.21/main/$arch/$APK_STATIC_FILE", pkg) {}
        try {
            val actual = sha256(pkg)
            if (!actual.equals(sum, ignoreCase = true)) throw IOException("apk-tools-static checksum mismatch")
            val dir = File(root, "usr/local/lib/farai").apply { mkdirs() }
            val bin = File(dir, "apk.static")
            if (!extractFromApk(pkg, "sbin/apk.static", bin)) throw IOException("apk.static missing from package")
            bin.setExecutable(true, false)
            File(root, "usr/local/sbin").mkdirs()
            File(root, "usr/local/sbin/apk").apply {
                writeText("#!/bin/sh\n# FARAI: apk-tools 2 (PRoot-safe). The distro's apk 3 stays at /sbin/apk.\nexec /usr/local/lib/farai/apk.static \"$@\"\n")
                setExecutable(true, false)
            }
            line("✓ apk-tools 2.14 (PRoot-compatible) installed as apk")
        } finally { pkg.delete() }
    }

    /**
     * Ubuntu 26.04 makes uutils (Rust coreutils) the default; its multicall binary picks the tool
     * from the exec path, which PRoot rewrites, so `env`, `ls`… all fail. Point each command at
     * the GNU build the image also ships (gnuls, gnuenv…); apt makes it permanent later.
     */
    private fun useGnuCoreutils(root: File, line: (String) -> Unit) {
        val bin = File(root, "usr/bin")
        var n = 0
        bin.listFiles()?.forEach { f ->
            val path = f.toPath()
            if (!java.nio.file.Files.isSymbolicLink(path)) return@forEach
            val target = java.nio.file.Files.readSymbolicLink(path).toString()
            if (!target.contains("cargo/bin/coreutils/")) return@forEach
            val gnu = File(bin, "gnu" + target.substringAfterLast('/'))
            if (!gnu.exists()) return@forEach
            java.nio.file.Files.delete(path)
            java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(gnu.name))
            n++
        }
        if (n > 0) line("✓ $n coreutils commands switched to GNU")
    }

    /** Copies [entry] out of an Alpine .apk (several concatenated tar.gz segments) into [to]. */
    private fun extractFromApk(apk: File, entry: String, to: File): Boolean {
        java.util.zip.GZIPInputStream(apk.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) { val n = input.read(header, read, 512 - read); if (n < 0) return false; read += n }
                if (header.all { it.toInt() == 0 }) continue // segment ends; the next member follows
                val name = String(header, 0, 100).substringBefore('\u0000')
                val size = String(header, 124, 12).trim('\u0000', ' ').toLongOrNull(8) ?: 0L
                val padded = (size + 511) / 512 * 512
                if (name == entry) {
                    to.outputStream().use { out ->
                        var left = size; val buf = ByteArray(64 * 1024)
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) return false; out.write(buf, 0, n); left -= n }
                    }
                    return true
                }
                var skip = padded
                while (skip > 0) { val n = input.skip(skip); if (n <= 0) { if (input.read() < 0) return false; skip-- } else skip -= n }
            }
        }
    }

    private fun noSeccompMarker(d: Distro) = File(rootfsOf(d), ".farai-no-seccomp")

    /**
     * PRoot's seccomp acceleration crashes on some kernels (the guest dies on its first exec).
     * Run a trivial command; if it fails, retry without seccomp and remember that for [d].
     */
    private fun probeSeccomp(d: Distro, line: (String) -> Unit) {
        val marker = noSeccompMarker(d)
        marker.delete()
        val out = StringBuilder()
        val ok = runCatching { exec("/bin/echo farai-ok", timeoutSeconds = 60, distro = d) { out.append(it) } == 0 && "farai-ok" in out }.getOrDefault(false)
        if (ok) return
        marker.writeText("1")
        out.clear()
        val ok2 = runCatching { exec("/bin/echo farai-ok", timeoutSeconds = 60, distro = d) { out.append(it) } == 0 && "farai-ok" in out }.getOrDefault(false)
        if (ok2) line("✓ seccomp acceleration off (this device needs it)") else { marker.delete(); throw IOException("${d.title} could not start a program under PRoot") }
    }

    private fun updateIndex(d: Distro, stage: (String, Float?) -> Unit, line: (String) -> Unit) {
        if (d == Distro.UBUNTU) {
            repeat(2) { attempt ->
                stage(if (attempt == 0) "Updating package lists…" else "Retrying package lists…", null)
                val out = ArrayList<String>()
                val code = exec("apt-get update", timeoutSeconds = 300, distro = d) { out += it; line(it) }
                if (code == 0 && out.none { it.startsWith("E:") }) return
            }
            throw IOException("apt-get update failed; check the connection and try again")
        }
        val reasons = ArrayList<String>()
        for ((i, mirror) in MIRRORS.withIndex()) {
            stage(if (i == 0) "Updating package index…" else "Trying mirror ${i + 1} of ${MIRRORS.size}…", null)
            writeRepositories(mirror)
            val out = ArrayList<String>()
            val code = exec("apk update", timeoutSeconds = 180, distro = d) { out += it; line(it) }
            val bad = out.filter { it.startsWith("WARNING") || it.startsWith("ERROR") }
            val failed = bad.size
            if (code == 0 && failed == 0) return
            reasons += bad.map { it.substringAfterLast(": ").trim() }
        }
        val why = reasons.distinct().joinToString("; ").ifBlank { "no response" }
        throw IOException("could not reach any Alpine mirror ($why)")
    }

    private fun writeRepositories(mirror: String) {
        val f = File(rootfsOf(Distro.ALPINE), "etc/apk/repositories")
        val version = Regex("""/(v\d+\.\d+|edge)/""").find(f.takeIf { it.isFile }?.readText().orEmpty())?.groupValues?.get(1)
            ?: "v$PINNED_BRANCH"
        f.parentFile?.mkdirs()
        f.writeText("$mirror/$version/main\n$mirror/$version/community\n")
    }

    /**
     * Android has no /etc/resolv.conf, and the guest's resolver cannot ask Android's DNS daemon.
     * So: write the active network's own DNS servers (public ones as a fallback) into the guest,
     * and pre-resolve the hosts package managers need through Android's resolver into /etc/hosts,
     * so apt, apk, pip and git work even on networks that block public DNS.
     */
    fun refreshNetwork(d: Distro = active.value, force: Boolean = false) {
        val root = rootfsOf(d)
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        if (!force && now - (networkRefreshedAt[d] ?: 0L) < 10 * 60_000L) return
        networkRefreshedAt[d] = now
        runCatching {
            val servers = LinkedHashSet<String>()
            runCatching {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.forEach { a ->
                    a.hostAddress?.substringBefore('%')?.let(servers::add)
                }
            }
            servers += listOf("8.8.8.8", "1.1.1.1")
            File(root, "etc/resolv.conf").apply { delete(); writeText(servers.take(3).joinToString("") { "nameserver $it\n" }) }
            val hosts = StringBuilder("127.0.0.1 localhost.localdomain localhost\n::1 localhost ip6-localhost ip6-loopback\n")
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                PRERESOLVE.map { h ->
                    h to pool.submit<List<String>> {
                        runCatching { java.net.InetAddress.getAllByName(h).filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
                    }
                }.forEach { (h, fut) ->
                    runCatching { fut.get(5, TimeUnit.SECONDS) }.getOrNull()?.firstOrNull()?.let { hosts.append("$it $h\n") }
                }
            } finally { pool.shutdownNow() }
            File(root, "etc/hosts").writeText(hosts.toString())
        }
    }

    private fun configureGuest(d: Distro) {
        val root = rootfsOf(d)
        File(root, "workspace").mkdirs()
        File(root, "sdcard").mkdirs()
        File(root, "root").mkdirs()
        File(root, "tmp").apply { mkdirs(); runCatching { Os.chmod(path, 0b1_111_111_111) } }
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/farai.sh").writeText(
            "export PIP_BREAK_SYSTEM_PACKAGES=1\nexport PIP_ROOT_USER_ACTION=ignore\ncd /workspace 2>/dev/null\n",
        )
        // Fake /proc and /sys entries Android hides from apps (as proot-distro does).
        File(root, "sys/.empty").mkdirs()
        File(root, "proc").mkdirs()
        FAKE_PROC.forEach { (name, body) -> File(root, "proc/.$name").writeText(body) }
        if (d == Distro.UBUNTU) {
            // apt drops to the _apt user for downloads; that setgroups() call fails under PRoot.
            File(root, "etc/apt/apt.conf.d").mkdirs()
            File(root, "etc/apt/apt.conf.d/01-farai-proot").writeText(
                "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n",
            )
            // Android's own uid/gid, so tools that look up the file owner find a name.
            runCatching {
                val uid = Os.getuid(); val gid = Os.getgid()
                val passwd = File(root, "etc/passwd")
                if (passwd.isFile && !passwd.readText().contains("aid_app:")) {
                    passwd.appendText("aid_app:x:$uid:$gid:Android app:/:/usr/sbin/nologin\n")
                    File(root, "etc/group").takeIf { it.isFile }?.appendText("aid_app:x:$gid:root\n")
                }
            }
            File(root, "etc/environment").appendText("")
        }
    }

    private data class RootfsSource(val url: String, val sha256: String?, val release: String, val size: Long? = null)

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            it.body?.string().orEmpty()
        }

    private fun resolveRootfs(d: Distro, arch: String): RootfsSource = when (d) {
        Distro.ALPINE -> {
            val base = "https://dl-cdn.alpinelinux.org/alpine/v$PINNED_BRANCH/releases/$arch"
            try {
                val yaml = fetch("$base/latest-releases.yaml")
                val block = yaml.split("\n-").firstOrNull { it.contains("flavor: alpine-minirootfs") }
                    ?: throw IOException("no minirootfs in release list")
                fun field(k: String) = Regex("""(?m)^\s*$k:\s*(\S+)""").find(block)?.groupValues?.get(1)
                val file = field("file") ?: throw IOException("no file name")
                RootfsSource("$base/$file", field("sha256"), field("version") ?: file, field("size")?.toLongOrNull())
            } catch (e: Exception) {
                val v = FALLBACK_RELEASE
                RootfsSource("$base/alpine-minirootfs-$v-$arch.tar.gz", null, v)
            }
        }
        Distro.UBUNTU -> {
            val ua = when (arch) { "aarch64" -> "arm64"; "armv7" -> "armhf"; else -> "amd64" }
            val ubase = _ubuntuRelease.value.base
            val sums = fetch("$ubase/SHA256SUMS")
            // Newest point release of the LTS for this CPU.
            val hit = Regex("""(?m)^([0-9a-f]{64}) \*?(ubuntu-base-(\d+\.\d+(?:\.\d+)?)-base-$ua\.tar\.gz)$""").findAll(sums)
                .maxByOrNull { m -> m.groupValues[3].split('.').map { it.toInt() }.let { it.getOrElse(0) { 0 } * 10_000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } } }
                ?: throw IOException("no Ubuntu base image for $ua")
            RootfsSource("$ubase/${hit.groupValues[2]}", hit.groupValues[1], hit.groupValues[3])
        }
    }

    private fun download(url: String, to: File, onProgress: (Float?) -> Unit) {
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty download")
            val total = body.contentLength()
            var done = 0L
            var lastTick = 0L
            to.outputStream().buffered(256 * 1024).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastTick > 128 * 1024) {
                            lastTick = done
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
        }
    }

    // ---- Exec ---------------------------------------------------------------------------------

    /** Host path → path inside the guest, or null when the guest cannot see it. */
    fun toGuestPath(host: File): String? {
        val p = host.canonicalFile.path
        val ws = workspaceRoot.canonicalFile.path
        if (p == ws) return "/workspace"
        if (p.startsWith("$ws/")) return "/workspace/" + p.removePrefix("$ws/")
        storageRoot()?.canonicalFile?.path?.let { sd ->
            if (p == sd) return "/sdcard"
            if (p.startsWith("$sd/")) return "/sdcard/" + p.removePrefix("$sd/")
        }
        return null
    }

    /** A process running `/bin/sh -c [command]` inside [distro], with stdout and stderr merged. */
    fun buildProcess(command: String, hostCwd: File? = null, distro: Distro = active.value): ProcessBuilder {
        tmpDir.mkdirs()
        shmDir.mkdirs()
        refreshNetwork(distro)
        val root = rootfsOf(distro)
        val guestCwd = hostCwd?.let(::toGuestPath) ?: "/workspace"
        val args = mutableListOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--root-id",
            // glibc refuses to start on kernels it thinks are too old; report a current one.
            "--kernel-release=$FAKE_KERNEL",
            "--rootfs=${root.absolutePath}",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=${shmDir.absolutePath}:/dev/shm",
            "--bind=${workspaceRoot.canonicalPath}:/workspace",
        )
        if (File(root, "sys/.empty").isDirectory) args += "--bind=${File(root, "sys/.empty").absolutePath}:/sys/fs/selinux"
        // Android hides some /proc files from apps; programs that read them get fakes instead.
        for ((name, _) in FAKE_PROC) {
            val host = File(if (name == "sys_kernel_cap_last_cap") "/proc/sys/kernel/cap_last_cap" else "/proc/$name")
            val fake = File(root, "proc/.$name")
            if (fake.isFile && !canRead(host)) args += "--bind=${fake.absolutePath}:${host.path}"
        }
        storageRoot()?.let { sd ->
            args += "--bind=/storage"
            args += "--bind=${sd.canonicalPath}:/sdcard"
        }
        args += listOf(
            "--cwd=$guestCwd",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/sh",
            "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PIP_BREAK_SYSTEM_PACKAGES=1", "PIP_ROOT_USER_ACTION=ignore", "PIP_DISABLE_PIP_VERSION_CHECK=1",
            "CHARSET=UTF-8", "TZ=${posixTz()}", "PYTHONDONTWRITEBYTECODE=1",
            // link2symlink turns hard links into symlinks; keep uv and git off link().
            "UV_LINK_MODE=symlink", "GIT_CONFIG_COUNT=1", "GIT_CONFIG_KEY_0=core.createObject", "GIT_CONFIG_VALUE_0=rename",
        )
        if (distro == Distro.UBUNTU) args += listOf("DEBIAN_FRONTEND=noninteractive", "LC_ALL=C.UTF-8")
        args += systemProxyEnv()
        args += listOf("/bin/sh", "-c", command)
        val pb = ProcessBuilder(args).redirectErrorStream(true).directory(workspaceRoot)
        val env = pb.environment()
        env.clear()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.isFile) env["PROOT_LOADER_32"] = loader32.absolutePath
        if (noSeccompMarker(distro).isFile) env["PROOT_NO_SECCOMP"] = "1"
        env["PATH"] = "/system/bin"
        System.getenv("ANDROID_ROOT")?.let { env["ANDROID_ROOT"] = it }
        System.getenv("ANDROID_DATA")?.let { env["ANDROID_DATA"] = it }
        return pb
    }

    /** Android's system HTTP proxy as env vars, so apk, pip, curl and git use it too (empty when none). */
    private fun systemProxyEnv(): List<String> {
        val proxy = runCatching {
            val info = context.getSystemService(android.net.ConnectivityManager::class.java)?.defaultProxy
            if (info != null && !info.host.isNullOrBlank() && info.port > 0) "http://${info.host}:${info.port}" else ""
        }.getOrDefault("")
        if (proxy.isEmpty()) return emptyList()
        return listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY").map { "$it=$proxy" } +
            listOf("no_proxy=localhost,127.0.0.1,::1", "NO_PROXY=localhost,127.0.0.1,::1")
    }

    /** POSIX TZ for the device zone with a fixed name, since musl misparses names like "GMT+7". */
    private fun posixTz(): String {
        val secs = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L
        val hrs = -(secs / 3600).toInt()
        val mins = ((kotlin.math.abs(secs) % 3600) / 60).toInt()
        val sign = if (hrs >= 0) "+" else "-"
        return if (mins != 0) "LCL$sign${kotlin.math.abs(hrs)}:${"%02d".format(mins)}" else "LCL$sign${kotlin.math.abs(hrs)}"
    }

    /** Runs [command] in the guest and streams its output line by line. Returns the exit code. */
    fun exec(command: String, timeoutSeconds: Long = 600, distro: Distro = active.value, onLine: (String) -> Unit = {}): Int {
        val p = buildProcess(command, null, distro).start()
        val reader = Thread {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { runCatching { onLine(it) } } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            onLine("timed out after ${timeoutSeconds}s")
            return 124
        }
        reader.join(2000)
        return p.exitValue()
    }

    private object AndroidPlatform : TarExtractor.Platform {
        override fun symlink(target: String, link: File) { Os.symlink(target, link.path) }
        override fun chmod(file: File, mode: Int) { runCatching { Os.chmod(file.path, mode and 0xfff) } }
    }

    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        /**
         * Alpine branch the guest runs. 3.23+ ships apk-tools 3, which cannot write under PRoot,
         * so [installApkStatic] puts apk-tools 2.14 in front of it.
         */
        const val PINNED_BRANCH = "3.24"
        private const val FALLBACK_RELEASE = "3.24.4"

        private const val APK_STATIC_FILE = "apk-tools-static-2.14.13-r0.apk"
        private val APK_STATIC_SHA256 = mapOf(
            "aarch64" to "ad1c259de3b44ac98899520be6cfb2a24feab1482466a4b6951bd30e74a09c0f",
            "x86_64" to "7c9a784c1c2544e71e3de83b6be62a6e2c3ae7749248425ff8561dfdbece3b08",
            "armv7" to "3d9ad1cc673e99cd1b2a97166753bf7d0bafd333a4afd2962754760cb4741ae3",
        )

        /** True when an install marker is on the release in use (Alpine 3.24; the chosen Ubuntu LTS). */
        fun isCurrentRelease(d: Distro, marker: String, ubuntu: UbuntuRelease = UbuntuRelease.DEFAULT): Boolean = when (d) {
            Distro.ALPINE -> isPinnedRelease(marker)
            Distro.UBUNTU -> UbuntuRelease.ofMarker(marker) == ubuntu
        }

        /** True when a marker like "Alpine 3.21.9" is on the pinned branch. */
        fun isPinnedRelease(marker: String): Boolean =
            Regex("""(\d+)\.(\d+)""").find(marker)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" == PINNED_BRANCH } ?: false

        val DEFAULT_PACKAGES = Distro.ALPINE.defaultPackages
        private const val FAKE_KERNEL = "6.2.1-FARAI"

        /** name → contents, bound over /proc/<name> when Android hides it. */
        private val FAKE_PROC = listOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version $FAKE_KERNEL (farai@harness) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 136757\nnr_zone_inactive_anon 0\nnr_zone_active_anon 0\n",
            "sys_kernel_cap_last_cap" to "40\n",
        )

        private fun canRead(f: File): Boolean = runCatching { f.inputStream().use { it.read() }; true }.getOrDefault(false)
        private val MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine",
            "http://dl-cdn.alpinelinux.org/alpine",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
            "http://mirror.leaseweb.com/alpine",
        )
        private val PRERESOLVE = listOf(
            "dl-cdn.alpinelinux.org", "mirrors.tuna.tsinghua.edu.cn", "mirror.leaseweb.com",
            "ports.ubuntu.com", "archive.ubuntu.com", "security.ubuntu.com", "deb.nodesource.com",
            "pypi.org", "files.pythonhosted.org", "github.com", "codeload.github.com",
            "objects.githubusercontent.com", "raw.githubusercontent.com", "registry.npmjs.org",
        )
        private const val LOG_LINES = 200

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Deletes a tree without ever following a symlink out of it. */
        fun deleteTree(f: File) {
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
            if (!isLink && f.isDirectory) f.listFiles()?.forEach(::deleteTree)
            f.delete()
        }

        /** Size without following symlinks (the guest has absolute links into the host's /). */
        fun dirSize(dir: File): Long {
            var total = 0L
            val stack = ArrayDeque<File>().apply { add(dir) }
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
                if (isLink) continue
                if (f.isDirectory) f.listFiles()?.forEach { stack.add(it) } else total += f.length()
            }
            return total
        }
    }
}
