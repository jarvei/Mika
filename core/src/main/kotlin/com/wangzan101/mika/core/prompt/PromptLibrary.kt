package com.wangzan101.mika.core.prompt

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 手机存储里的提示词目录(Mika/prompts)的维护:目录是 APK 里 prompts/ 的一份副本,模型读的是这一份。
 *
 * 清单(.seed)记着每个文件是原样(plain)还是自己改过(edited),以及上次对齐的是哪个版本。
 * 版本变了以后:
 * - 没动过的文件换成新版;
 * - 改过的留着不动,记成 edited,以后再升级也不再覆盖;
 * - 删掉的不会被补回来(等于「这一份用 APK 里的」);整个目录没了才会重新放一份。
 */
object PromptLibrary {
    const val MANIFEST = ".seed"
    const val NOTES = "LEEME.txt"

    data class Entry(val hash: String, val edited: Boolean)

    data class Manifest(val version: String?, val files: Map<String, Entry>)

    /** 对齐之后目录的样子:对齐的版本、文件数、其中改过的。 */
    data class Result(val version: String, val files: Int, val edited: Int)

    /** 目录现在的样子(逐份看过):有几份在、几份改过、哪几份不能用(路径 → 原因)。 */
    data class Scan(val files: Int, val edited: Int, val rejected: Map<String, String>)

    /** 清单里一份文件现在的样子。 */
    data class Item(val path: String, val present: Boolean, val edited: Boolean, val rejected: String?)

    /** 目录里现有的清单;没有或读不了时返回 null。 */
    fun current(dir: File): Manifest? =
        File(dir, MANIFEST).takeIf { it.isFile }?.let { runCatching { parse(it.readText(Charsets.UTF_8)) }.getOrNull() }

    /**
     * 从 APK 里读出提示词:它们是 APK 根目录下的普通条目 `prompts/<层>/<文件>`,
     * 键去掉前面的 `prompts/`,好按目录(也就是 [Prompts.root])原样放。
     */
    fun readApk(apk: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(apk).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory || !e.name.startsWith("prompts/")) continue
                out[e.name.removePrefix("prompts/")] = zip.getInputStream(e).use { it.readBytes() }
            }
        }
        return out
    }

    fun parse(text: String): Manifest {
        var version: String? = null
        val files = LinkedHashMap<String, Entry>()
        for (raw in text.lineSequence()) {
            // 用别的编辑器存过(CRLF)也认
            val line = raw.trimEnd('\r')
            if (line.startsWith("version ")) {
                version = line.removePrefix("version ").trim().takeIf { it.isNotEmpty() }
                continue
            }
            val parts = line.trim().split(' ')
            if (parts.size == 3 && (parts[0] == "plain" || parts[0] == "edited")) {
                files[parts[2]] = Entry(parts[1], parts[0] == "edited")
            }
        }
        return Manifest(version, files)
    }

    fun render(version: String, files: Map<String, Entry>): String = buildString {
        appendLine("version $version")
        for ((path, e) in files) appendLine("${if (e.edited) "edited" else "plain"} ${e.hash} $path")
    }

    /**
     * 把目录对齐到 [version]:按 [bundled] 里的路径(APK 里的 prompts/ 原样)放文件、更新清单。
     * 只读 [dir] 里已有的文件,不碰清单和说明以外的任何东西。
     */
    fun sync(dir: File, version: String, bundled: Map<String, ByteArray>): Result {
        dir.mkdirs()
        val old = current(dir) ?: Manifest(null, emptyMap())
        val next = LinkedHashMap<String, Entry>()
        var edited = 0
        for ((path, bytes) in bundled) {
            val target = File(dir, path)
            val existing = if (target.isFile) runCatching { sha256(target.readBytes()) }.getOrNull() else null
            val shipped = sha256(bytes)
            val prev = old.files[path]
            // 没动过才换;目录里本来就有、又不是我们放的文件(来历不明)当作改过
            val untouched = prev != null && !prev.edited && existing == prev.hash
            val write = when {
                existing == null && prev != null -> false          // 自己删了:不补
                existing == null -> true                           // 目录里没有:放一份
                else -> untouched                                  // 有:只看是不是原样
            }
            if (write) {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                next[path] = Entry(shipped, edited = false)
            } else {
                val hash = existing ?: continue
                // 和 APK 里那份一样就不算改过(手动改回去的,以后照常跟着升级走)
                val isEdited = hash != shipped
                next[path] = Entry(hash, isEdited)
                if (isEdited) edited++
            }
        }
        File(dir, MANIFEST).writeText(render(version, next), Charsets.UTF_8)
        writeNotes(dir, version)
        return Result(version, next.size, edited)
    }

    /**
     * 逐份看一遍目录:有几份在、几份和清单里记的不一样(改过或坏了)、哪几份不能用。
     * 只有改过的那几份要交给 [Prompts] 验一遍(刚放进去的原样文件按定义是能用的),所以不读 APK。
     * 调用前 [Prompts.root] 要指向 [dir],不然验的还是 APK 里的。
     */
    fun scan(dir: File): Scan {
        val items = listing(dir)
        return Scan(
            files = items.count { it.present },
            edited = items.count { it.edited },
            rejected = items.mapNotNull { i -> i.rejected?.let { i.path to it } }.toMap(),
        )
    }

    /** 清单里每一份现在的样子:[present] 在不在、[edited] 改没改、[rejected] 不能用的原因。 */
    fun listing(dir: File): List<Item> {
        val manifest = current(dir) ?: return emptyList()
        return manifest.files.map { (path, entry) ->
            val file = File(dir, path)
            when {
                !file.isFile -> Item(path, present = false, edited = false, rejected = null)
                else -> {
                    val hash = runCatching { sha256(file.readBytes()) }.getOrNull()
                    if (hash == null || hash == entry.hash) {
                        Item(path, present = true, edited = false, rejected = null)
                    } else {
                        runCatching { Prompts.read(path) }
                        Item(path, present = true, edited = true, rejected = Prompts.rejections()[path])
                    }
                }
            }
        }
    }

    /** 用 APK 里的那一份盖回去,清单里记成原样。 */
    fun restore(dir: File, path: String, bytes: ByteArray): Boolean {
        val manifest = current(dir) ?: return false
        runCatching {
            File(dir, path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        }.onFailure { return false }
        val next = LinkedHashMap(manifest.files)
        next[path] = Entry(sha256(bytes), edited = false)
        runCatching { File(dir, MANIFEST).writeText(render(manifest.version.orEmpty(), next), Charsets.UTF_8) }
            .onFailure { return false }
        return true
    }

    /** 整个目录重来:删掉再按 APK 放一份(自己的改动也没了)。 */
    fun reset(dir: File, version: String, bundled: Map<String, ByteArray>): Result {
        dir.deleteRecursively()
        return sync(dir, version, bundled)
    }

    /** 目录里的说明:自己写过的(开头不是我们那行)就不动,否则每次对齐都换成当前版本的说法。 */
    private fun writeNotes(dir: File, version: String) {
        val notes = File(dir, NOTES)
        val text = NOTES_TEXT.replace("{version}", version)
        if (notes.isFile) {
            val current = runCatching { notes.readText(Charsets.UTF_8) }.getOrNull()
            if (current != null && !current.startsWith("Mika · prompts del sistema")) return
        }
        runCatching { notes.writeText(text, Charsets.UTF_8) }
    }

    private fun sha256(bytes: ByteArray): String =
        // Locale.ROOT:有的语言把数字换成别的字符(阿拉伯语、波斯语),哈希就跟别的语言对不上了
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    val NOTES_TEXT = """
        Mika · prompts del sistema
        ==========================

        Aquí vive el prompt del sistema de esta versión de Mika. Lo que hay en esta
        carpeta se lee antes que lo que va dentro de la APK; los archivos que no
        están aquí salen de la APK.

          system/identity.md     quién es Mika
          system/principles.md   cómo trabaja
          capabilities/          una sección por grupo de herramientas
          tools/                 la descripción de cada herramienta
          entries/               la descripción corta de las herramientas de entrada
          feedback/              lo que el modelo recibe como resultado o aviso
          roles/                 los roles internos (verificador, memoria, título…)

        Reglas
        - Si borras un archivo, Mika usa el que lleva dentro la APK.
        - Un archivo vacío, o con un {{placeholder}} que la app no rellena, se
          ignora y se usa el de la APK.
        - En feedback/ no se pueden quitar apartados: si falta una clave, se
          ignora el archivo entero.
        - Al actualizar la app, los archivos que no hayas tocado se sustituyen por
          los nuevos; los que hayas tocado se quedan y no se vuelven a tocar.
        - system/ y capabilities/ se leen en cada tarea: guarda y ya está.
          tools/, entries/, feedback/ y roles/ se leen una vez por arranque:
          reinicia Mika para ver esos cambios.

        Sin «acceso a todos los archivos» Mika usa su propia carpeta
        (Android/data/<paquete>/files/prompts), que algunos gestores no dejan
        abrir; al conceder el permiso se traslada todo a esta.

        En Ajustes › Opciones de desarrollo › Prompt del sistema ves qué has
        tocado, por qué se ignora un archivo, y puedes restaurar uno o todos.

        Los archivos son la versión {version}. El manifiesto .seed no hace falta
        tocarlo: es lo que permite saber qué has cambiado tú.

        Ojo: cualquier app con «acceso a todos los archivos» puede escribir aquí,
        y esto es el prompt del sistema. Es el precio de editarlo sin recompilar.
    """.trimIndent() + "\n"
}
