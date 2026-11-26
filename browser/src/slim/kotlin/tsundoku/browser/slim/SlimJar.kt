package tsundoku.browser.slim

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds the browser target's slim jar (`./gradlew :browser:slimJar`,
 * `doc/FORK.md`, "The slim jar"). The engine's shadow jar is about 100 MB;
 * a tab downloads and caches the whole jar it runs, so it gets only what it
 * can load:
 *
 * - **Classes reachable from the roots**: the browser entry, and every class
 *   in the packages extensions are compiled against (the extension API's
 *   surface: Mihon's and Aniyomi's source API, the Android shim, OkHttp,
 *   jsoup, kotlinx.serialization, ...), so an extension this build was never
 *   run with finds what it names. Reachable means through any reference a
 *   class file holds, a type named only in a signature included: CheerpJ
 *   loads such a class where HotSpot would not, so a list of what one
 *   desktop run loaded is too small.
 * - **Minus what a tab never runs**: the excluded packages are neither kept
 *   nor followed (Chromium's bridge, JOGL, JNA, the desktop window).
 * - **Resources** of the code kept: one is dropped when the nearest folder
 *   above it that holds classes holds none that were kept (ICU's 14 MB of
 *   data, whose classes nothing reaches; JNA's native libraries), and so
 *   are the dropped prefixes (fonts, Android's resources) and multi-release
 *   variants. Resources outside any code package (`META-INF/services`) stay.
 *
 * Inputs are read in order and the first copy of a name wins, which is how
 * the browser's own `ConnectInterceptor` takes OkHttp's place.
 *
 * Arguments: `--out FILE --inputs FILE` (one jar or class directory per line)
 * and any of `--root-class NAME`, `--root-package PREFIX`,
 * `--exclude PREFIX`, `--drop-resource PREFIX`, `--report FILE`, with names
 * as internal names (`a/b/C`, `a/b/`).
 */
fun main(args: Array<String>) {
    val options = Options.parse(args)
    val entries = Entries.read(options.inputs)
    val classes = Closure(entries, options).compute()
    val written = write(entries, classes, options)
    val report =
        buildString {
            appendLine("classes kept: ${classes.size} of ${entries.classNames().size}")
            appendLine("resources kept: ${written - classes.size}")
            appendLine("jar: ${options.out.length()} bytes")
        }
    print(report)
    options.report?.writeText(report + classes.sorted().joinToString("\n", postfix = "\n"))
}

internal class Options(
    val out: File,
    val inputs: List<File>,
    val rootClasses: List<String>,
    val rootPackages: List<String>,
    val excluded: List<String>,
    val droppedResources: List<String>,
    val report: File?,
) {
    fun isExcluded(name: String) = excluded.any(name::startsWith)

    fun isRoot(name: String) = name in rootClasses || rootPackages.any(name::startsWith)

    companion object {
        fun parse(args: Array<String>): Options {
            val values = mutableMapOf<String, MutableList<String>>()
            var i = 0
            while (i < args.size) {
                val key = args[i]
                require(key.startsWith("--") && i + 1 < args.size) { "Expected --option value at $key" }
                values.getOrPut(key) { mutableListOf() } += args[i + 1]
                i += 2
            }

            fun one(key: String) = values[key]?.single() ?: error("$key is required")
            return Options(
                out = File(one("--out")),
                inputs =
                    File(one("--inputs"))
                        .readLines()
                        .filter(String::isNotBlank)
                        .map(::File),
                rootClasses = values["--root-class"].orEmpty(),
                rootPackages = values["--root-package"].orEmpty(),
                excluded = values["--exclude"].orEmpty(),
                droppedResources = values["--drop-resource"].orEmpty(),
                report = values["--report"]?.single()?.let(::File),
            )
        }
    }
}

/** Every entry of every input, the first copy of each name only. */
internal class Entries private constructor(
    private val byName: LinkedHashMap<String, () -> ByteArray>,
    private val zips: List<ZipFile>,
) : AutoCloseable {
    fun names(): Set<String> = byName.keys

    fun classNames(): Set<String> =
        byName.keys
            .filter { it.endsWith(".class") }
            .map { it.removeSuffix(".class") }
            .toSet()

    fun bytes(name: String): ByteArray? = byName[name]?.invoke()

    override fun close() = zips.forEach(ZipFile::close)

    companion object {
        fun read(inputs: List<File>): Entries {
            val byName = LinkedHashMap<String, () -> ByteArray>()
            val zips = mutableListOf<ZipFile>()
            inputs.forEach { input ->
                when {
                    input.isDirectory ->
                        input.walkTopDown().filter(File::isFile).forEach { file ->
                            byName.putIfAbsent(file.relativeTo(input).invariantSeparatorsPath, file::readBytes)
                        }
                    input.isFile -> {
                        val zip = ZipFile(input).also(zips::add)
                        zip.entries().asSequence().filterNot(ZipEntry::isDirectory).forEach { entry ->
                            byName.putIfAbsent(entry.name) { zip.getInputStream(entry).use { it.readBytes() } }
                        }
                    }
                }
            }
            return Entries(byName, zips)
        }
    }
}

internal class Closure(
    private val entries: Entries,
    private val options: Options,
) {
    fun compute(): Set<String> {
        val available = entries.classNames()
        val kept = mutableSetOf<String>()
        val queue = ArrayDeque(available.filter { options.isRoot(it) && !options.isExcluded(it) && !isVersioned(it) })
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!kept.add(name)) continue
            references(name).forEach { ref ->
                if (ref !in kept && ref in available && !options.isExcluded(ref)) queue.addLast(ref)
            }
        }
        return kept
    }

    /** Every class a class file names: its constant pool, descriptors, signatures and annotations. */
    internal fun references(name: String): Set<String> {
        val bytes = entries.bytes("$name.class") ?: return emptySet()
        val found = mutableSetOf<String>()
        val collector =
            object : Remapper(Opcodes.ASM9) {
                override fun map(internalName: String): String {
                    found += internalName
                    return internalName
                }
            }
        ClassReader(bytes).accept(ClassRemapper(ClassNode(), collector), 0)
        // An array's element type is what loads; `[La/B;` arrives here as such.
        return found.mapNotNull(::elementType).toSet()
    }

    private fun elementType(name: String): String? {
        val bare = name.trimStart('[')
        return when {
            bare.startsWith("L") && bare.endsWith(";") -> bare.substring(1, bare.length - 1)
            bare.length == 1 && name.startsWith("[") -> null
            else -> bare
        }
    }
}

private fun isVersioned(name: String) = name.startsWith("META-INF/versions/")

private fun write(
    entries: Entries,
    classes: Set<String>,
    options: Options,
): Int {
    var count = 0
    options.out.parentFile?.mkdirs()
    ZipOutputStream(options.out.outputStream().buffered()).use { zip ->
        val dirs = sortedSetOf<String>()
        val codeDirs = entries.classNames().mapTo(HashSet()) { it.substringBeforeLast('/', "") }
        val keptDirs = classes.mapTo(HashSet()) { it.substringBeforeLast('/', "") }

        // The nearest folder at or above a resource's that holds classes, if any.
        fun codeDirOf(name: String): String? {
            var dir = name.substringBeforeLast('/', "")
            while (dir.isNotEmpty()) {
                if (dir in codeDirs) return dir
                dir = dir.substringBeforeLast('/', "")
            }
            return null
        }
        val names =
            entries.names().filter { name ->
                when {
                    name.endsWith(".class") -> name.removeSuffix(".class") in classes
                    isVersioned(name) -> false
                    name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") -> false
                    options.droppedResources.any(name::startsWith) || options.isExcluded(name) -> false
                    else -> codeDirOf(name)?.let { it in keptDirs } ?: true
                }
            }
        // Directory entries first: CheerpJ answers a package's resources by them.
        names.forEach { name ->
            val parts = name.split('/').dropLast(1)
            for (k in 1..parts.size) dirs += parts.take(k).joinToString("/") + "/"
        }
        dirs.forEach { dir ->
            zip.putNextEntry(ZipEntry(dir).apply { time = 0 })
            zip.closeEntry()
        }
        names.forEach { name ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(entries.bytes(name)!!)
            zip.closeEntry()
            count++
        }
    }
    entries.close()
    return count
}
