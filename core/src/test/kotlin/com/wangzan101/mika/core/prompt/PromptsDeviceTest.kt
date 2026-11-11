package com.wangzan101.mika.core.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 手机存储里的提示词目录:那里有就用那里的,不合格的(空、坏占位符、反馈少键)退回 APK 里的并记下原因。 */
class PromptsDeviceTest {
    private val dir: File = Files.createTempDirectory("mika-prompts").toFile()

    @After fun restore() {
        Prompts.root = null
        dir.deleteRecursively()
    }

    private fun put(path: String, text: String): File = File(dir, path).also {
        it.parentFile?.mkdirs()
        it.writeText(text, Charsets.UTF_8)
    }

    @Test fun deviceFileReplacesTheBundledOne() {
        val bundled = Prompts.system("identity")
        put("system/identity.md", "You are Nobody.")
        Prompts.root = dir
        assertEquals("You are Nobody.", Prompts.system("identity"))
        // 目录里没有的文件照旧来自 APK
        assertEquals(Prompts.read("system/principles.md"), Prompts.system("principles"))
        assertTrue(bundled != Prompts.system("identity"))
        assertTrue(Prompts.rejections().isEmpty())
    }

    @Test fun savingTheFileIsEnough() {
        put("system/identity.md", "one")
        Prompts.root = dir
        assertEquals("one", Prompts.system("identity"))
        val file = put("system/identity.md", "second version")
        // 同一秒里改两次的文件靠修改时间分不出来:拨到两秒后,等于用户过一会儿再存
        file.setLastModified(file.lastModified() + 2_000)
        assertEquals("second version", Prompts.system("identity"))
    }

    @Test fun emptyFileFallsBackToTheApk() {
        val bundled = Prompts.system("identity")
        put("system/identity.md", "\n\n  \n")
        Prompts.root = dir
        assertEquals(bundled, Prompts.system("identity"))
        assertTrue(Prompts.rejections().containsKey("system/identity.md"))
    }

    /** 模板里没有的占位符代码不会填:整份不用,免得 {{who}} 原样发给模型。 */
    @Test fun unknownPlaceholderFallsBackToTheApk() {
        val bundled = Prompts.system("identity")
        put("system/identity.md", "You are {{who}}.")
        Prompts.root = dir
        assertEquals(bundled, Prompts.system("identity"))
        assertTrue(Prompts.rejections().getValue("system/identity.md").contains("{{who}}"))
    }

    /** 反馈按「## 键」取:少一个键代码就取不到,整份不用。 */
    @Test fun feedbackCannotDropKeys() {
        val bundled = Prompts.read("feedback/tools.md")
        put("feedback/tools.md", "## failure.network\nSomething else.\n")
        Prompts.root = dir
        assertEquals(bundled, Prompts.read("feedback/tools.md"))
        assertTrue(Prompts.rejections().getValue("feedback/tools.md").contains("feedback keys removed"))
    }

    @Test fun fixedFileIsUsedAgain() {
        put("system/identity.md", "")
        Prompts.root = dir
        Prompts.system("identity")
        assertTrue(Prompts.rejections().isNotEmpty())
        val file = put("system/identity.md", "Fixed.")
        file.setLastModified(file.lastModified() + 2_000)
        assertEquals("Fixed.", Prompts.system("identity"))
        assertTrue(Prompts.rejections().isEmpty())
    }

    @Test fun droppingTheFolderGoesBackToTheApk() {
        val bundled = Prompts.system("identity")
        put("system/identity.md", "Mine.")
        Prompts.root = dir
        assertEquals("Mine.", Prompts.system("identity"))
        Prompts.root = null
        assertEquals(bundled, Prompts.system("identity"))
    }
}
