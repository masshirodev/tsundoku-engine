package mextensionserver.impl

import android.content.pm.PackageInfo
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MExtensionServerLoaderTest {
    @Test
    fun `factory children are recreated after preferences change`() {
        var configuredChildren = 1
        val jar = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile()
        val classLoader = URLClassLoader(emptyArray())
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = listOf("source-1"),
                packageInfo = PackageInfo(),
                jarFile = jar,
                classLoader = classLoader,
                sourceFactory = {
                    (1..configuredChildren).map { "source-$it" }
                },
            )

        configuredChildren = 3

        assertEquals(
            listOf("source-1", "source-2", "source-3"),
            extension.refreshFactorySources(),
        )
        assertEquals(3, extension.sources.size)
        extension.close()
    }

    @Test
    fun `the working folder is made again when it is removed under a running server`() {
        val dir = MExtensionServerLoader.workingDir()
        assertTrue(dir.deleteRecursively())
        assertFalse(dir.exists())

        val again = MExtensionServerLoader.workingDir()

        assertEquals(dir, again)
        assertTrue(again.isDirectory)
        // What a load writes first: the APK it was sent.
        File(again, "extension-test.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(delete())
        }
    }
}
