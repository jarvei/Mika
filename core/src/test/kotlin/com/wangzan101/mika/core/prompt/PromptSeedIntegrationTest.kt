package com.wangzan101.mika.core.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 拿编译出来的 APK 试一遍真事:把 APK 里的 prompts/ 原样放进目录(PromptFolder 在手机上做的就是这个),
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
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(apk!!).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory || !e.name.startsWith("prompts/")) continue
                entries[e.name] = zip.getInputStream(e).use { it.readBytes() }
            }
        }
        assertTrue("APK has no prompts/", entries.isNotEmpty())

        val result = PromptLibrary.sync(dir, "test", entries)
        assertEquals(entries.size, result.files)
        assertEquals(0, result.edited)

        Prompts.root = dir
        for ((name, bytes) in entries) {
            val path = name.removePrefix("prompts/")
            assertEquals(path, String(bytes, Charsets.UTF_8).trim(), Prompts.read(path))
        }
        assertEquals(emptyMap<String, String>(), Prompts.rejections())
    }
}
