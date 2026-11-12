package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Why a WebView could not get a browser. Reaches the extension as a load error, never as a throw. */
class WebViewUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The one headless Chromium the engine runs, and the pages open in it.
 *
 * Chromium is started the first time a page is asked for (or [prepare] is
 * called), never at engine start, so an install whose extensions never use a
 * WebView never downloads it. Pages are capped at [maxPages]: a page asked for
 * beyond the cap waits for one to close. A page is closed when its WebView is
 * destroyed, and at the latest [pageLifetimeMs] after it opened, so an
 * extension that forgets `destroy()` (or times out before calling it) cannot
 * keep a renderer alive.
 *
 * Every state change happens on [executor], a single thread, so nothing here
 * needs a lock. Starting Chromium (which may download 170-190 MB) runs on a
 * thread of its own, so closing pages and reading [status] stay immediate.
 *
 * Generic over the client type so the lifecycle is testable without Chromium.
 */
class ChromiumRuntime<C : Any>(
    private val maxPages: Int,
    private val pageLifetimeMs: Long,
    private val starter: Starter<C>,
    private val retryAfterMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val startThread: (Runnable) -> Thread = { Thread(it, "webview-start").apply { isDaemon = true } },
) {
    interface Starter<C> {
        /**
         * Makes Chromium usable in this process: downloads it if it isn't
         * there, proves it starts, and starts it. Throws with a reason a person
         * can act on; a [PermanentFailure] is not retried until the engine restarts.
         */
        fun start(progress: (Progress) -> Unit)

        fun newClient(): C

        fun disposeClient(client: C)
    }

    class PermanentFailure(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    data class Progress(
        val phase: String,
        val percent: Int? = null,
    )

    data class Status(
        val state: String,
        val phase: String?,
        val percent: Int?,
        val error: String?,
        val openPages: Int,
        val waitingPages: Int,
        val maxPages: Int,
    )

    /** One WebView's claim on a page. [close] is safe from any thread, any number of times. */
    inner class Page internal constructor(
        internal val onGranted: (C) -> Unit,
        internal val onFailed: (Throwable) -> Unit,
        internal val onExpired: () -> Unit,
    ) {
        internal var client: C? = null
        internal var expiry: ScheduledFuture<*>? = null
        internal var closed = false

        fun close() {
            executor.execute { closePage(this) }
        }
    }

    private sealed class State {
        object Idle : State()

        class Starting(
            val progress: Progress,
        ) : State()

        object Ready : State()

        class Failed(
            val reason: Throwable,
            val at: Long,
            val permanent: Boolean,
        ) : State()
    }

    private val logger = KotlinLogging.logger {}
    private var state: State = State.Idle
    private val waiting = ArrayDeque<Page>()
    private val open = mutableSetOf<Page>()

    @Volatile
    private var snapshot = snapshotOf(state)

    /** Starts Chromium now if it isn't started, without opening a page: a WebView extension was loaded. */
    fun prepare() {
        executor.execute { ensureStarted() }
    }

    fun open(
        onGranted: (C) -> Unit,
        onFailed: (Throwable) -> Unit,
        onExpired: () -> Unit = {},
    ): Page {
        val page = Page(onGranted, onFailed, onExpired)
        executor.execute {
            waiting.add(page)
            ensureStarted()
            pump()
        }
        return page
    }

    fun status(): Status = snapshot

    private fun ensureStarted() {
        when (val current = state) {
            is State.Ready, is State.Starting -> return
            is State.Failed ->
                if (current.permanent || clock() - current.at < retryAfterMs) {
                    failWaiting(current.reason)
                    return
                }
            State.Idle -> Unit
        }
        setState(State.Starting(Progress("starting")))
        startThread(
            Runnable {
                val outcome =
                    runCatching {
                        starter.start { progress -> executor.execute { onProgress(progress) } }
                    }
                executor.execute { onStarted(outcome) }
            },
        ).start()
    }

    private fun onProgress(progress: Progress) {
        if (state is State.Starting) setState(State.Starting(progress))
    }

    private fun onStarted(outcome: Result<Unit>) {
        outcome
            .onSuccess {
                logger.info { "Headless Chromium is ready" }
                setState(State.Ready)
                pump()
            }.onFailure { error ->
                val reason = error as? WebViewUnavailableException ?: WebViewUnavailableException(describe(error), error)
                logger.warn(error) { "Headless Chromium could not start: ${reason.message}" }
                setState(State.Failed(reason, clock(), error is PermanentFailure))
                failWaiting(reason)
            }
    }

    private fun failWaiting(reason: Throwable) {
        while (waiting.isNotEmpty()) {
            val page = waiting.poll()
            page.closed = true
            notify { page.onFailed(reason) }
        }
        publish()
    }

    private fun pump() {
        while (state is State.Ready && open.size < maxPages && waiting.isNotEmpty()) {
            val page = waiting.poll()
            val client =
                try {
                    starter.newClient()
                } catch (error: Throwable) {
                    page.closed = true
                    notify { page.onFailed(WebViewUnavailableException("Chromium could not open a page: ${describe(error)}", error)) }
                    continue
                }
            page.client = client
            open.add(page)
            page.expiry =
                executor.schedule(
                    { expire(page) },
                    pageLifetimeMs,
                    TimeUnit.MILLISECONDS,
                )
            notify { page.onGranted(client) }
        }
        publish()
    }

    private fun expire(page: Page) {
        if (page.closed) return
        logger.warn { "A WebView page was still open after ${pageLifetimeMs / 1000} s; closing it (its extension never destroyed it)" }
        notify { page.onExpired() }
        closePage(page)
    }

    private fun closePage(page: Page) {
        if (page.closed && page.client == null) {
            waiting.remove(page)
            return
        }
        page.closed = true
        if (waiting.remove(page)) {
            publish()
            return
        }
        page.expiry?.cancel(false)
        page.expiry = null
        page.client?.let { client ->
            page.client = null
            open.remove(page)
            try {
                starter.disposeClient(client)
            } catch (error: Throwable) {
                logger.warn(error) { "Closing a WebView page failed" }
            }
        }
        pump()
    }

    /** A callback into extension code must never take the runtime thread down with it. */
    private inline fun notify(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            logger.warn(error) { "A WebView callback threw" }
        }
    }

    private fun setState(next: State) {
        state = next
        publish()
    }

    private fun publish() {
        snapshot = snapshotOf(state)
    }

    private fun snapshotOf(state: State): Status =
        Status(
            state =
                when (state) {
                    State.Idle -> "idle"
                    is State.Starting -> "starting"
                    State.Ready -> "ready"
                    is State.Failed -> "failed"
                },
            phase = (state as? State.Starting)?.progress?.phase,
            percent = (state as? State.Starting)?.progress?.percent,
            error = (state as? State.Failed)?.reason?.message,
            openPages = open.size,
            waitingPages = waiting.size,
            maxPages = maxPages,
        )

    companion object {
        private fun describe(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "webview-runtime").apply { isDaemon = true }
            }
    }
}
