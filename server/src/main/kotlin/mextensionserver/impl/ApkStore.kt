package mextensionserver.impl

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loaded APKs kept on disk under their SHA-256, which is also their handle,
 * so a handle survives a restart: without it every source answers 409 after
 * one, and the client has to resend and the engine reconvert every APK.
 * Optional: unset ([ENV]), handles last as long as the process, as upstream.
 */
internal class ApkStore(
    private val dir: File,
) {
    companion object {
        const val ENV = "ENGINE_APK_DIR"
        private val HANDLE = Regex("[0-9a-f]{64}")

        fun fromEnv(): ApkStore? = System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { ApkStore(File(it)) }
    }

    fun put(
        key: String,
        data: ByteArray,
    ) {
        val target = file(key) ?: return
        if (target.isFile) return
        dir.mkdirs()
        // Written beside the target and renamed, so a crash mid-write never
        // leaves a truncated APK under a valid handle.
        val partial = File(dir, "$key.apk.partial")
        partial.writeBytes(data)
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun get(key: String): ByteArray? = file(key)?.takeIf { it.isFile }?.readBytes()

    // A handle comes from the client, so it is never used as a path unless it
    // is exactly a SHA-256.
    private fun file(key: String): File? = if (HANDLE.matches(key)) File(dir, "$key.apk") else null
}

/**
 * The loaded instance for [key], reloading it from [store] when the process
 * no longer has it (after a restart); null when neither does.
 */
internal fun <T, R> ExtensionInstanceCache<T>.useByKeyOrStore(
    key: String,
    store: ApkStore?,
    block: (T) -> R,
): ExtensionInstanceCache.KeyedResult<R>? =
    useByKey(key, block)
        ?: store?.get(key)?.let { data ->
            useAndGetKey(data, block).also { check(it.key == key) { "Stored APK $key no longer matches its handle" } }
        }
