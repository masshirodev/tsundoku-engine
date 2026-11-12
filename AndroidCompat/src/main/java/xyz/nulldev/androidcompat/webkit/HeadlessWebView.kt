package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import dev.datlag.kcef.KCEFClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.Date

/** A cookie as both sides of [HeadlessWebView]'s cookie copy see it. */
data class WebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    /** Epoch milliseconds, or null for a session cookie. */
    val expiresAt: Long?,
)

/**
 * The engine's one headless Chromium, as WebViews see it ([KcefWebViewProvider]).
 *
 * Off unless `ENGINE_WEBVIEW` is set ([WebViewConfig]): then nothing here
 * touches KCEF, and WebViews behave as upstream's.
 */
object HeadlessWebView {
    private val logger = KotlinLogging.logger {}

    private val environmentConfig: WebViewConfig by lazy { WebViewConfig.fromEnvironment() }
    private val chromium: ChromiumRuntime<KCEFClient> by lazy {
        ChromiumRuntime(config.maxPages, config.pageLifetimeMs, KcefStarter(config))
    }

    /** Tests only: a WebView's lifecycle without the environment, or Chromium. */
    @Volatile
    internal var configOverride: WebViewConfig? = null

    @Volatile
    internal var runtimeOverride: ChromiumRuntime<KCEFClient>? = null

    val config: WebViewConfig get() = configOverride ?: environmentConfig

    val enabled: Boolean get() = config.enabled

    internal val runtime: ChromiumRuntime<KCEFClient> get() = runtimeOverride ?: chromium

    /**
     * In Mihon and Aniyomi a WebView and OkHttp share one cookie jar
     * (AndroidCookieJar over CookieManager), and WebView extensions rely on
     * it: they clear a check or earn a token in the page, then fetch with
     * OkHttp. Here the engine's client keeps its own jar, so the server
     * plugs it in: cookies a page sets go [intoHttpClient], and the client's
     * cookies for a URL come [fromHttpClient] before the page loads it.
     */
    @Volatile
    var intoHttpClient: ((url: String, cookie: WebCookie) -> Unit)? = null

    @Volatile
    var fromHttpClient: ((url: String) -> List<WebCookie>)? = null

    private val timer =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "webview-timer").apply { isDaemon = true }
        }

    /** Runs [block] after [millis], on a thread of its own: a WebView's deadlines. */
    internal fun later(
        millis: Long,
        block: () -> Unit,
    ) {
        timer.schedule({ runCatching(block).onFailure { logger.warn(it) { "A WebView deadline failed" } } }, millis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** A WebView extension was loaded: have Chromium ready (downloaded, started) by its first call. */
    fun prepare() {
        if (enabled) runtime.prepare()
    }

    /** For `GET /webview`. Never starts Chromium. */
    fun statusJson(): String =
        buildJsonObject {
            put("enabled", enabled)
            if (!enabled) {
                put("state", "off")
                return@buildJsonObject
            }
            val status = runtime.status()
            put("state", status.state)
            put("phase", status.phase?.let(::JsonPrimitive) ?: JsonNull)
            put("percent", status.percent?.let(::JsonPrimitive) ?: JsonNull)
            put("error", status.error?.let(::JsonPrimitive) ?: JsonNull)
            put("openPages", status.openPages)
            put("waitingPages", status.waitingPages)
            put("maxPages", status.maxPages)
            put("chromium", ChromiumSetup.JBR_RELEASE)
            put("dir", config.dir.path)
        }.toString()

    /** Before a page loads [url]: the HTTP client's cookies for it, into Chromium's jar. */
    internal fun pushCookies(url: String) {
        val cookies =
            try {
                fromHttpClient?.invoke(url).orEmpty()
            } catch (error: Throwable) {
                logger.warn(error) { "Reading the HTTP client's cookies for a WebView failed" }
                return
            }
        if (cookies.isEmpty()) return
        val manager = CefCookieManager.getGlobalManager() ?: return
        val now = Date()
        cookies.forEach { cookie ->
            manager.setCookie(
                url,
                CefCookie(
                    cookie.name,
                    cookie.value,
                    cookie.domain,
                    cookie.path,
                    cookie.secure,
                    cookie.httpOnly,
                    now,
                    now,
                    cookie.expiresAt != null,
                    cookie.expiresAt?.let(::Date),
                ),
            )
        }
    }

    /** After a page loaded [url]: what it set, into CookieManager (what extensions read) and the HTTP client. */
    internal fun pullCookies(url: String) {
        if (!url.startsWith("http")) return
        val manager = CefCookieManager.getGlobalManager() ?: return
        manager.visitUrlCookies(url, true) { cookie, _, _, _ ->
            try {
                val copy =
                    WebCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = cookie.domain,
                        path = cookie.path ?: "/",
                        secure = cookie.secure,
                        httpOnly = cookie.httponly,
                        expiresAt = cookie.expires?.time?.takeIf { cookie.hasExpires },
                    )
                android.webkit.CookieManager
                    .getInstance()
                    .setCookie(url, "${copy.name}=${copy.value}; Domain=${copy.domain}; Path=${copy.path}")
                intoHttpClient?.invoke(url, copy)
            } catch (error: Throwable) {
                logger.warn(error) { "Copying a WebView cookie failed" }
            }
            true
        }
    }
}
