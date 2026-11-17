package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The headless WebView's lifecycle (doc/FORK.md, ENGINE_WEBVIEW): Chromium
 * started only when asked for, pages capped and always given back, and a
 * start that fails reaching the WebView as an error rather than a throw.
 */
class ChromiumRuntimeTest {
    private class FakeStarter(
        var failWith: Throwable? = null,
        val gate: CountDownLatch = CountDownLatch(0),
    ) : ChromiumRuntime.Starter<String> {
        val starts = AtomicInteger()
        val clients = AtomicInteger()
        val disposed = CopyOnWriteArrayList<String>()
        var failNewClient = false

        override fun start(progress: (ChromiumRuntime.Progress) -> Unit) {
            starts.incrementAndGet()
            progress(ChromiumRuntime.Progress("downloading", 40))
            gate.await(5, TimeUnit.SECONDS)
            failWith?.let { throw it }
        }

        override fun newClient(): String {
            if (failNewClient) throw IllegalStateException("no client")
            return "client-${clients.incrementAndGet()}"
        }

        override fun disposeClient(client: String) {
            disposed.add(client)
        }
    }

    /** What one page was told, in order. */
    private class Recorder {
        val events = LinkedBlockingQueue<Any>()

        fun granted(client: String) = events.add("granted:$client")

        fun failed(error: Throwable) = events.add(error)

        fun expired() = events.add("expired")

        fun next(): Any = assertNotNull(events.poll(5, TimeUnit.SECONDS), "nothing happened in 5 s")

        fun nothingFor(millis: Long) = assertNull(events.poll(millis, TimeUnit.MILLISECONDS))
    }

    private fun runtime(
        starter: FakeStarter,
        maxPages: Int = 2,
        lifetimeMs: Long = 60_000,
        clock: () -> Long = System::currentTimeMillis,
    ) = ChromiumRuntime(maxPages, lifetimeMs, starter, retryAfterMs = 60_000, clock = clock)

    private fun ChromiumRuntime<String>.page(recorder: Recorder) = open(recorder::granted, recorder::failed, recorder::expired)

    private fun eventually(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met in 5 s")
            Thread.sleep(10)
        }
    }

    @Test
    fun nothingStartsUntilAPageIsAskedFor() {
        val starter = FakeStarter()
        val runtime = runtime(starter)

        Thread.sleep(100)
        assertEquals(0, starter.starts.get())
        assertEquals("idle", runtime.status().state)

        val page = Recorder()
        runtime.page(page)
        assertEquals("granted:client-1", page.next())
        assertEquals(1, starter.starts.get())
        assertEquals("ready", runtime.status().state)
    }

    @Test
    fun prepareStartsChromiumWithoutOpeningAPage() {
        val starter = FakeStarter()
        val runtime = runtime(starter)

        runtime.prepare()

        eventually { runtime.status().state == "ready" }
        assertEquals(0, starter.clients.get())
        assertEquals(1, starter.starts.get())
    }

    @Test
    fun statusShowsTheDownloadWhileChromiumStarts() {
        val gate = CountDownLatch(1)
        val runtime = runtime(FakeStarter(gate = gate))
        val page = Recorder()

        runtime.page(page)

        eventually { runtime.status().phase == "downloading" }
        assertEquals(40, runtime.status().percent)
        assertEquals("starting", runtime.status().state)
        assertEquals(1, runtime.status().waitingPages)
        gate.countDown()
        assertEquals("granted:client-1", page.next())
    }

    @Test
    fun pagesBeyondTheCapWaitForOneToClose() {
        val starter = FakeStarter()
        val runtime = runtime(starter, maxPages = 2)
        val first = Recorder()
        val second = Recorder()
        val third = Recorder()

        val firstPage = runtime.page(first)
        runtime.page(second)
        runtime.page(third)

        assertEquals("granted:client-1", first.next())
        assertEquals("granted:client-2", second.next())
        third.nothingFor(200)
        assertEquals(2, runtime.status().openPages)
        assertEquals(1, runtime.status().waitingPages)

        firstPage.close()

        assertEquals("granted:client-3", third.next())
        eventually { starter.disposed == listOf("client-1") }
    }

    @Test
    fun aPageClosedWhileWaitingIsNeverGranted() {
        val starter = FakeStarter()
        val runtime = runtime(starter, maxPages = 1)
        val first = Recorder()
        val second = Recorder()

        val firstPage = runtime.page(first)
        val secondPage = runtime.page(second)
        assertEquals("granted:client-1", first.next())
        secondPage.close()
        firstPage.close()

        second.nothingFor(300)
        assertEquals(1, starter.clients.get())
        eventually { runtime.status().openPages == 0 && runtime.status().waitingPages == 0 }
    }

    @Test
    fun aPageItsExtensionNeverDestroysIsClosedAfterItsLifetime() {
        val starter = FakeStarter()
        val runtime = runtime(starter, maxPages = 1, lifetimeMs = 150)
        val leaked = Recorder()
        val next = Recorder()

        runtime.page(leaked)
        assertEquals("granted:client-1", leaked.next())
        runtime.page(next)

        assertEquals("expired", leaked.next())
        assertEquals("granted:client-2", next.next())
        assertTrue("client-1" in starter.disposed)
    }

    @Test
    fun closingTwiceIsHarmless() {
        val starter = FakeStarter()
        val runtime = runtime(starter)
        val page = Recorder()

        val ticket = runtime.page(page)
        page.next()
        ticket.close()
        ticket.close()

        eventually { runtime.status().openPages == 0 }
        Thread.sleep(100)
        assertEquals(listOf("client-1"), starter.disposed.toList())
    }

    @Test
    fun aFailedStartFailsEveryWaitingPageWithItsReason() {
        val gate = CountDownLatch(1)
        val starter = FakeStarter(failWith = WebViewUnavailableException("no network"), gate = gate)
        val runtime = runtime(starter)
        val first = Recorder()
        val second = Recorder()

        runtime.page(first)
        runtime.page(second)
        gate.countDown()

        assertEquals("no network", assertIs<WebViewUnavailableException>(first.next()).message)
        assertEquals("no network", assertIs<WebViewUnavailableException>(second.next()).message)
        assertEquals("failed", runtime.status().state)
        assertEquals("no network", runtime.status().error)
    }

    @Test
    fun anUnexpectedStartErrorStillReachesThePageAsUnavailable() {
        val runtime = runtime(FakeStarter(failWith = StackOverflowError()))
        val page = Recorder()

        runtime.page(page)

        assertIs<WebViewUnavailableException>(page.next())
    }

    @Test
    fun aFailedStartIsRetriedOnlyAfterItsBackoff() {
        val now = AtomicLong(1_000_000)
        val starter = FakeStarter(failWith = WebViewUnavailableException("no network"))
        val runtime = runtime(starter, clock = now::get)

        val first = Recorder()
        runtime.page(first)
        assertIs<WebViewUnavailableException>(first.next())

        val soon = Recorder()
        runtime.page(soon)
        assertIs<WebViewUnavailableException>(soon.next())
        assertEquals(1, starter.starts.get())

        now.addAndGet(61_000)
        starter.failWith = null
        val later = Recorder()
        runtime.page(later)
        assertEquals("granted:client-1", later.next())
        assertEquals(2, starter.starts.get())
    }

    @Test
    fun aPermanentFailureIsNeverRetried() {
        val now = AtomicLong(0)
        val starter = FakeStarter(failWith = ChromiumRuntime.PermanentFailure("no display"))
        val runtime = runtime(starter, clock = now::get)

        val first = Recorder()
        runtime.page(first)
        assertEquals("no display", assertIs<WebViewUnavailableException>(first.next()).message)

        now.addAndGet(3_600_000)
        starter.failWith = null
        val later = Recorder()
        runtime.page(later)
        assertIs<WebViewUnavailableException>(later.next())
        assertEquals(1, starter.starts.get())
    }

    @Test
    fun aClientThatCannotBeMadeFailsThatPageOnly() {
        val starter = FakeStarter()
        val runtime = runtime(starter)
        runtime.prepare()
        eventually { runtime.status().state == "ready" }

        starter.failNewClient = true
        val broken = Recorder()
        runtime.page(broken)
        assertIs<WebViewUnavailableException>(broken.next())

        starter.failNewClient = false
        val fine = Recorder()
        runtime.page(fine)
        assertEquals("granted:client-1", fine.next())
    }

    @Test
    fun aCallbackThatThrowsDoesNotStopTheRuntime() {
        val starter = FakeStarter()
        val runtime = runtime(starter, maxPages = 1)
        val thrown = CountDownLatch(1)

        val bad =
            runtime.open(
                onGranted = {
                    thrown.countDown()
                    throw IllegalStateException("extension code")
                },
                onFailed = {},
            )
        assertTrue(thrown.await(5, TimeUnit.SECONDS))
        bad.close()

        val next = Recorder()
        runtime.page(next)
        assertEquals("granted:client-2", next.next())
    }
}
