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
 * 真机:提示词目录(/sdcard/Mika/prompts)真的在手机上被读到,删掉一份就回到 APK 里的那份。
 * 要先把「所有文件访问权限」给这个包(`adb shell appops set <包名> MANAGE_EXTERNAL_STORAGE allow`);
 * 改的是 system/identity.md,两个用例读完都恢复原样。
 */
@RunWith(AndroidJUnit4::class)
class PromptFolderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity get() = File(PromptFolder.dir(), "system/identity.md")

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
}
