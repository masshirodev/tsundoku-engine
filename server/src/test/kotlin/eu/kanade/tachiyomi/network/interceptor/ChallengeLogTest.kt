package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import kotlin.test.Test
import kotlin.test.assertTrue

class ChallengeLogTest {
    @Test
    fun `a host is solving while its check runs, then solved, and a failed solve isn't`() {
        ChallengeLog.clear()
        var during = ""
        ChallengeLog.solving("example.org") {
            during = ChallengeLog.json()
            "solution"
        }
        assertTrue(during.contains("\"solving\":{\"example.org\":"), during)
        val after = ChallengeLog.json()
        assertTrue(after.startsWith("{\"solving\":{},\"solved\":{\"example.org\":"), after)

        ChallengeLog.clear()
        ChallengeLog.solving<String?>("example.net") { null }
        assertTrue(ChallengeLog.json() == "{\"solving\":{},\"solved\":{}}", ChallengeLog.json())
    }
}
