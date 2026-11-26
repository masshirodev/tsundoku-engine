package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The browser target's QuickJs, as an extension calls it, with the page's
 * script runner stood in for: results translated exactly as AndroidCompat's
 * QuickJs translates them (its QuickJsTest), so an extension gets the same
 * values in a tab.
 */
class BrowserScriptsTest {
    private val scripts = mutableListOf<String>()

    private fun answering(answer: String) {
        BrowserScripts.evaluator = { script ->
            scripts += script
            answer
        }
    }

    @AfterTest
    fun reset() {
        BrowserScripts.evaluator = null
    }

    @Test
    fun `the script goes to the page as it is, with no wrapping`() {
        answering("""{"value":3}""")

        QuickJs.create().use { assertEquals(3, it.evaluate("var a = 1; a + 2")) }
        assertEquals(listOf("var a = 1; a + 2"), scripts)
    }

    @Test
    fun `values come back as AndroidCompat's QuickJs returns them`() {
        answering("""{"value":"abc"}""")
        assertEquals("abc", QuickJs.create().evaluate("'a' + 'bc'"))
        answering("""{"value":1.5}""")
        assertEquals(1.5, QuickJs.create().evaluate("3 / 2"))
        answering("""{"value":[1,2]}""")
        assertContentEquals(doubleArrayOf(1.0, 2.0), QuickJs.create().evaluate("[1, 2]") as DoubleArray)
        answering("""{"value":["a","b"]}""")
        assertEquals(listOf("a", "b"), (QuickJs.create().evaluate("['a', 'b']") as Array<*>).toList())
        answering("""{"value":true}""")
        assertEquals(true, QuickJs.create().evaluate("true"))
        answering("""{"value":{"k":"v"}}""")
        assertEquals(mapOf("k" to "v"), QuickJs.create().evaluate("({k: 'v'})"))
    }

    @Test
    fun `undefined and null are null`() {
        answering("{}")
        assertNull(QuickJs.create().evaluate("undefined"))
        answering("""{"value":null}""")
        assertNull(QuickJs.create().evaluate("null"))
    }

    @Test
    fun `a script that threw is a map with its error, as on a desktop`() {
        answering("""{"error":"x is not defined"}""")

        assertEquals(mapOf("error" to "x is not defined"), QuickJs.create().evaluate("x"))
    }

    @Test
    fun `compile and execute run the same script`() {
        answering("""{"value":7}""")

        QuickJs.create().use { js -> assertEquals(7, js.execute(js.compile("3 + 4", "f.js"))) }
        assertEquals(listOf("3 + 4"), scripts)
    }

    @Test
    fun `a bound Java object is refused, not silently left out`() {
        answering("""{"value":1}""")

        val js = QuickJs.create()
        js.set("helper", Any::class.java, Any())
        assertFailsWith<QuickJsException> { js.evaluate("helper.x()") }
    }

    @Test
    fun `an answer that is not JSON is a QuickJsException`() {
        answering("not json")

        assertFailsWith<QuickJsException> { QuickJs.create().evaluate("1") }
    }
}
