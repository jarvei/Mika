package com.wangzan101.mika.core.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 目录对齐:第一次全放一份;升级时没动过的换新版,改过的留着,删掉的不补。 */
class PromptLibraryTest {
    private val dir: File = Files.createTempDirectory("mika-promptlib").toFile()

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun bundle(vararg files: Pair<String, String>): Map<String, ByteArray> =
        files.associate { (path, text) -> path to text.toByteArray(Charsets.UTF_8) }

    private fun file(path: String) = File(dir, path)

    @Test fun firstSyncWritesEverythingAndNotes() {
        val r = PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A", "roles/title.md" to "B"))
        assertEquals(2, r.files)
        assertEquals(0, r.edited)
        assertEquals("A", file("system/identity.md").readText())
        assertEquals("B", file("roles/title.md").readText())
        assertTrue(file(PromptLibrary.NOTES).readText().contains("v1"))
        assertEquals("v1", PromptLibrary.current(dir)?.version)
    }

    @Test fun untouchedFilesFollowTheNewVersion() {
        PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A"))
        val r = PromptLibrary.sync(dir, "v2", bundle("system/identity.md" to "A2"))
        assertEquals("A2", file("system/identity.md").readText())
        assertEquals(0, r.edited)
        assertEquals("v2", PromptLibrary.current(dir)?.version)
    }

    @Test fun editedFilesStayEditedAcrossVersions() {
        PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A"))
        file("system/identity.md").writeText("mine")
        PromptLibrary.sync(dir, "v2", bundle("system/identity.md" to "A2"))
        assertEquals("mine", file("system/identity.md").readText())
        assertTrue(PromptLibrary.current(dir)!!.files.getValue("system/identity.md").edited)
        PromptLibrary.sync(dir, "v3", bundle("system/identity.md" to "A3"))
        assertEquals("mine", file("system/identity.md").readText())
    }

    @Test fun deletedFilesAreNotRestored() {
        PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A", "system/principles.md" to "B"))
        file("system/identity.md").delete()
        PromptLibrary.sync(dir, "v2", bundle("system/identity.md" to "A2", "system/principles.md" to "B2"))
        assertFalse(file("system/identity.md").exists())
        assertEquals("B2", file("system/principles.md").readText())
    }

    /** 没有清单的目录(用户自己放的):已有的不动,缺的补上。 */
    @Test fun existingFilesWithoutManifestAreKept() {
        file("system").mkdirs()
        file("system/identity.md").writeText("mine")
        val r = PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A", "system/principles.md" to "B"))
        assertEquals("mine", file("system/identity.md").readText())
        assertEquals("B", file("system/principles.md").readText())
        assertEquals(1, r.edited)
        assertTrue(PromptLibrary.current(dir)!!.files.getValue("system/identity.md").edited)
    }

    @Test fun manifestRoundTrips() {
        val text = PromptLibrary.render("0.1.39 abc1234", mapOf(
            "system/identity.md" to PromptLibrary.Entry("aa", edited = false),
            "roles/title.md" to PromptLibrary.Entry("bb", edited = true),
        ))
        val m = PromptLibrary.parse(text)
        assertEquals("0.1.39 abc1234", m.version)
        assertEquals(2, m.files.size)
        assertFalse(m.files.getValue("system/identity.md").edited)
        assertTrue(m.files.getValue("roles/title.md").edited)
    }

    @Test fun unreadableManifestCountsAsNone() {
        dir.mkdirs()
        file(PromptLibrary.MANIFEST).writeText("basura\n")
        assertNull(PromptLibrary.parse("").version)
        assertEquals(0, PromptLibrary.parse("").files.size)
        assertEquals(0, PromptLibrary.current(dir)!!.files.size)
    }
}
