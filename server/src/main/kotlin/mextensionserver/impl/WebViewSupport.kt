package mextensionserver.impl

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.network.MemoryCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nulldev.androidcompat.webkit.HeadlessWebView
import xyz.nulldev.androidcompat.webkit.WebCookie
import java.io.File
import java.util.zip.ZipFile

/** The server's half of the headless WebView (doc/FORK.md, `ENGINE_WEBVIEW`). */
object WebViewSupport {
    private val webViewClass = "Landroid/webkit/WebView;".toByteArray()

    /**
     * Whether an APK's code names android.webkit.WebView. A dex file keeps
     * every type it refers to as a descriptor string, minified or not, so a
     * byte search finds it without converting anything.
     */
    fun usesWebView(apk: File): Boolean =
        ZipFile(apk).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .any { entry -> zip.getInputStream(entry).use { contains(it.readBytes(), webViewClass) } }
        }

    /** The engine's HTTP cookie jar and the WebView's, joined as Mihon's are (HeadlessWebView). */
    fun connect(jar: MemoryCookieJar) {
        HeadlessWebView.intoHttpClient = { url, cookie ->
            url.toHttpUrlOrNull()?.let { httpUrl -> toOkHttp(httpUrl.host, cookie)?.let(jar::addCookie) }
        }
        HeadlessWebView.fromHttpClient = { url ->
            url
                .toHttpUrlOrNull()
                ?.let(jar::loadForRequest)
                .orEmpty()
                .map(::toWebCookie)
        }
    }

    internal fun toOkHttp(
        host: String,
        cookie: WebCookie,
    ): Cookie? {
        val domain = cookie.domain.removePrefix(".").ifBlank { host }
        return runCatching {
            Cookie
                .Builder()
                .name(cookie.name)
                .value(cookie.value)
                .apply { if (cookie.domain.startsWith(".")) domain(domain) else hostOnlyDomain(domain) }
                .path(cookie.path.ifBlank { "/" })
                .apply {
                    if (cookie.secure) secure()
                    if (cookie.httpOnly) httpOnly()
                    cookie.expiresAt?.let(::expiresAt)
                }.build()
        }.getOrNull()
    }

    internal fun toWebCookie(cookie: Cookie): WebCookie =
        WebCookie(
            name = cookie.name,
            value = cookie.value,
            domain = if (cookie.hostOnly) cookie.domain else ".${cookie.domain}",
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            expiresAt = cookie.expiresAt.takeIf { cookie.persistent },
        )

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        val last = haystack.size - needle.size
        outer@ for (start in 0..last) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
