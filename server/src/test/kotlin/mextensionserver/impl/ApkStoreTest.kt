package mextensionserver.impl

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ApkStoreTest {
    private val dir = Files.createTempDirectory("apk-store-test")
    private val store = ApkStore(dir.toFile())

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private val apk = "an apk".toByteArray()
    private val key = sha256(apk)

    @Test
    fun `keeps an APK under its handle, and leaves no partial file`() {
        store.put(key, apk)

        assertContentEquals(apk, store.get(key))
        assertEquals(listOf("$key.apk"), dir.listDirectoryEntries().map { it.fileName.toString() })
    }

    // A handle arrives from the client, so it must never become a path.
    @Test
    fun `refuses a handle that isn't a SHA-256`() {
        store.put("../../etc/passwd", apk)
        store.put(key.uppercase(), apk)

        assertNull(store.get("../../etc/passwd"))
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `a handle the process forgot is reloaded from the store`() {
        var loads = 0
        fun cache() =
            ExtensionInstanceCache(
                keyOf = ::sha256,
                load = { data: ByteArray -> loads++; String(data).also { store.put(sha256(data), data) } },
                dispose = { _: String -> },
            )

        cache().useAndGetKey(apk) { it }
        // A fresh cache is a restarted engine: the instance is gone, the APK isn't.
        val afterRestart = cache()

        assertEquals("an apk", afterRestart.useByKeyOrStore(key, store) { it }?.value)
        assertEquals(2, loads)
        assertNull(afterRestart.useByKeyOrStore(sha256("never sent".toByteArray()), store) { it })
        assertNull(cache().useByKeyOrStore(key, null) { it })
    }
}
