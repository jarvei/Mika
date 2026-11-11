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
        Prompts.root = null
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

    /** 阿拉伯语、波斯语会把数字换成别的字符:哈希得跟语言无关,不然换语言后所有文件都像改过。 */
    @Test fun hashesAreAsciiWhateverTheLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A"))
        } finally {
            java.util.Locale.setDefault(before)
        }
        val hash = PromptLibrary.current(dir)!!.files.getValue("system/identity.md").hash
        assertTrue(hash, hash.matches(Regex("[0-9a-f]{64}")))
    }

    /** 改回和 APK 里一样的内容之后,不再算「改过」,以后照常跟着升级走。 */
    @Test fun contentEqualToTheShippedOneStopsBeingEdited() {
        PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A"))
        file("system/identity.md").writeText("mine")
        PromptLibrary.sync(dir, "v2", bundle("system/identity.md" to "A2"))
        assertTrue(PromptLibrary.current(dir)!!.files.getValue("system/identity.md").edited)
        file("system/identity.md").writeText("A2")
        PromptLibrary.sync(dir, "v3", bundle("system/identity.md" to "A2"))
        assertFalse(PromptLibrary.current(dir)!!.files.getValue("system/identity.md").edited)
    }

    /** 逐份看一眼:在的、改过的、不能用的(占位符是代码不会填的)。 */
    @Test fun scanCountsWhatIsThereAndWhatIsBroken() {
        PromptLibrary.sync(dir, "v1", bundle("system/identity.md" to "A", "system/principles.md" to "B", "roles/title.md" to "C"))
        file("system/identity.md").writeText("mía")
        file("system/principles.md").writeText("You are {{nadie}}.")
        file("roles/title.md").delete()
        Prompts.root = dir
        val scan = PromptLibrary.scan(dir)
        assertEquals(2, scan.files)                       // roles/title.md 删掉了,不算
        assertEquals(2, scan.edited)                      // 两份都和清单里记的不一样
        assertEquals(setOf("system/principles.md"), scan.rejected.keys)
        assertTrue(scan.rejected.getValue("system/principles.md").contains("{{nadie}}"))
    }
}
