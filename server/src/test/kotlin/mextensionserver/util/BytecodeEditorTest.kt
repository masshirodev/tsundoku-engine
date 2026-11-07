package mextensionserver.util

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BytecodeEditorTest {
    @Test
    fun `repairs missing frames and dex constructors`() {
        val jar = Files.createTempFile("bytecode-editor-test", ".jar")
        try {
            writeJar(
                jar,
                mapOf(
                    "a.class" to dexStyleFunction(),
                    "b.class" to dexStyleUnaryFunction(),
                    "b2.class" to dexStyleUnaryFunctionWithArgument(),
                    "h.class" to dexStyleLambda(),
                    "j.class" to dexStyleHolder(),
                    "SourceBase.class" to sourceBase(),
                    "SourceOne.class" to concreteSource("SourceOne"),
                    "SourceTwo.class" to concreteSource("SourceTwo"),
                    "VerifierFixture.class" to verifierFixture(),
                ),
            )

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val fixture = Class.forName("VerifierFixture", true, loader)
                val result = fixture.getMethod("create", Boolean::class.javaPrimitiveType).invoke(null, false)
                assertEquals("a", result.javaClass.name)
                assertEquals(
                    "j",
                    fixture
                        .getMethod("createHolder")
                        .invoke(null)
                        .javaClass.name,
                )
                assertEquals(
                    "h",
                    Class
                        .forName("h", true, loader)
                        .getField("INSTANCE")
                        .get(null)
                        .javaClass.name,
                )
                assertNull(fixture.getMethod("callFunction").invoke(null))
                val sources = fixture.getMethod("createSources").invoke(null) as Array<*>
                assertEquals(2, sources.size)
                assertTrue(
                    sources.map { it?.javaClass?.name }.toSet() == setOf("SourceOne", "SourceTwo"),
                )
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    // R8 drops trivial constructors all the way up a chain, which ART allows,
    // and dex2jar then folds `new-instance Singleton` plus `Object.<init>` into
    // `new Object`. With an abstract class between the singleton and Object,
    // the store into its INSTANCE failed verification (Aethon Webcomics 1.6.0:
    // "Type 'java/lang/Object' is not assignable to 'p'").
    @Test
    fun `restores a singleton whose abstract parent lost its constructor too`() {
        val jar = Files.createTempFile("bytecode-editor-test", ".jar")
        try {
            writeJar(
                jar,
                mapOf(
                    "q.class" to constructorlessClass("q", "java/lang/Object", abstract = true),
                    "p.class" to dexStyleSingleton("p", "q"),
                ),
            )

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val instance = Class.forName("p", true, loader).getField("INSTANCE").get(null)
                assertEquals("p", instance.javaClass.name)
                assertEquals("q", instance.javaClass.superclass.name)
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    // dex2jar can write a max_stack smaller than the method needs. The repair's
    // frame analysis then threw "Insufficient maximum stack size" and the whole
    // method was skipped, so an erased `new Object` stayed erased and the
    // putfield into it failed verification (Weeb Central 1.6.26 as re-released
    // on 2026-11-07, h.intercept @387: "Type 'java/lang/Object' is not
    // assignable to 'x'"). One pass must repair it, as the loader runs one.
    @Test
    fun `repairs an erased allocation in a method whose max stack dex2jar understated`() {
        val jar = Files.createTempFile("bytecode-editor-test", ".jar")
        try {
            writeJar(
                jar,
                mapOf(
                    "x.class" to constructorlessClassWithFlag("x"),
                    "h.class" to dexStyleFieldWriter("h", "x", understatedMaxStack = true),
                ),
            )

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val made = Class.forName("h", true, loader).getMethod("make").invoke(null)
                assertEquals("x", made.javaClass.name)
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun constructorlessClassWithFlag(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "a", "Z", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /**
     * `make()` as dex2jar writes R8's `new-instance x` with x's trivial
     * constructor removed: `new Object`, Object.<init>, kept in a local, then a
     * field of x written through it.
     */
    private fun dexStyleFieldWriter(
        name: String,
        owner: String,
        understatedMaxStack: Boolean,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, "java/lang/Object", null)
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", "()Ljava/lang/Object;", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ICONST_0)
                visitFieldInsn(Opcodes.PUTFIELD, owner, "a", "Z")
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                // The method needs 2; dex2jar's output said less.
                visitMaxs(if (understatedMaxStack) 1 else 2, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun constructorlessClass(
        name: String,
        superName: String,
        abstract: Boolean,
    ): ByteArray {
        val writer = ClassWriter(0)
        val access = Opcodes.ACC_PUBLIC or if (abstract) Opcodes.ACC_ABSTRACT else Opcodes.ACC_FINAL
        writer.visit(Opcodes.V1_8, access, name, null, superName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dexStyleSingleton(
        name: String,
        superName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, superName, null)
        writer
            .visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "INSTANCE",
                "L$name;",
                null,
                null,
            ).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", "L$name;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 0)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun writeJar(
        jar: Path,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(Files.newOutputStream(jar).buffered()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun dexStyleUnaryFunction(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "b",
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function1"),
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC,
                "invoke",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dexStyleUnaryFunctionWithArgument(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "b2",
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function1"),
        )
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC,
                "invoke",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dexStyleLambda(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "h",
            null,
            "kotlin/jvm/internal/Lambda",
            arrayOf("kotlin/jvm/functions/Function0"),
        )
        writer
            .visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "INSTANCE",
                "Lh;",
                null,
                null,
            ).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "kotlin/jvm/internal/Lambda")
                visitInsn(Opcodes.DUP)
                visitInsn(Opcodes.ICONST_0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "kotlin/jvm/internal/Lambda", "<init>", "(I)V", false)
                visitFieldInsn(Opcodes.PUTSTATIC, "h", "INSTANCE", "Lh;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(3, 0)
                visitEnd()
            }
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "invoke", "()Ljava/lang/Object;", null, null)
            .apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dexStyleHolder(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "j", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun sourceBase(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "SourceBase",
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun concreteSource(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, "SourceBase", null)
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "SourceBase", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dexStyleFunction(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "a",
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function2"),
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC,
                "invoke",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 3)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun verifierFixture(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "VerifierFixture",
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "create",
                "(Z)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                val construct = org.objectweb.asm.Label()
                visitCode()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, construct)
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitLabel(construct)
                visitTypeInsn(Opcodes.NEW, "a")
                visitVarInsn(Opcodes.ASTORE, 1)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "createHolder",
                "()Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ICONST_1)
                visitFieldInsn(Opcodes.PUTFIELD, "j", "value", "I")
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "callFunction",
                "()Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "kotlin/jvm/functions/Function1",
                    "invoke",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 0)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "createSources",
                "()[LSourceBase;",
                null,
                null,
            ).apply {
                visitCode()
                visitInsn(Opcodes.ICONST_2)
                visitTypeInsn(Opcodes.ANEWARRAY, "SourceBase")
                repeat(2) { index ->
                    visitInsn(Opcodes.DUP)
                    visitInsn(if (index == 0) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    visitTypeInsn(Opcodes.NEW, "SourceBase")
                    visitInsn(Opcodes.DUP)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, "SourceBase", "<init>", "()V", false)
                    visitInsn(Opcodes.AASTORE)
                }
                visitInsn(Opcodes.ARETURN)
                visitMaxs(5, 0)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
