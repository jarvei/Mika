package com.wangzan101.mika.core.prompt

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 随 App 发布的提示词,都在资源目录 prompts/ 下,分层见 prompts/README.md:
 * system/ 身份(L0)、capabilities/ 各能力一段(L1)、tools/ 每个工具的说明、feedback/ 工具与主循环给模型的反馈、
 * roles/ 内部角色(列标准、核查、描述、定位……)。
 * 文字里的 {{name}} 由调用方填;漏填的直接报错,不把占位符发给模型。
 *
 * 外部目录([root]):手机存储里放一份同样的树(见 PromptLibrary)。那里有这份文件就用它,
 * 没有或不合格(空文件、出现模板里没有的占位符、反馈少了键)就用 APK 里的,原因记在 [rejections] 里。
 */
object Prompts {
    /** APK 里那份;进程内不变。 */
    private val shippedCache = ConcurrentHashMap<String, String>()

    /** 被放弃的外部文件:路径 → 原因。界面拿它提示用户。 */
    private val rejected = ConcurrentHashMap<String, String>()

    /** 外部提示词目录;null 表示只用 APK 里的那份。由 App 启动时设置。 */
    @Volatile
    var root: File? = null
        set(value) {
            field = value
            rejected.clear()
        }

    private const val MAX_CHARS = 200_000

    fun read(path: String): String = fromDevice(path) ?: shipped(path)

    /** 外部文件里被放弃的那些(路径 → 原因);文件改好后下次读就没这条了。 */
    fun rejections(): Map<String, String> = rejected.toMap()

    fun system(name: String): String = read("system/$name.md")

    fun capability(name: String): String = read("capabilities/$name.md")

    fun role(name: String): String = read("roles/$name.md")

    /** 入口工具那一组收着时的短说明(prompts/entries/<工具名>.md)。 */
    fun entry(tool: String): String = read("entries/$tool.md")

    /** 一个工具发给模型的说明(prompts/tools/<file>.md),填好占位符。 */
    fun tool(file: String, vararg vars: Pair<String, Any?>): String = fill(read("tools/$file.md"), vars.toMap())

    /** 一遍替换:填进去的值(屏上的字、用户的话)里即使有 {{…}} 也原样保留。 */
    fun fill(template: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(template) { m ->
        val k = m.groupValues[1]
        check(k in vars) { "unfilled placeholder {{$k}} in: ${template.take(80)}" }
        vars[k].toString()
    }

    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")

    /** APK 里的原文(去掉首尾空白)。 */
    private fun shipped(path: String): String = shippedCache.getOrPut(path) {
        checkNotNull(Prompts::class.java.getResourceAsStream("/prompts/$path")) { "missing prompts/$path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }

    /**
     * 外部目录里同一路径的原文;没有或不合格时返回 null(调用方用 APK 里的)。
     * 每次都读盘:文件很小,而按修改时间做缓存会在「同一秒内改成同样大小」时读到旧内容。
     */
    private fun fromDevice(path: String): String? {
        val dir = root ?: return null
        val file = File(dir, path)
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return reject(path, "cannot read")
        val text = raw.trim()
        if (text.isEmpty()) return reject(path, "empty file")
        if (text.length > MAX_CHARS) return reject(path, "too long (${text.length} chars, max $MAX_CHARS)")
        // 模板里没有的占位符,代码不会填,发到模型那里就是字面的 {{…}}
        val unknown = placeholders(text) - placeholders(shipped(path))
        if (unknown.isNotEmpty()) return reject(path, "placeholders the app does not fill: ${unknown.sorted().joinToString(" ") { "{{$it}}" }}")
        // 反馈按「## 键」取:少了键是代码里按名字要的,整个文件不认
        if (path.startsWith("feedback/")) {
            val lost = Feedback.parse(shipped(path)).map { it.first }.toSet() - Feedback.parse(text).map { it.first }.toSet()
            if (lost.isNotEmpty()) return reject(path, "feedback keys removed: ${lost.sorted().joinToString(" ")}")
        }
        rejected.remove(path)
        return text
    }

    private fun reject(path: String, why: String): String? {
        rejected[path] = why
        return null
    }

    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()
}
