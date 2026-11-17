package mextensionserver.impl

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.network.MemoryCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import xyz.nulldev.androidcompat.webkit.HeadlessWebView
import xyz.nulldev.androidcompat.webkit.WebCookie
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebViewSupportTest {
    private val dir = createTempDirectory("webview-support").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        HeadlessWebView.intoHttpClient = null
        HeadlessWebView.fromHttpClient = null
    }

    private fun apk(vararg dex: Pair<String, String>): File =
        File(dir, "extension-${dex.size}-${System.nanoTime()}.apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write("Landroid/webkit/WebView;".toByteArray()) // not code: must not count
                dex.forEach { (name, strings) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(("dex\n035\u0000" + strings).toByteArray())
                }
            }
        }

    @Test
    fun anExtensionThatNamesWebViewInItsCodeUsesOne() {
        assertTrue(WebViewSupport.usesWebView(apk("classes.dex" to "Lokhttp3/Request;\u0000Landroid/webkit/WebView;\u0000")))
        // Multidex: the reference may sit in any classes file.
        assertTrue(WebViewSupport.usesWebView(apk("classes.dex" to "Lokhttp3/Request;", "classes2.dex" to "Landroid/webkit/WebView;")))
    }

    @Test
    fun anExtensionWithoutItDoesNot() {
        assertFalse(WebViewSupport.usesWebView(apk("classes.dex" to "Lokhttp3/Request;\u0000Landroid/webkit/WebViewClient;")))
    }

    @Test
    fun cookiesAPageSetReachTheHttpClientsJar() {
        val jar = MemoryCookieJar()
        WebViewSupport.connect(jar)

        HeadlessWebView.intoHttpClient!!.invoke(
            "https://www.example.org/watch/1",
            WebCookie("cf_clearance", "abc", ".example.org", "/", secure = true, httpOnly = true, expiresAt = 4_102_444_800_000),
        )
        HeadlessWebView.intoHttpClient!!.invoke(
            "https://www.example.org/watch/1",
            WebCookie("session", "s1", "www.example.org", "/", secure = false, httpOnly = false, expiresAt = null),
        )

        val sent = jar.loadForRequest("https://cdn.example.org/a.m3u8".toHttpUrl()).associateBy { it.name }
        assertEquals("abc", sent["cf_clearance"]?.value, "a domain cookie reaches the site's other hosts")
        assertFalse("session" in sent, "a host-only cookie stays on its host")
        assertEquals(
            "s1",
            jar
                .loadForRequest("https://www.example.org/".toHttpUrl())
                .single { it.name == "session" }
                .value,
        )
    }

    @Test
    fun theHttpClientsCookiesGoIntoThePageWithTheirScope() {
        val jar = MemoryCookieJar()
        val url = "https://www.example.org/".toHttpUrl()
        jar.addCookie(Cookie.parse(url, "token=t; Domain=example.org; Path=/; Secure")!!)
        jar.addCookie(Cookie.parse(url, "host=h; Path=/")!!)
        WebViewSupport.connect(jar)

        val cookies = HeadlessWebView.fromHttpClient!!.invoke("https://www.example.org/watch").associateBy { it.name }

        val token = assertNotNull(cookies["token"])
        assertEquals(".example.org", token.domain)
        assertTrue(token.secure)
        assertEquals("www.example.org", cookies["host"]?.domain)
    }
}
