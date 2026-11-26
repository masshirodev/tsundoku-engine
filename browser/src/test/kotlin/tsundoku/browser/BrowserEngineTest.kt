package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What JavaScript sees of the engine: two methods that answer text and never
 * throw. Started for real (the Android shim, Injekt, the routes), with no
 * network: nothing here reaches a source.
 */
class BrowserEngineTest {
    companion object {
        private val dataDir = Files.createTempDirectory("tsundoku-browser-test").toFile().apply { deleteOnExit() }
    }

    private fun started(): String = BrowserEngine.start(dataDir.absolutePath, "https://app.example.org/engine")

    private fun call(
        head: String,
        body: String = "",
    ): Frames.Answer = Frames.parseAnswer(BrowserEngine.handle(head, body))

    @BeforeTest
    fun start() {
        val answer = started()
        assertTrue(answer.startsWith("""{"ok":true,"ms":"""), answer)
    }

    @Test
    fun `start answers ok again once started`() {
        assertTrue(started().startsWith("""{"ok":true"""))
    }

    @Test
    fun `a route answers through the frame as it would over HTTP`() {
        val root = call("GET\n/\n")
        assertEquals(200, root.status)
        assertEquals("mextensionserver Server Running", String(root.body))

        val capabilities = call("GET\nhttps://app.example.org/engine/capabilities\nAuthorization: Bearer ignored\n")
        assertEquals(200, capabilities.status)
        assertTrue(capabilities.headers.any { it.first.equals("Content-Type", true) && it.second.startsWith("application/json") })
        assertTrue(String(capabilities.body).contains(""""webview":false"""))
    }

    @Test
    fun `an unknown route is a 404, and there is no stop to call`() {
        assertEquals(404, call("GET\n/nowhere\n").status)
        assertEquals(404, call("GET\n/stop\n").status)
    }

    @Test
    fun `a dalvik call that fails is the engine's own error answer`() {
        val body = Base64.getEncoder().encodeToString("""{"method":"getPopularManga","extensionId":"${"0".repeat(64)}"}""".toByteArray())
        val answer = call("POST\n/dalvik\nContent-Type: application/json\n", body)

        // The engine has no such extension: 409, resend the APK, as over HTTP.
        assertEquals(409, answer.status)
        assertTrue(String(answer.body).contains("resend the APK"), String(answer.body))
    }

    @Test
    fun `a dalvik call with no body is an error answer, not a throw`() {
        val answer = call("POST\n/dalvik\n")

        assertEquals(500, answer.status)
        assertTrue(String(answer.body).contains("No JSON body"))
    }

    @Test
    fun `a head that isn't one is a 500 with the stack, never a throw`() {
        val answer = call("")

        assertEquals(500, answer.status)
        assertTrue(String(answer.body).contains("FrameException"), String(answer.body))
        assertEquals(500, call("BREW\n/coffee\n").status)
        assertEquals(500, call("GET\n/\n", "!!not base64!!").status)
    }

    @Test
    fun `nothing is answered before start`() {
        BrowserEngine.resetForTest()
        try {
            assertEquals(503, call("GET\n/\n").status)
        } finally {
            started()
        }
    }

    @Test
    fun `a chunked response comes back whole`() {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "video/mp2t", ByteArrayInputStream(payload))
        response.addHeader("Accept-Ranges", "bytes")
        val wire = java.io.ByteArrayOutputStream()
        NanoHTTPD.Response::class.java
            .getDeclaredMethod("send", java.io.OutputStream::class.java)
            .apply { isAccessible = true }
            .invoke(response, wire)

        val answer = Frames.parseAnswer(BrowserEngine.Wire.toFrame(wire.toByteArray()))

        assertEquals(200, answer.status)
        assertContentEquals(payload, answer.body)
        assertTrue(answer.headers.contains("Accept-Ranges" to "bytes"))
        assertTrue(answer.headers.none { it.first.equals("Transfer-Encoding", true) })
    }
}
