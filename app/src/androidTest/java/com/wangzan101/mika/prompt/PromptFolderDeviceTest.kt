package com.wangzan101.mika.prompt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.prompt.Prompts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机:提示词目录(/sdcard/Mika/prompts)真的在手机上被读到,删掉一份就回到 APK 里的那份,
 * 逐份检查能数出改过和不能用的。要先把「所有文件访问权限」给这个包
 * (`adb shell appops set <包名> MANAGE_EXTERNAL_STORAGE allow`);
 * 改的是 system/identity.md,每个用例读完都恢复原样。
 */
@RunWith(AndroidJUnit4::class)
class PromptFolderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity get() = File(PromptFolder.dir(context), "system/identity.md")

    @After fun reset() {
        Prompts.root = null
    }

    @Test fun readsTheFolderOnThePhone() {
        val status = PromptFolder.install(context)
        assertTrue("sin «Acceso a todos los archivos»: $status", status.granted)
        assertNull("error al alinear: ${status.error}", status.error)
        assertTrue("la carpeta tiene pocos ficheros: ${status.files}", status.files > 100)
        assertTrue("falta ${identity.absolutePath}", identity.isFile)

        val original = identity.readText()
        try {
            identity.writeText("MARCADOR EN EL MOVIL")
            assertEquals("MARCADOR EN EL MOVIL", Prompts.system("identity"))
        } finally {
            identity.writeText(original)
        }
        assertEquals(original.trim(), Prompts.system("identity"))
    }

    @Test fun deletingAFileFallsBackToTheApk() {
        PromptFolder.install(context)
        val original = identity.readText()
        // 原文从 APK 里取一份做对照
        val keep = Prompts.root
        Prompts.root = null
        val bundled = Prompts.read("system/identity.md")
        Prompts.root = keep
        try {
            identity.writeText("MARCADOR EN EL MOVIL")
            assertEquals("MARCADOR EN EL MOVIL", Prompts.system("identity"))
            assertTrue(identity.delete())
            // 目录里没有这一份:回到 APK 里的,和改之前一样
            assertEquals(bundled, Prompts.system("identity"))
            assertTrue(Prompts.rejections().isEmpty())
        } finally {
            identity.writeText(original)
        }
    }

    /** 设置里那一行数的就是这个:改过的份数、以及因为不合格被忽略的份数。 */
    @Test fun inspectCountsEditsAndIgnoredFiles() {
        PromptFolder.install(context)
        val original = identity.readText()
        try {
            identity.writeText("MARCADOR EN EL MOVIL")
            val edited = PromptFolder.inspect()
            assertTrue("$edited", edited != null && edited.edited >= 1)
            assertEquals("$edited", 0, edited!!.ignored)

            identity.writeText("You are {{nadie}}.")
            val broken = PromptFolder.inspect()
            assertTrue("${broken}", broken != null && broken.ignored >= 1)
            // 不能用:读的时候用的还是 APK 里那份
            assertTrue(Prompts.system("identity").startsWith("You are Mika"))
        } finally {
            identity.writeText(original)
        }
    }

    /** 设置页那个「恢复」按钮:一份从 APK 里盖回去。 */
    @Test fun restorePutsTheAppFileBack() {
        PromptFolder.install(context)
        val original = identity.readText()
        try {
            identity.writeText("MARCADOR EN EL MOVIL")
            assertEquals("MARCADOR EN EL MOVIL", Prompts.system("identity"))
            assertTrue(PromptFolder.restore(context, "system/identity.md"))
            assertEquals(original.trim(), Prompts.system("identity"))
        } finally {
            identity.writeText(original)
        }
    }

    /** 「恢复所有文件」:目录整个重来,改过的都回到 App 里的那份。 */
    @Test fun resetPutsEveryFileBack() {
        PromptFolder.install(context)
        val original = identity.readText()
        try {
            identity.writeText("MARCADOR EN EL MOVIL")
            assertTrue(PromptFolder.inspect()!!.edited >= 1)
            assertTrue(PromptFolder.reset(context))
            assertEquals(0, PromptFolder.inspect()!!.edited)
            assertEquals(original.trim(), Prompts.system("identity"))
        } finally {
            identity.writeText(original)
        }
    }
}
