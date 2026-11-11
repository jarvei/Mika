package com.wangzan101.mika.core.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 拿编译出来的 APK 试一遍真事:把 APK 里的 prompts/ 原样放进目录(手机上 PromptFolder 做的就是这件事),
 * 再把 Prompts 指过去,读出来的每一份都要和 APK 里的字一样、一份都不被拒。
 * APK 没编译时跳过。
 */
class PromptSeedIntegrationTest {
    private val apk = File("../app/build/outputs/apk/perf/app-arm64-v8a-perf.apk").takeIf { it.isFile }
    private val dir: File = Files.createTempDirectory("mika-seed").toFile()

    @After fun clean() {
        Prompts.root = null
        dir.deleteRecursively()
    }

    @Test fun seededTreeReadsExactlyLikeTheApk() {
        assumeTrue("APK not built yet", apk != null)
        val entries = PromptLibrary.readApk(apk!!)
        assertTrue("APK has no prompts/", entries.isNotEmpty())
        // 键相对 prompts/:目录里是 system/identity.md,不是 prompts/system/identity.md
        assertTrue("keys are not relative", entries.keys.none { it.startsWith("prompts/") })
        assertTrue("system/identity.md" in entries)

        val result = PromptLibrary.sync(dir, "test", entries)
        assertEquals(entries.size, result.files)
        assertEquals(0, result.edited)
        // 目录的样子也对:文件在根下,没有再套一层 prompts/
        assertTrue(File(dir, "system/identity.md").isFile)
        assertFalse(File(dir, "prompts").exists())

        Prompts.root = dir
        for ((path, bytes) in entries) {
            assertEquals(path, String(bytes, Charsets.UTF_8).trim(), Prompts.read(path))
        }
        assertEquals(emptyMap<String, String>(), Prompts.rejections())
        // 刚放进去的一整份:一份不少、没有改过的、没有不能用的
        val scan = PromptLibrary.scan(dir)
        assertEquals(entries.size, scan.files)
        assertEquals(0, scan.edited)
        assertTrue(scan.rejected.toString(), scan.rejected.isEmpty())
    }
}
