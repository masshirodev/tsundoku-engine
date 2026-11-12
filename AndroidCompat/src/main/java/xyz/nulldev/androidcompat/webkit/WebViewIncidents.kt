package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/**
 * What went wrong in extensions' WebViews: no WebView to be had, a page that
 * couldn't load, an extension's own WebView callback that threw.
 *
 * None of it reaches the request thread by itself. An extension drives its
 * WebView on the main looper and waits on a latch; when the WebView fails, the
 * extension sees nothing and often answers with an empty list, and the client
 * took "no videos" for an answer. So each incident is kept with the class
 * loader of the extension it happened to (its WebViewClient's), and a call to
 * that extension that comes back empty after one is turned into an error that
 * says what happened ([firstSince]).
 */
object WebViewIncidents {
    data class Incident(
        val at: Long,
        val owner: ClassLoader?,
        val message: String,
    )

    private const val KEPT = 64
    private val incidents = ArrayDeque<Incident>()

    /** A point to ask [firstSince] from: incidents are ordered by this clock. */
    fun now(): Long = System.nanoTime()

    fun record(
        owner: ClassLoader?,
        message: String,
    ) {
        synchronized(incidents) {
            incidents.addLast(Incident(now(), owner, message))
            while (incidents.size > KEPT) incidents.removeFirst()
        }
    }

    /** The first incident in [owner]'s WebViews since [since], if any. */
    fun firstSince(
        since: Long,
        owner: ClassLoader?,
    ): Incident? =
        synchronized(incidents) {
            incidents.firstOrNull { it.at >= since && owner != null && it.owner === owner }
        }

    /** Tests only. */
    fun clear() {
        synchronized(incidents) { incidents.clear() }
    }
}
