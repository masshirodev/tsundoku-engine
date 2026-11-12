package xyz.nulldev.androidcompat.webkit

import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.datlag.kcef.KCEFClient
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A WebView driven the way every WebView extension drives one: created and
 * loaded on a looper thread, answered through its WebViewClient. When
 * Chromium can't start, the extension must hear a load error on that thread,
 * not a throw (the spike saw AniZone's request thread die instead).
 */
class KcefWebViewProviderTest {
    private val looperThread =
        CompletableFuture<Looper>().also { looper ->
            Thread({
                Looper.prepare()
                looper.complete(Looper.myLooper())
                Looper.loop()
            }, "test-webview-looper").apply {
                isDaemon = true
                start()
            }
        }
    private val looper: Looper = looperThread.get(5, TimeUnit.SECONDS)
    private val handler = Handler(looper)

    @BeforeTest
    fun useTheHeadlessProvider() {
        WebView.setProviderFactory { view -> KcefWebViewProvider(view) }
    }

    @AfterTest
    fun reset() {
        HeadlessWebView.configOverride = null
        HeadlessWebView.runtimeOverride = null
        looper.quitSafely()
    }

    private fun enableWith(starter: ChromiumRuntime.Starter<KCEFClient>) {
        HeadlessWebView.configOverride = WebViewConfig(enabled = true, dir = File("unused"), maxPages = 1, pageLifetimeMs = 60_000)
        HeadlessWebView.runtimeOverride = ChromiumRuntime(1, 60_000, starter)
    }

    private class FailingStarter(
        private val reason: Throwable,
    ) : ChromiumRuntime.Starter<KCEFClient> {
        var starts = 0

        override fun start(progress: (ChromiumRuntime.Progress) -> Unit) {
            starts++
            throw reason
        }

        override fun newClient(): KCEFClient = error("never reached: Chromium did not start")

        override fun disposeClient(client: KCEFClient) = Unit
    }

    private class Events : WebViewClient() {
        val queue = LinkedBlockingQueue<String>()

        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            queue.add("error[${Thread.currentThread().name}]: $description @ $failingUrl")
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            queue.add("finished: $url")
        }

        fun next(): String = assertNotNull(queue.poll(5, TimeUnit.SECONDS), "the WebView never answered")
    }

    /** Runs [block] on the looper, as `Handler(Looper.getMainLooper()).post { }` does in an extension. */
    private fun <T> onLooper(block: () -> T): T {
        val result = CompletableFuture<T>()
        handler.post {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun aWebViewWhoseChromiumCannotStartReportsALoadErrorOnItsOwnThread() {
        val starter = FailingStarter(ChromiumRuntime.PermanentFailure("no X display"))
        enableWith(starter)
        val events = Events()

        val webView =
            onLooper {
                WebView(ContextWrapper(null)).apply {
                    webViewClient = events
                    loadUrl("https://example.org/watch/1")
                }
            }

        assertEquals("error[test-webview-looper]: no X display @ https://example.org/watch/1", events.next())
        assertEquals("finished: https://example.org/watch/1", events.next())
        assertEquals(1, starter.starts)

        onLooper { webView.destroy() }
    }

    @Test
    fun javascriptAskedForBeforeAPageThatNeverComesIsAnsweredWithNull() {
        enableWith(FailingStarter(WebViewUnavailableException("download failed")))
        val result = CompletableFuture<String?>()
        val events = Events()

        onLooper {
            WebView(ContextWrapper(null)).apply {
                webViewClient = events
                loadUrl("https://example.org/")
                evaluateJavascript("document.title", ValueCallback { result.complete(it) })
            }
        }

        assertTrue(events.next().startsWith("error"))
        // Answered at once rather than left to the extension's own timeout.
        assertNull(result.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun anExtensionThatEvaluatesOnEveryPageFinishedDoesNotLoopOnAFailure() {
        val starter = FailingStarter(ChromiumRuntime.PermanentFailure("no X display"))
        enableWith(starter)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val client =
            object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    errors.incrementAndGet()
                }

                // MKissa's StreamResolver does exactly this.
                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    view.evaluateJavascript("start()", null)
                }
            }

        onLooper {
            WebView(ContextWrapper(null)).apply {
                webViewClient = client
                loadUrl("https://example.org/")
            }
        }

        Thread.sleep(1_000)
        assertEquals(1, errors.get())
    }

    @Test
    fun withTheSwitchOffAWebViewFailsAsUpstreamsDoesWithoutStartingAnything() {
        HeadlessWebView.configOverride = WebViewConfig(enabled = false, dir = File("unused"), maxPages = 1, pageLifetimeMs = 60_000)
        val starter = FailingStarter(IllegalStateException("must not start"))
        HeadlessWebView.runtimeOverride = ChromiumRuntime(1, 60_000, starter)
        val events = Events()

        onLooper {
            WebView(ContextWrapper(null)).apply {
                webViewClient = events
                loadUrl("https://example.org/")
            }
        }

        assertTrue(events.next().contains("Desktop WebView is unavailable"))
        assertEquals(0, starter.starts)
    }
}
