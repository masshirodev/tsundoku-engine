package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.util.concurrent.ConcurrentHashMap

/**
 * Which hosts' browser checks are being solved now, and when each was last
 * solved, so a client waiting on a slow first call can say why it's slow,
 * and name the check at once. In memory: after a restart the next challenge
 * is the first again, which is also what the site's cookie says.
 */
object ChallengeLog {
    private val solving = ConcurrentHashMap<String, Long>()
    private val solved = ConcurrentHashMap<String, Long>()

    fun <T> solving(
        host: String,
        block: () -> T,
    ): T {
        solving[host] = System.currentTimeMillis()
        try {
            return block().also { if (it != null) solved[host] = System.currentTimeMillis() }
        } finally {
            solving.remove(host)
        }
    }

    /** `{"solving":{"host":since},"solved":{"host":at}}`, times in epoch milliseconds. */
    fun json(): String {
        fun obj(m: Map<String, Long>) =
            m.entries.joinToString(",", "{", "}") { (h, t) -> "\"${h.replace("\\", "\\\\").replace("\"", "\\\"")}\":$t" }
        return """{"solving":${obj(solving)},"solved":${obj(solved)}}"""
    }

    /** For tests. */
    fun clear() {
        solving.clear()
        solved.clear()
    }
}
