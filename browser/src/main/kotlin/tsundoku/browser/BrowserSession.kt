package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * One request to the engine as NanoHTTPD would have parsed it off a socket,
 * built from a frame instead: the engine's handlers read the session, never
 * the socket, so they run unchanged. Header names are lower-cased, as
 * NanoHTTPD does, and a non-multipart body is `postData`, as NanoHTTPD's
 * `parseBody` gives it.
 *
 * The URL may be a path (`/dalvik`) or absolute. An absolute one under
 * [origin], the engine's own (which may carry a path, such as
 * `https://app.example/engine`), is routed by what follows it, so a proxy URL
 * the engine handed out comes back to the route it names.
 */
internal class BrowserSession(
    head: Frames.Head,
    private val body: ByteArray?,
    origin: String? = null,
) : NanoHTTPD.IHTTPSession {
    private val method: NanoHTTPD.Method =
        NanoHTTPD.Method.entries.firstOrNull { it.name == head.method.uppercase() }
            ?: throw Frames.FrameException("Unknown method ${head.method}")
    private val uri: String
    private val query: String?
    private val headers: MutableMap<String, String> = LinkedHashMap()

    init {
        val local =
            when {
                head.url.startsWith("/") -> head.url
                origin != null && head.url.startsWith(origin, ignoreCase = true) ->
                    "/" + head.url.substring(origin.length).trimStart('/')
                else -> null
            }
        val parsed = URI(if (local != null) "http://engine.invalid$local" else head.url)
        uri = parsed.rawPath?.takeIf { it.isNotEmpty() }?.let(::decode) ?: "/"
        query = parsed.rawQuery
        head.headers.forEach { (name, value) ->
            val key = name.lowercase()
            // NanoHTTPD keeps the last value of a repeated header; so does this.
            headers[key] = value
        }
    }

    override fun execute() = throw UnsupportedOperationException("A browser session has no socket to read")

    override fun getCookies(): NanoHTTPD.CookieHandler = throw UnsupportedOperationException("No route reads cookies through NanoHTTPD")

    override fun getHeaders(): MutableMap<String, String> = headers

    override fun getInputStream(): InputStream = ByteArrayInputStream(body ?: ByteArray(0))

    override fun getMethod(): NanoHTTPD.Method = method

    @Deprecated("NanoHTTPD's own deprecation: use getParameters")
    override fun getParms(): Map<String, String> = parameters.mapValues { (_, values) -> values.first() }

    override fun getParameters(): Map<String, List<String>> =
        query
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.map { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }?.groupBy({ it.first }, { it.second })
            .orEmpty()

    override fun getQueryParameterString(): String? = query

    override fun getUri(): String = uri

    override fun parseBody(files: MutableMap<String, String>) {
        if (body != null && (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.PUT)) {
            files["postData"] = String(body, StandardCharsets.UTF_8)
        }
    }

    override fun getRemoteIpAddress(): String = "127.0.0.1"

    override fun getRemoteHostName(): String = "localhost"

    // As NanoHTTPD's decodePercent, so a path or a parameter means here what it means there.
    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrDefault(text)
}
