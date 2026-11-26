package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * Where every request the engine makes ends, in the browser: OkHttp's
 * `ConnectInterceptor` is replaced by this target's own (first on the
 * classpath, and in place of OkHttp's in the slim jar), which hands the
 * request here instead of opening a socket. Everything above it still runs:
 * the extension's interceptors, the engine's user-agent and Cloudflare
 * interceptors, rate limits, cookies, the cache. Network interceptors and
 * `CallServerInterceptor` never do.
 *
 * The request goes out as a frame ([Frames]) through [fetchNative], which
 * the page implements in JavaScript. On a desktop JVM, for tests and for
 * checking a change without a browser, `-Dtsundoku.browser.transport=jdk`
 * sends it with `HttpURLConnection` instead, the same frames both ways.
 */
object BrowserTransport {
    /** The system property that selects the JDK's own HTTP client instead of the native. */
    const val TRANSPORT_PROPERTY = "tsundoku.browser.transport"

    /**
     * An optional header in the native's answer: the URL the response came
     * from, after the redirects the browser followed on its own (fetch's
     * `response.url`). When it differs from the URL asked for, OkHttp is
     * shown the redirect it would have seen ([execute]); without it, the
     * response says it came from the URL asked for.
     */
    const val FINAL_URL_HEADER = "X-Tsundoku-Final-Url"

    // How long a followed redirect's answer waits for OkHttp to ask for it.
    private const val REPLAY_MS = 30_000L

    // The browser did the TLS and doesn't say how. OkHttp's cache only needs
    // an https response to carry a handshake, so it gets a stand-in.
    private val browserTls =
        Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())

    /** Tests only: answers in place of the native or the JDK. */
    @Volatile
    internal var fetcher: ((head: String, bodyBase64: String) -> String)? = null

    private class Replay(
        val answer: Frames.Answer,
        val at: Long,
    )

    /** Answers already fetched by a redirect the browser followed, by the URL they came from. */
    private val replays = LinkedHashMap<String, Replay>()

    /**
     * Implemented in JavaScript (`Java_tsundoku_browser_BrowserTransport_fetchNative`).
     * Takes the request head and its body as base64 (empty for none); answers
     * `STATUS\nName: value\n...\n\nBASE64BODY`, or `ERR <message>`.
     */
    @JvmStatic
    external fun fetchNative(
        head: String,
        bodyBase64: String,
    ): String?

    /**
     * The browser follows redirects itself, but OkHttp sets each response's
     * request back to the one it sent, and some extensions read where a
     * request ended up (`response.request.url`) or turn redirects off to read
     * `Location`. So when the native says it ended elsewhere, OkHttp gets a
     * 302 to there, and its follow-up is answered with what the browser
     * already fetched, with no second request: OkHttp sees the redirect it
     * would have seen over a socket.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun execute(request: Request): Response {
        val sentAt = System.currentTimeMillis()
        takeReplay(request.url.toString())?.let { return response(request, it, sentAt) }
        val body =
            request.body?.let { requestBody ->
                Buffer().also(requestBody::writeTo).readByteArray()
            }
        val headers = outgoingHeaders(request)
        val head = Frames.head(request.method, request.url.toString(), headers)
        val bodyBase64 = body?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()

        val frame =
            fetcher?.invoke(head, bodyBase64)
                ?: if (System.getProperty(TRANSPORT_PROPERTY) == "jdk") {
                    viaJdk(request.method, request.url.toString(), headers, body)
                } else {
                    fetchNative(head, bodyBase64)
                }
        val answer =
            try {
                Frames.parseAnswer(frame)
            } catch (error: Frames.FrameException) {
                throw IOException(error.message, error)
            }
        val finalUrl = answer.headers.firstOrNull { it.first.equals(FINAL_URL_HEADER, ignoreCase = true) }?.second
        val redirected = finalUrl?.toHttpUrlOrNull()?.takeIf { it != request.url }
        if (redirected != null) {
            putReplay(redirected.toString(), answer)
            return response(request, Frames.Answer(302, listOf("Location" to redirected.toString()), ByteArray(0)), sentAt)
        }
        return response(request, answer, sentAt)
    }

    private fun putReplay(
        url: String,
        answer: Frames.Answer,
    ) = synchronized(replays) {
        val now = System.currentTimeMillis()
        replays.values.removeAll { now - it.at > REPLAY_MS }
        replays[url] = Replay(answer, now)
    }

    private fun takeReplay(url: String): Frames.Answer? =
        synchronized(replays) {
            if (replays.isEmpty()) return null
            replays.remove(url)?.takeIf { System.currentTimeMillis() - it.at <= REPLAY_MS }?.answer
        }

    /**
     * Accept-Encoding stays unset: the browser decompresses on its own and
     * won't let a page choose, and OkHttp's interceptors would otherwise try
     * to decompress a body that already is. The body's type goes as a
     * header, since the frame has no other place for it.
     */
    internal fun outgoingHeaders(request: Request): List<Pair<String, String>> {
        val headers = request.headers.filter { (name, _) -> !name.equals("Accept-Encoding", ignoreCase = true) }.toMutableList()
        val contentType = request.body?.contentType()
        if (contentType != null && request.header("Content-Type") == null) headers += "Content-Type" to contentType.toString()
        return headers
    }

    /**
     * Content-Encoding and Content-Length are dropped: the body arrives
     * decompressed, so both describe bytes that are no longer there.
     */
    internal fun response(
        request: Request,
        answer: Frames.Answer,
        sentAt: Long,
    ): Response {
        val headers = Headers.Builder()
        var contentType: String? = null
        answer.headers.forEach { (name, value) ->
            when {
                name.equals("Content-Encoding", ignoreCase = true) -> {}
                name.equals("Content-Length", ignoreCase = true) -> {}
                name.equals(FINAL_URL_HEADER, ignoreCase = true) -> {}
                else -> {
                    if (name.equals("Content-Type", ignoreCase = true)) contentType = value
                    headers.addUnsafeNonAscii(name, value)
                }
            }
        }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.status)
            .message(if (answer.status in 200..299) "OK" else "HTTP ${answer.status}")
            .headers(headers.build())
            .body(answer.body.toResponseBody(contentType?.toMediaTypeOrNull()))
            .handshake(if (request.url.isHttps) browserTls else null)
            .sentRequestAtMillis(sentAt)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
    }

    /** The desktop stand-in for the native: the same request, the same frame back. */
    private fun viaJdk(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
    ): String =
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.requestMethod = method
            headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val answerHeaders =
                connection.headerFields
                    .filterKeys { it != null }
                    .flatMap { (name, values) -> values.map { name to it } }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = ByteArrayOutputStream().also { out -> stream?.use { it.transferTo(out) } }.toByteArray()
            Frames.answer(status, answerHeaders + (FINAL_URL_HEADER to connection.url.toString()), bytes)
        } catch (error: Exception) {
            "ERR ${error.javaClass.simpleName}: ${error.message}"
        }
}
