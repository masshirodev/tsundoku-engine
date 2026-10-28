package eu.kanade.tachiyomi.network.interceptor

import java.util.concurrent.ConcurrentHashMap

/**
 * Which hosts' browser checks are being solved now, and when each was last
 * solved, so a client waiting on a slow first call can say why it's slow
 * (tsundoku's BROWSE: "a site's check is named at once"). In memory: after a
 * restart the next challenge is the first again, which is also what the
 * site's cookie says.
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
