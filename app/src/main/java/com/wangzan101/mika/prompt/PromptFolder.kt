package com.wangzan101.mika.prompt

import android.content.Context
import android.os.Environment
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.prompt.PromptLibrary
import com.wangzan101.mika.core.prompt.Prompts
import java.io.File

/**
 * 手机存储里的提示词目录:整个 prompts/ 树的一份副本,Mika 读的是这一份,不是 APK 里的。
 *
 * 位置固定为 /sdcard/Mika/prompts(即 [Environment.getExternalStorageDirectory] 下的 Mika/prompts),
 * 需要「所有文件访问权限」(MANAGE_EXTERNAL_STORAGE);没有权限时什么也不动,提示词照旧全部来自 APK。
 * 启动时只做对齐(版本变了才读 APK、动文件);逐份数一遍留给 [inspect],打开设置里那一页时才跑。
 */
object PromptFolder {
    const val RELATIVE = "Mika/prompts"
    private const val TAG = "MikaPrompts"

    /** 目录的状态;开发者选项里显示。[files]/[edited]/[ignored] 在 [inspect] 之后才是逐份数出来的。 */
    data class Status(
        val dir: File,
        val granted: Boolean,
        val files: Int,
        val edited: Int,
        val ignored: Int,
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

    /** 启动时调一次(设置里重新对齐也用它):没有权限就返回未授权;有权限就对齐目录并接给 [Prompts]。 */
    fun install(context: Context): Status {
        val dir = dir()
        val granted = Environment.isExternalStorageManager()
        var error: String? = null
        if (granted) {
            runCatching { sync(context.applicationContext, dir) }.onFailure {
                Log.w(TAG, "cannot sync $dir", it)
                error = it.message ?: it.toString()
            }
        }
        Prompts.root = if (granted && dir.isDirectory) dir else null
        val manifest = if (granted) PromptLibrary.current(dir) else null
        return Status(
            dir = dir,
            granted = granted,
            files = manifest?.files?.size ?: 0,
            edited = manifest?.files?.count { it.value.edited } ?: 0,
            ignored = 0,
            version = manifest?.version,
            error = error,
        ).also { status = it }
    }

    /**
     * 逐份看一遍目录(有几份、几份改过、哪几份不能用),并把它记下来。
     * 140 个文件要读一遍,所以启动时不跑:打开设置里那一页、或者点了重新对齐才跑。
     */
    fun inspect(): Status? {
        val base = status ?: return null
        if (!base.granted || Prompts.root == null) return base
        val scan = runCatching { PromptLibrary.scan(base.dir) }.getOrElse {
            Log.w(TAG, "cannot scan ${base.dir}", it)
            return base
        }
        scan.rejected.forEach { (path, why) -> Log.w(TAG, "ignored $path: $why") }
        return base.copy(files = scan.files, edited = scan.edited, ignored = scan.rejected.size).also { status = it }
    }

    /**
     * 目录对齐的是哪一份 APK:发行号 + 提交短哈希 + APK 自己的大小和安装时间。
     * 只看提交不够:改了提示词还没提交就重编时提交号不变,目录就永远停在旧的那份。
     */
    fun version(context: Context): String {
        val apk = File(context.applicationInfo.sourceDir)
        return "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_HASH} ${apk.length()}-${apk.lastModified() / 1000}".trim()
    }

    /** 版本没变就什么也不做(不读 APK、不动文件)。 */
    private fun sync(context: Context, dir: File) {
        val version = version(context)
        if (PromptLibrary.current(dir)?.version == version) return
        val result = PromptLibrary.sync(dir, version, PromptLibrary.readApk(File(context.applicationInfo.sourceDir)))
        Log.i(TAG, "prompts aligned to $version: ${result.files} files, ${result.edited} edited")
    }
}
