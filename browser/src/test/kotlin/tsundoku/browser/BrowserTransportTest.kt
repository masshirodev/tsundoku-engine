package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A request made with an ordinary OkHttp client ends in [BrowserTransport]:
 * the browser target's ConnectInterceptor is ahead of OkHttp's on this
 * classpath, as it is in the slim jar. No network: the native is a stand-in.
 */
class BrowserTransportTest {
    private val client = OkHttpClient()
    private val sent = mutableListOf<Pair<String, String>>()

    private fun answering(frame: String) {
        BrowserTransport.fetcher = { head, body ->
            sent += head to body
            frame
        }
    }

    @AfterTest
    fun reset() {
        BrowserTransport.fetcher = null
    }

    @Test
    fun `a call goes out as a frame, without Accept-Encoding, and comes back as a response`() {
        answering(
            Frames.answer(200, listOf("Content-Type" to "text/html; charset=utf-8", "Set-Cookie" to "a=b"), "<p>hi</p>".toByteArray()),
        )

        client
            .newCall(
                Request
                    .Builder()
                    .url("https://example.org/list?page=2")
                    .header("Referer", "https://example.org/")
                    .build(),
            ).execute()
            .use { response ->
                assertEquals(200, response.code)
                assertEquals("<p>hi</p>", response.body.string())
                assertEquals("a=b", response.header("Set-Cookie"))
                // OkHttp's cache keeps only https responses that carry a handshake.
                assertTrue(response.handshake != null)
            }
        val (head, body) = sent.single()
        val parsed = Frames.parseHead(head)
        assertEquals("GET", parsed.method)
        assertEquals("https://example.org/list?page=2", parsed.url)
        assertTrue(parsed.headers.contains("Referer" to "https://example.org/"))
        assertTrue(parsed.headers.none { it.first.equals("Accept-Encoding", ignoreCase = true) })
        assertEquals("", body)
    }

    @Test
    fun `a body goes as base64 and its type as a header`() {
        answering("201\n\n")

        val json = """{"q":"x"}"""
        client
            .newCall(
                Request
                    .Builder()
                    .url("https://example.org/api")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
            .close()

        val (head, body) = sent.single()
        val parsed = Frames.parseHead(head)
        assertEquals("POST", parsed.method)
        assertTrue(parsed.headers.any { it.first.equals("Content-Type", ignoreCase = true) && it.second.startsWith("application/json") })
        assertContentEquals(json.toByteArray(), Base64.getDecoder().decode(body))
    }

    @Test
    fun `the encoding and length the browser undid are not kept`() {
        answering(Frames.answer(200, listOf("Content-Encoding" to "gzip", "Content-Length" to "999"), "plain".toByteArray()))

        client.newCall(Request.Builder().url("https://example.org/").build()).execute().use { response ->
            assertNull(response.header("Content-Encoding"))
            assertEquals("plain", response.body.string())
        }
    }

    @Test
    fun `a redirect the browser followed is the redirect OkHttp sees, with one fetch`() {
        answering(Frames.answer(200, listOf(BrowserTransport.FINAL_URL_HEADER to "https://example.org/moved"), "here".toByteArray()))

        client.newCall(Request.Builder().url("https://example.org/old").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("https://example.org/moved", response.request.url.toString())
            assertEquals(302, response.priorResponse?.code)
            assertEquals("here", response.body.string())
            assertNull(response.header(BrowserTransport.FINAL_URL_HEADER))
        }
        assertEquals(1, sent.size)
    }

    @Test
    fun `with redirects off, an extension reads the Location it asked for`() {
        answering(Frames.answer(200, listOf(BrowserTransport.FINAL_URL_HEADER to "https://example.org/moved"), ByteArray(0)))

        client
            .newBuilder()
            .followRedirects(
                false,
            ).build()
            .newCall(Request.Builder().url("https://example.org/old").build())
            .execute()
            .use { response ->
                assertEquals(302, response.code)
                assertEquals("https://example.org/moved", response.header("Location"))
            }
    }

    @Test
    fun `the final URL the native repeats when nothing redirected changes nothing`() {
        answering(Frames.answer(200, listOf(BrowserTransport.FINAL_URL_HEADER to "https://example.org/same"), "x".toByteArray()))

        client.newCall(Request.Builder().url("https://example.org/same").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(response.priorResponse)
        }
    }

    @Test
    fun `a native that could not fetch is an IOException, as a failed socket is`() {
        answering("ERR blocked by the fetcher")

        val error =
            assertFailsWith<IOException> {
                client.newCall(Request.Builder().url("https://example.org/").build()).execute()
            }
        assertEquals("blocked by the fetcher", error.message)
    }

    @Test
    fun `an answer that isn't a frame is an IOException, not a crash`() {
        answering("<html>not a frame</html>")

        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("https://example.org/").build()).execute()
        }
    }
}
