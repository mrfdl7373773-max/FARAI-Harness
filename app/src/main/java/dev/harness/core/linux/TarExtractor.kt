package dev.harness.core.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal streaming extractor for (gzipped) ustar/GNU/pax tarballs: enough for an Alpine
 * minirootfs. Regular files, directories, symlinks and hardlinks are supported; device nodes
 * and FIFOs are skipped (PRoot binds the host's /dev).
 *
 * Symlinks and file modes go through [Platform] so the JVM tests and Android each use their
 * own system calls.
 */
class TarExtractor(private val platform: Platform) {

    interface Platform {
        fun symlink(target: String, link: File)
        fun chmod(file: File, mode: Int)
    }

    /** Extracts [gz] into [dest]. [onEntry] receives each entry name, for progress UIs. */
    fun extractTarGz(gz: InputStream, dest: File, onEntry: (String) -> Unit = {}): Int =
        extractTar(GZIPInputStream(gz, 64 * 1024), dest, onEntry)

    fun extractTar(input: InputStream, dest: File, onEntry: (String) -> Unit = {}): Int {
        dest.mkdirs()
        val root = dest.canonicalFile
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var count = 0
        val deferredModes = ArrayList<Pair<File, Int>>()

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker

            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8).toInt()
            val rawName = longName ?: paxPath ?: ustarName(header)
            val rawLink = longLink ?: paxLink ?: cString(header, 157, 100)

            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> {
                    val pax = parsePax(readString(input, size))
                    paxPath = pax["path"]; paxLink = pax["linkpath"]
                    continue
                }
                'g' -> { skip(input, padded(size)); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null

            val name = rawName.trimStart('/').removePrefix("./")
            if (name.isEmpty() || name == ".") { skip(input, padded(size)); continue }
            // Absolute symlinks inside a rootfs (var/run -> /run) are normal, so entries are
            // checked lexically rather than by resolving links on the host.
            if (name.split('/').any { it == ".." }) throw IOException("tar entry escapes destination: $name")
            val out = File(root, name)
            onEntry(name)
            count++

            when (type) {
                '5' -> { out.mkdirs(); deferredModes += out to mode }
                '2' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isSymlink(out)) out.delete()
                    platform.symlink(rawLink, out)
                }
                '1' -> {
                    // Hardlinks are not allowed in app storage on Android: copy the target instead.
                    val target = File(root, rawLink.trimStart('/').removePrefix("./"))
                    out.parentFile?.mkdirs()
                    if (target.isFile) {
                        target.copyTo(out, overwrite = true)
                        platform.chmod(out, mode)
                    }
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (isSymlink(out)) out.delete()
                    out.outputStream().buffered(64 * 1024).use { os -> copyExactly(input, os, size) }
                    skip(input, padded(size) - size)
                    platform.chmod(out, mode)
                    continue
                }
                else -> Unit // char/block devices, FIFOs: skipped
            }
            skip(input, padded(size))
        }
        // Directory modes last, so a read-only directory never blocks its own children.
        deferredModes.asReversed().forEach { (f, m) -> platform.chmod(f, m or 0b111_000_000) }
        return count
    }

    private fun isSymlink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun ustarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(h, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readString(input: InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) throw IOException("truncated tar")
        skip(input, padded(size) - size)
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    companion object {
        private const val BLOCK = 512

        fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

        fun parsePax(text: String): Map<String, String> {
            val map = HashMap<String, String>()
            var i = 0
            val bytes = text.toByteArray(Charsets.UTF_8)
            while (i < bytes.size) {
                var sp = i
                while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
                val len = String(bytes, i, sp - i).trim().toIntOrNull() ?: break
                if (len <= 0 || i + len > bytes.size) break
                val record = String(bytes, sp + 1, i + len - sp - 2, Charsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq > 0) map[record.substring(0, eq)] = record.substring(eq + 1)
                i += len
            }
            return map
        }

        fun cString(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end] != 0.toByte()) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
            if (h[off].toInt() and 0x80 != 0) { // GNU base-256 for big sizes
                var v = 0L
                for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xff)
                return v
            }
            val s = cString(h, off, len).trim()
            return if (s.isEmpty()) 0 else s.toLong(8)
        }

        fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) { if (read == 0) return false else throw IOException("truncated tar") }
                read += n
            }
            return true
        }

        fun skip(input: InputStream, n: Long) {
            var left = n
            val buf = ByteArray(8192)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("truncated tar")
                left -= r
            }
        }

        fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
            var left = size
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("truncated tar")
                out.write(buf, 0, r)
                left -= r
            }
        }
    }
}
