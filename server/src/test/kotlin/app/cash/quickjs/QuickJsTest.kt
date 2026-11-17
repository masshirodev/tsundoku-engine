package app.cash.quickjs

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class QuickJsTest {
    // The result is printed behind a 14-character "QUICJS_RESULT:" marker; it
    // was read from index 7, so every value arrived as "RESULT:..." and failed
    // to parse, which broke every extension that evaluates JavaScript.
    @Test
    fun `returns what the script evaluates to`() {
        QuickJs.create().use { js ->
            assertEquals(3, js.evaluate("1 + 2"))
        }
        QuickJs.create().use { js ->
            assertEquals("abc", js.evaluate("'a' + 'bc'"))
        }
        QuickJs.create().use { js ->
            assertEquals(1.5, js.evaluate("3 / 2"))
        }
        // Lists of numbers come back as double[], as translateType makes them.
        QuickJs.create().use { js ->
            assertContentEquals(doubleArrayOf(1.0, 2.0), js.evaluate("[1, 2]") as DoubleArray)
        }
    }

    // The runner's stdout accumulates, and the first marker was taken, so a
    // reused instance answered every call with its first result.
    @Test
    fun `a reused instance returns each call's own result`() {
        QuickJs.create().use { js ->
            assertEquals(3, js.evaluate("1 + 2"))
            assertEquals("abc", js.evaluate("'a' + 'bc'"))
            assertEquals(10, js.evaluate("5 * 2"))
        }
    }
}
