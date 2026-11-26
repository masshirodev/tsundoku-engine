package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramesTest {
    @Test
    fun `a request head goes out and comes back the same`() {
        val head = Frames.head("POST", "https://example.org/a?b=c", listOf("Content-Type" to "application/json", "X-A" to "1: 2"))

        assertEquals("POST\nhttps://example.org/a?b=c\nContent-Type: application/json\nX-A: 1: 2\n", head)
        val parsed = Frames.parseHead(head)
        assertEquals("POST", parsed.method)
        assertEquals("https://example.org/a?b=c", parsed.url)
        assertEquals(listOf("Content-Type" to "application/json", "X-A" to "1: 2"), parsed.headers)
    }

    @Test
    fun `a head with no headers and no trailing line break is read`() {
        val parsed = Frames.parseHead("GET\n/capabilities")

        assertEquals("GET", parsed.method)
        assertEquals("/capabilities", parsed.url)
        assertEquals(emptyList(), parsed.headers)
    }

    @Test
    fun `a head without a URL is refused`() {
        assertFailsWith<Frames.FrameException> { Frames.parseHead("GET") }
        assertFailsWith<Frames.FrameException> { Frames.parseHead("") }
    }

    @Test
    fun `a line break in a header value can't break the frame`() {
        val head = Frames.head("GET", "https://example.org/", listOf("X-Bad" to "a\r\nX-Injected: b"))

        assertEquals(listOf("X-Bad" to "a X-Injected: b"), Frames.parseHead(head).headers)
    }

    @Test
    fun `an answer goes out and comes back the same, bytes included`() {
        val body = ByteArray(256) { it.toByte() }
        val frame = Frames.answer(206, listOf("Content-Type" to "image/png", "Content-Range" to "bytes 0-255/1000"), body)

        val answer = Frames.parseAnswer(frame)
        assertEquals(206, answer.status)
        assertEquals(listOf("Content-Type" to "image/png", "Content-Range" to "bytes 0-255/1000"), answer.headers)
        assertContentEquals(body, answer.body)
    }

    @Test
    fun `an answer with no headers or no body is read`() {
        assertEquals(0, Frames.parseAnswer("204\n\n").body.size)
        assertEquals(0, Frames.parseAnswer("204").body.size)
        assertEquals(emptyList(), Frames.parseAnswer("200\n\naGk=").headers)
        assertContentEquals("hi".toByteArray(), Frames.parseAnswer("200\n\naGk=").body)
    }

    @Test
    fun `a native's ERR is a failure with its message`() {
        val error = assertFailsWith<Frames.FrameException> { Frames.parseAnswer("ERR the fetcher is not installed") }

        assertEquals("the fetcher is not installed", error.message)
    }

    @Test
    fun `what isn't an answer is refused, not misread`() {
        assertFailsWith<Frames.FrameException> { Frames.parseAnswer(null) }
        assertFailsWith<Frames.FrameException> { Frames.parseAnswer("<html>") }
        assertFailsWith<Frames.FrameException> { Frames.parseAnswer("999\n\n") }
        assertFailsWith<Frames.FrameException> { Frames.parseAnswer("200\n\n!!not base64!!") }
    }

    @Test
    fun `an empty body is none`() {
        assertEquals(null, Frames.decodeBody(""))
        assertEquals(null, Frames.decodeBody(null))
        assertContentEquals("hi".toByteArray(), Frames.decodeBody("aGk="))
    }
}
