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
 * 有两处,按权限挑:
 * - 有「所有文件访问权限」(MANAGE_EXTERNAL_STORAGE)时用公开的那份:/sdcard/Mika/prompts,
 *   任何编辑器和文件管理器都够得着;
 * - 没有权限时用 App 自己的外部目录(Android/data/<包名>/files/prompts):不用权限,但有的
 *   文件管理器进不去。刚授予权限时,把免权限那份整个搬到公开的那份(含清单)。
 *
 * 启动时只做对齐(版本变了才读 APK、动文件);逐份数一遍留给 [inspect],打开设置里那一页时才跑。
 */
object PromptFolder {
    const val RELATIVE = "Mika/prompts"
    private const val TAG = "MikaPrompts"

    /** 目录的状态;设置页里显示。[files]/[edited]/[ignored] 在 [inspect] 之后才是逐份数出来的。 */
    data class Status(
        val dir: File,
        /** 用的是公开目录(有「所有文件访问权限」);false 表示用的是 App 自己的外部目录。 */
        val granted: Boolean,
        val files: Int,
        val edited: Int,
        val ignored: Int,
        val version: String?,
        val error: String? = null,
    ) {
        val active: Boolean get() = error == null && version != null
    }

    @Volatile
    var status: Status? = null
        private set

    /** 现在用的目录:有权限用公开的那份,没有就用 App 自己的。 */
    fun dir(context: Context): File =
        if (Environment.isExternalStorageManager()) File(Environment.getExternalStorageDirectory(), RELATIVE)
        else privateDir(context)

    private fun privateDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "prompts")

    /** 启动时调一次(设置里重新对齐也用它):对齐目录并把它接给 [Prompts]。 */
    fun install(context: Context): Status {
        val app = context.applicationContext
        val dir = dir(app)
        var error: String? = null
        if (Environment.isExternalStorageManager()) migrate(privateDir(app), dir)
        runCatching { sync(app, dir) }.onFailure {
            Log.w(TAG, "cannot sync $dir", it)
            error = it.message ?: it.toString()
        }
        Prompts.root = if (dir.isDirectory) dir else null
        val manifest = PromptLibrary.current(dir)
        return Status(
            dir = dir,
            granted = Environment.isExternalStorageManager(),
            files = manifest?.files?.size ?: 0,
            edited = manifest?.files?.count { it.value.edited } ?: 0,
            ignored = 0,
            version = manifest?.version,
            error = error,
        ).also { status = it }
    }

    /** 逐份看一遍目录(有几份、几份改过、哪几份不能用),并把它记下来。设置页打开时调。 */
    fun inspect(): Status? {
        val base = status ?: return null
        if (Prompts.root == null) return base
        val scan = runCatching { PromptLibrary.scan(base.dir) }.getOrElse {
            Log.w(TAG, "cannot scan ${base.dir}", it)
            return base
        }
        scan.rejected.forEach { (path, why) -> Log.w(TAG, "ignored $path: $why") }
        return base.copy(files = scan.files, edited = scan.edited, ignored = scan.rejected.size).also { status = it }
    }

    /** 清单里每一份现在的样子(设置页那一列用);读盘,要在后台线程调。 */
    fun items(): List<PromptLibrary.Item> {
        val base = status ?: return emptyList()
        if (Prompts.root == null) return emptyList()
        return runCatching { PromptLibrary.listing(base.dir) }.getOrDefault(emptyList())
    }

    /** 把一条从 APK 里盖回去。 */
    fun restore(context: Context, path: String): Boolean {
        val base = status ?: return false
        val bytes = runCatching { PromptLibrary.readApk(File(context.applicationInfo.sourceDir))[path] }.getOrNull() ?: return false
        val ok = runCatching { PromptLibrary.restore(base.dir, path, bytes) }.getOrDefault(false)
        if (ok) inspect()
        return ok
    }

    /** 整个目录重来:删掉再按 APK 放一份(自己的改动也没了)。 */
    fun reset(context: Context): Boolean {
        val base = status ?: return false
        val ok = runCatching {
            PromptLibrary.reset(base.dir, version(context), PromptLibrary.readApk(File(context.applicationInfo.sourceDir)))
        }.isSuccess
        if (ok) install(context)
        return ok
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

    /** 刚给了权限:把免权限那份整个搬到公开的那份(那边已经有自己的一份就不动)。 */
    private fun migrate(from: File, to: File) {
        if (from == to || PromptLibrary.current(to) != null) return
        val manifest = PromptLibrary.current(from) ?: return
        runCatching {
            to.mkdirs()
            for ((path, _) in manifest.files) {
                val src = File(from, path)
                if (!src.isFile) continue
                val dst = File(to, path)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = false)
            }
            File(from, PromptLibrary.MANIFEST).copyTo(File(to, PromptLibrary.MANIFEST), overwrite = true)
            Log.i(TAG, "moved the prompt folder from $from to $to")
        }.onFailure { Log.w(TAG, "cannot move the prompt folder to $to", it) }
    }
}
