package dev.harness

import dev.harness.core.linux.TarExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarExtractorTest {
    private val platform = object : TarExtractor.Platform {
        override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), File(target).toPath()) }
        override fun chmod(file: File, mode: Int) {
            file.setExecutable(mode and 0b001_000_000 != 0, true)
        }
    }

    private fun header(name: String, type: Char, size: Int = 0, link: String = "", mode: Int = 420): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%07o\u0000".format(mode).toByteArray().copyInto(h, 100)
        "%011o\u0000".format(size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        link.toByteArray().copyInto(h, 157)
        "ustar\u0000".toByteArray().copyInto(h, 257)
        "00".toByteArray().copyInto(h, 263)
        // checksum (not verified by the extractor, but keep the archive valid)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
        return h
    }

    private fun tar(build: ByteArrayOutputStream.() -> Unit): ByteArray {
        val raw = ByteArrayOutputStream().apply(build).apply { write(ByteArray(1024)) }.toByteArray()
        return ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(raw) } }.toByteArray()
    }

    private fun ByteArrayOutputStream.file(name: String, body: String, mode: Int = 420) {
        val bytes = body.toByteArray()
        write(header(name, '0', bytes.size, mode = mode))
        write(bytes)
        write(ByteArray(((bytes.size + 511) / 512) * 512 - bytes.size))
    }

    @Test
    fun extractsFilesDirsSymlinksHardlinksAndLongNames() {
        val longName = "usr/lib/" + "a".repeat(120) + ".txt"
        val gz = tar {
            write(header("./etc/", '5', mode = 493))
            file("./etc/os-release", "NAME=Alpine\n")
            file("./bin/busybox", "#!fake", mode = 493)
            write(header("./bin/sh", '2', link = "/bin/busybox"))
            write(header("./bin/ash", '1', link = "./bin/busybox"))
            val ln = longName.toByteArray()
            write(header("././@LongLink", 'L', ln.size))
            write(ln); write(ByteArray(512 - ln.size % 512))
            file("ignored-name", "long")
            write(header("./dev/null", '3'))
        }
        val dest = Files.createTempDirectory("rootfs").toFile()
        val n = TarExtractor(platform).extractTarGz(ByteArrayInputStream(gz), dest)
        assertEquals("NAME=Alpine\n", File(dest, "etc/os-release").readText())
        assertTrue(Files.isSymbolicLink(File(dest, "bin/sh").toPath()))
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(dest, "bin/sh").toPath()).toString())
        assertEquals("#!fake", File(dest, "bin/ash").readText())
        assertTrue(File(dest, "bin/busybox").canExecute())
        assertEquals("long", File(dest, longName).readText())
        assertTrue(!File(dest, "dev/null").exists())
        assertEquals(7, n)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsPathTraversal() {
        val gz = tar { file("../evil", "x") }
        TarExtractor(platform).extractTarGz(ByteArrayInputStream(gz), Files.createTempDirectory("rootfs").toFile())
    }

    @Test
    fun parsesPaxRecords() {
        val rec = "path=some/very/long/path\n"
        val len = rec.length + 3
        val pax = "$len $rec"
        assertEquals("some/very/long/path", TarExtractor.parsePax(pax)["path"])
    }
}
