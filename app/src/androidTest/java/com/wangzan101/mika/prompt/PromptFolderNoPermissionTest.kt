package com.wangzan101.mika.prompt

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.prompt.Prompts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机、没有「所有文件访问权限」时:目录落到 App 自己的外部目录里,照样能用、照样读。
 * 跑之前把权限收回:`adb shell appops set <包名> MANAGE_EXTERNAL_STORAGE default`;
 * 有权限时这组跳过。
 */
@RunWith(AndroidJUnit4::class)
class PromptFolderNoPermissionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun reset() {
        Prompts.root = null
    }

    @Test fun fallsBackToTheAppFolder() {
        assumeTrue("no se ha revocado el permiso", !Environment.isExternalStorageManager())
        val status = PromptFolder.install(context)
        assertFalse("sigue usando la carpeta pública: $status", status.granted)
        assertTrue("no es la carpeta del app: ${status.dir}", status.dir.absolutePath.contains("/Android/data/"))
        assertTrue("no se sembró: ${status.files}", status.files > 100)

        val identity = File(status.dir, "system/identity.md")
        assertTrue("falta ${identity.absolutePath}", identity.isFile)
        val original = identity.readText()
        try {
            identity.writeText("SIN PERMISO")
            assertEquals("SIN PERMISO", Prompts.system("identity"))
        } finally {
            identity.writeText(original)
        }
    }
}
