package eu.kanade.tachiyomi.network.interceptor

import kotlin.test.Test
import kotlin.test.assertTrue

class ChallengeLogTest {
    @Test
    fun `a host is solving while its check runs, then solved, and a failed solve isn't`() {
        ChallengeLog.clear()
        var during = ""
        ChallengeLog.solving("toonily.com") {
            during = ChallengeLog.json()
            "solution"
        }
        assertTrue(during.contains("\"solving\":{\"toonily.com\":"), during)
        val after = ChallengeLog.json()
        assertTrue(after.startsWith("{\"solving\":{},\"solved\":{\"toonily.com\":"), after)

        ChallengeLog.clear()
        ChallengeLog.solving<String?>("asuracomic.net") { null }
        assertTrue(ChallengeLog.json() == "{\"solving\":{},\"solved\":{}}", ChallengeLog.json())
    }
}
