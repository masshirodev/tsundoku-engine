package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.util.Base64

/**
 * The text frames requests and responses cross between Java and JavaScript
 * in: a Java object reaching JavaScript can't be read there, and strings
 * are what CheerpJ hands across cheaply. The same two shapes go both ways,
 * for the engine's own requests to the network (the native
 * `BrowserTransport.fetchNative`) and for the app's requests to the engine
 * (`BrowserEngine.handle`):
 *
 * - a request head is `METHOD\nURL\nName: value\n...`, its body separate,
 *   as base64 (empty for none);
 * - a response is `STATUS\nName: value\n...\n\nBASE64BODY`, or
 *   `ERR <message>` from a native that could not fetch at all.
 *
 * Header values can't hold a line break (HTTP forbids one too), so one is
 * sent as a space rather than breaking the frame.
 */
object Frames {
    data class Head(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
    )

    class Answer(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    class FrameException(
        message: String,
    ) : IllegalArgumentException(message)

    @JvmStatic
    fun head(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
    ): String =
        buildString {
            append(method).append('\n').append(oneLine(url)).append('\n')
            headers.forEach { (name, value) -> append(oneLine(name)).append(": ").append(oneLine(value)).append('\n') }
        }

    @JvmStatic
    fun parseHead(head: String): Head {
        val lines = head.split('\n')
        val method = lines.getOrNull(0)?.trim().orEmpty()
        val url = lines.getOrNull(1)?.trim().orEmpty()
        if (method.isEmpty() || url.isEmpty()) throw FrameException("A request head needs a method and a URL")
        return Head(method, url, headerLines(lines.drop(2)))
    }

    @JvmStatic
    fun answer(
        status: Int,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): String =
        buildString {
            append(status).append('\n')
            headers.forEach { (name, value) -> append(oneLine(name)).append(": ").append(oneLine(value)).append('\n') }
            append('\n')
            append(Base64.getEncoder().encodeToString(body))
        }

    /** Throws [FrameException] for an `ERR` answer, or one that isn't a frame. */
    @JvmStatic
    fun parseAnswer(frame: String?): Answer {
        if (frame == null) throw FrameException("No answer")
        if (frame.startsWith("ERR")) throw FrameException(frame.removePrefix("ERR").trim().ifEmpty { "The fetch failed" })
        val blank = frame.indexOf("\n\n")
        val headPart = if (blank < 0) frame else frame.substring(0, blank)
        val lines = headPart.split('\n')
        val status =
            lines[0].trim().toIntOrNull()?.takeIf { it in 100..599 }
                ?: throw FrameException("Not an answer: ${frame.take(40)}")
        val body =
            if (blank < 0) {
                ByteArray(0)
            } else {
                try {
                    Base64.getDecoder().decode(frame.substring(blank + 2))
                } catch (error: IllegalArgumentException) {
                    throw FrameException("The answer's body is not base64: ${error.message}")
                }
            }
        return Answer(status, headerLines(lines.drop(1)), body)
    }

    @JvmStatic
    fun decodeBody(bodyBase64: String?): ByteArray? =
        bodyBase64
            ?.takeIf { it.isNotEmpty() }
            ?.let { Base64.getDecoder().decode(it) }

    private fun headerLines(lines: List<String>): List<Pair<String, String>> =
        lines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

    private fun oneLine(text: String): String = if ('\n' in text || '\r' in text) text.replace(Regex("[\r\n]+"), " ") else text
}
