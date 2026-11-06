package dev.harness

import dev.harness.core.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillStoreTest {
    private fun ws(): File = Files.createTempDirectory("ws").toFile()

    @Test fun parsesFrontMatterIncludingFoldedDescription() {
        val fm = SkillStore.frontMatter("---\nname: pdf\ndescription: >\n  Makes PDFs.\n  Use for reports.\nlicense: MIT\n---\n# Body")
        assertEquals("pdf", fm["name"])
        assertEquals("Makes PDFs. Use for reports.", fm["description"])
        assertEquals("MIT", fm["license"])
    }

    @Test fun createListDisableRemove() {
        val store = SkillStore(ws())
        store.create("My Skill", "Does a thing. Use when asked.")
        val s = store.find("my-skill")!!
        assertEquals("Does a thing. Use when asked.", s.description)
        store.setEnabled(s, false)
        assertTrue(store.enabled().isEmpty())
        store.setEnabled(s, true)
        assertEquals(1, store.enabled().size)
        store.remove(s)
        assertTrue(store.list().isEmpty())
    }

    @Test fun readsClaudeFolderAndImportsZip() {
        val root = ws()
        File(root, ".claude/skills/cc/SKILL.md").apply { parentFile.mkdirs(); writeText("---\nname: cc\ndescription: from claude\n---\nhi") }
        val zip = File(root, "x.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("deploy/SKILL.md")); z.write("---\nname: deploy\ndescription: \"Ship it\"\n---\nsteps".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("deploy/scripts/run.sh")); z.write("echo hi".toByteArray()); z.closeEntry()
        }
        val store = SkillStore(root)
        store.import(zip)
        assertEquals(listOf("deploy", "cc"), store.list().map { it.name })
        val d = store.find("deploy")!!
        assertEquals("Ship it", d.description)
        assertEquals(listOf("scripts/run.sh"), store.files(d))
        assertEquals("steps", store.body(d))
    }

    @Test fun refusesPathsOutsideSkill() {
        val store = SkillStore(ws())
        val s = store.create("a", "b")
        assertFalse(runCatching { store.readFile(s, "../../secret") }.isSuccess)
        assertNotNull(store.readFile(s, "SKILL.md"))
    }
}

class AlpinePinTest {
    @org.junit.Test fun pinnedBranchDetection() {
        val R = dev.harness.core.linux.LinuxRuntime
        val A = dev.harness.core.linux.Distro.ALPINE
        val U = dev.harness.core.linux.Distro.UBUNTU
        org.junit.Assert.assertTrue(R.isPinnedRelease("Alpine Linux 3.24.4"))
        org.junit.Assert.assertTrue(R.isPinnedRelease("Alpine alpine-minirootfs-3.24.4-aarch64.tar.gz"))
        org.junit.Assert.assertFalse(R.isPinnedRelease("Alpine 3.21.9"))
        org.junit.Assert.assertTrue(R.isCurrentRelease(A, "Alpine Linux 3.24.4"))
        val U26 = dev.harness.core.linux.UbuntuRelease.RESOLUTE
        val U24 = dev.harness.core.linux.UbuntuRelease.NOBLE
        org.junit.Assert.assertTrue(R.isCurrentRelease(U, "Ubuntu 26.04.1", U26))
        org.junit.Assert.assertFalse(R.isCurrentRelease(U, "Ubuntu 24.04.5", U26))
        org.junit.Assert.assertTrue(R.isCurrentRelease(U, "Ubuntu 24.04.5", U24))
        org.junit.Assert.assertTrue(R.isCurrentRelease(U, "Ubuntu 24.04.3"))
        org.junit.Assert.assertEquals(U26, dev.harness.core.linux.UbuntuRelease.ofMarker("Ubuntu 26.04"))
    }
}
