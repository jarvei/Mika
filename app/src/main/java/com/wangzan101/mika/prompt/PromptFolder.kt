package com.wangzan101.mika.prompt

import android.content.Context
import android.os.Environment
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.prompt.PromptLibrary
import com.wangzan101.mika.core.prompt.Prompts
import java.io.File
import java.util.zip.ZipFile

/**
 * 手机存储里的提示词目录:整个 prompts/ 树的一份副本,Mika 读的是这一份,不是 APK 里的。
 *
 * 位置固定为 /sdcard/Mika/prompts(即 [Environment.getExternalStorageDirectory] 下的 Mika/prompts),
 * 需要「所有文件访问权限」(MANAGE_EXTERNAL_STORAGE);没有权限时什么也不动,提示词照旧全部来自 APK。
 * 启动时对齐一次:版本变了就把没动过的文件换成新版(见 [PromptLibrary])。
 */
object PromptFolder {
    const val RELATIVE = "Mika/prompts"
    private const val TAG = "MikaPrompts"

    /** 这一次启动之后目录的样子;开发者选项里显示。 */
    data class Status(
        val dir: File,
        val granted: Boolean,
        val files: Int,
        val edited: Int,
        val version: String?,
        val error: String? = null,
    ) {
        val active: Boolean get() = granted && error == null && version != null
    }

    @Volatile
    var status: Status? = null
        private set

    /** 手机存储里的目录(不管有没有权限)。 */
    fun dir(): File = File(Environment.getExternalStorageDirectory(), RELATIVE)

    /** 启动时调一次:没有权限就返回未授权;有权限就对齐目录并把它接给 [Prompts]。 */
    fun install(context: Context): Status {
        val dir = dir()
        val s = when {
            !Environment.isExternalStorageManager() -> Status(dir, granted = false, files = 0, edited = 0, version = null)
            else -> runCatching { sync(context.applicationContext, dir) }.getOrElse { e ->
                Log.w(TAG, "cannot sync $dir", e)
                Status(dir, granted = true, files = 0, edited = 0, version = null, error = e.message ?: e.toString())
            }
        }
        Prompts.root = if (s.granted && dir.isDirectory) dir else null
        status = s
        return s
    }

    /** 版本标识:发行号 + 提交短哈希,和「关于」页上看到的一样。 */
    fun version(): String = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_HASH}".trim()

    private fun sync(context: Context, dir: File): Status {
        val version = version()
        val current = PromptLibrary.current(dir)
        if (current?.version == version) {
            // 已经是这个版本:不读 APK、不动文件,直接接上
            return Status(dir, granted = true, files = current.files.size, edited = current.files.count { it.value.edited }, version = version)
        }
        val result = PromptLibrary.sync(dir, version, bundled(context))
        Log.i(TAG, "prompts aligned to $version: ${result.files} files, ${result.edited} edited")
        return Status(dir, granted = true, files = result.files, edited = result.edited, version = result.version)
    }

    /** APK 里的 prompts/:它们是 APK 根目录下的普通条目(和 classloader 读的是同一份)。 */
    private fun bundled(context: Context): Map<String, ByteArray> {
        val apk = File(context.applicationInfo.sourceDir)
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(apk).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory || !e.name.startsWith("prompts/")) continue
                out[e.name] = zip.getInputStream(e).use { it.readBytes() }
            }
        }
        return out
    }
}
