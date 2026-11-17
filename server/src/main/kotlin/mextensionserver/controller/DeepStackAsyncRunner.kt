package mextensionserver.controller

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * NanoHTTPD's DefaultAsyncRunner (a thread per request), with threads whose
 * stack is deep enough for code written for Android.
 *
 * Extensions run on the request thread. Android's java.util.regex is ICU's
 * and doesn't recurse; the JVM's recurses once per repetition, so a pattern
 * an extension runs over a whole page overflows the JVM's default 1 MB stack:
 * a source's extension died with StackOverflowError in Pattern$Loop on
 * its first list, and the client saw the connection close.
 */
internal class DeepStackAsyncRunner : NanoHTTPD.AsyncRunner {
    private val running = Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())
    private val requests = AtomicLong()

    override fun closeAll() {
        synchronized(running) { running.toList() }.forEach { it.close() }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running.add(clientHandler)
        requestThread(clientHandler, "NanoHttpd Request Processor (#${requests.incrementAndGet()})").start()
    }

    companion object {
        /**
         * Reserved, not committed: a thread only uses what it touches, so a
         * deep limit costs address space, which a 64-bit JVM has plenty of.
         */
        const val STACK_BYTES = 64L * 1024 * 1024

        internal fun requestThread(
            target: Runnable,
            name: String,
        ): Thread =
            Thread(null, target, name, STACK_BYTES).apply {
                isDaemon = true
            }
    }
}
