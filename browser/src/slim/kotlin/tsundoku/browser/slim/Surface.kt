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
import java.util.zip.ZipFile

/**
 * Which packages a folder of extension jars names outside themselves, with
 * how many of the jars name each: the extension API's surface as extensions
 * use it, to check the slim jar's root packages against
 * (`./gradlew :browser:extensionSurface -Pjars=<dir>`). JDK packages are
 * left out; CheerpJ provides them.
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull()?.takeIf { it.isNotBlank() } ?: error("Pass -Pjars=<folder of extension jars>"))
    val jars = dir.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().sorted()
    val users = sortedMapOf<String, MutableSet<String>>()
    jars.forEach { jar ->
        ZipFile(jar).use { zip ->
            val own = mutableSetOf<String>()
            val named = mutableSetOf<String>()
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                own += entry.name.removeSuffix(".class")
                val collector =
                    object : Remapper(Opcodes.ASM9) {
                        override fun map(internalName: String): String {
                            named += internalName
                            return internalName
                        }
                    }
                zip.getInputStream(entry).use { input ->
                    ClassReader(input.readBytes()).accept(ClassRemapper(ClassNode(), collector), 0)
                }
            }
            (named - own)
                .map { it.substringBeforeLast('/', "") + "/" }
                .filterNot {
                    it.startsWith("java/") ||
                        it.startsWith("javax/") ||
                        it.startsWith("jdk/") ||
                        it.startsWith("sun/") ||
                        it == "/"
                }.forEach { users.getOrPut(it) { sortedSetOf() } += jar.name }
        }
    }
    println("${jars.size} jars")
    users.forEach { (pkg, names) -> println("%4d  %s".format(names.size, pkg)) }
}
