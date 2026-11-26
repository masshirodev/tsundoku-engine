package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import fi.iki.elonen.NanoHTTPD
import mextensionserver.controller.EngineRoutes
import mextensionserver.impl.MExtensionServerLoader
import mextensionserver.initApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * The engine in a browser tab: what JavaScript calls (`doc/FORK.md`, "The
 * browser target"). There is no listener. The app sends each request it
 * would have sent the engine over HTTP to [handle], as a frame ([Frames]),
 * and gets the engine's answer back as one; the request goes through the
 * same routes ([EngineRoutes]) a listener's would.
 *
 * Neither method ever throws: a Java exception that reaches JavaScript is a
 * Java object the page can't even print, so every failure comes back as
 * text. There is no bearer token either, since nothing outside the tab can
 * reach the engine.
 */
object BrowserEngine {
    /**
     * Where the engine's `/image/` and `/video/` URLs point when [start] is
     * given no origin: tsundoku's client already rewrites loopback proxy
     * URLs onto the engine's address, so these resolve wherever it says the
     * engine is.
     */
    const val DEFAULT_ORIGIN = "http://127.0.0.1"

    @Volatile
    private var started = false

    @Volatile
    private var origin: String = DEFAULT_ORIGIN
    private val startLock = Any()

    /** See [start] with an origin. */
    @JvmStatic
    fun start(dataDir: String): String = start(dataDir, null)

    /**
     * Starts the engine once; later calls answer at once. [dataDir] is where
     * its files go (CheerpJ's `/files/` persists across visits): source
     * preferences, temporary files, and extensions already converted from
     * their APK, so each is converted once. [selfOrigin] is the origin the
     * engine's proxy URLs point at, which the app routes back to [handle].
     *
     * Answers `{"ok":true,"ms":<n>}` or `{"error":"<text>"}`.
     */
    @JvmStatic
    fun start(
        dataDir: String,
        selfOrigin: String?,
    ): String {
        val began = System.nanoTime()
        return try {
            synchronized(startLock) {
                if (!started) {
                    val root = File(dataDir)
                    // The engine's temporary files (an APK being converted)
                    // go under its own folder: a tab has no system one.
                    val tmp = File(root, "tmp").apply { mkdirs() }
                    System.setProperty("java.io.tmpdir", tmp.absolutePath)
                    MExtensionServerLoader.convertedJarDir = File(root, "extensions")
                    // A tab runs no WebView: no Chromium, so neither its
                    // cookie bridge nor the main looper it posts to.
                    initApplication(root.absolutePath, mainLooper = mainLooper, webView = false)
                    started = true
                }
                origin = (selfOrigin?.takeIf { it.isNotBlank() } ?: DEFAULT_ORIGIN).trimEnd('/')
                EngineRoutes.useProxyOrigin(origin)
            }
            """{"ok":true,"ms":${(System.nanoTime() - began) / 1_000_000}}"""
        } catch (error: Throwable) {
            """{"error":${jsonString(describe(error))}}"""
        }
    }

    /**
     * Tests and measurements only: whether [start] runs the Android main
     * looper. On by default, since an extension may post to it.
     */
    @Volatile
    @JvmStatic
    var mainLooper: Boolean = true

    /**
     * One request to the engine: [head] is `METHOD\nURL\nName: value\n...`
     * (the URL a path such as `/dalvik`, or absolute), [bodyBase64] its body
     * (empty for none). Answers `STATUS\nName: value\n...\n\nBASE64BODY`;
     * any failure is a `500` whose body is the stack trace.
     */
    @JvmStatic
    fun handle(
        head: String,
        bodyBase64: String?,
    ): String =
        try {
            if (!started) {
                failure(503, "The engine has not been started: call start first")
            } else {
                val session = BrowserSession(Frames.parseHead(head), Frames.decodeBody(bodyBase64), origin)
                val response = EngineRoutes.route(session)
                response.use { frame(it, session.method) }
            }
        } catch (error: Throwable) {
            try {
                failure(500, describe(error))
            } catch (_: Throwable) {
                // Even writing the failure failed (out of memory): the
                // smallest answer that is still a frame.
                "500\n\n"
            }
        }

    /**
     * The response as NanoHTTPD would put it on the wire, read back into a
     * frame: NanoHTTPD keeps a response's headers to itself and writes them
     * only in `send`, which also handles fixed-length and chunked bodies.
     * Headers that describe the connection or the wire encoding are left out:
     * the frame's body is the body.
     */
    private fun frame(
        response: NanoHTTPD.Response,
        method: NanoHTTPD.Method,
    ): String {
        response.setRequestMethod(method)
        response.setKeepAlive(false)
        val wire = ByteArrayOutputStream()
        sendMethod.invoke(response, wire as OutputStream)
        return Wire.toFrame(wire.toByteArray())
    }

    private val sendMethod by lazy {
        NanoHTTPD.Response::class.java.getDeclaredMethod("send", OutputStream::class.java).apply { isAccessible = true }
    }

    private fun failure(
        status: Int,
        text: String,
    ): String = Frames.answer(status, listOf("Content-Type" to "text/plain; charset=utf-8"), text.toByteArray(StandardCharsets.UTF_8))

    private fun describe(error: Throwable): String {
        val cause = if (error is java.lang.reflect.InvocationTargetException) error.targetException ?: error else error
        return StringWriter().also { cause.printStackTrace(PrintWriter(it)) }.toString()
    }

    internal fun jsonString(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    /** Tests only: forget the start, as a new tab would (the engine's own state stays). */
    internal fun resetForTest() {
        started = false
    }

    /** Reads an HTTP/1.1 response off the bytes NanoHTTPD wrote. */
    internal object Wire {
        private val dropped = setOf("connection", "keep-alive", "content-length", "transfer-encoding", "content-encoding")

        fun toFrame(bytes: ByteArray): String {
            val end = indexOf(bytes, "\r\n\r\n".toByteArray())
            require(end >= 0) { "The engine wrote no response head" }
            // NanoHTTPD writes the head in the content type's charset, UTF-8 unless it says otherwise.
            val lines = String(bytes, 0, end, StandardCharsets.UTF_8).split("\r\n")
            val status = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: error("Not a status line: ${lines[0]}")
            val headers =
                lines.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
            val chunked = headers.any { (name, value) -> name.equals("transfer-encoding", true) && value.contains("chunked", true) }
            val rest = bytes.inputStream(end + 4, bytes.size - end - 4)
            val body = if (chunked) dechunk(rest) else rest.readBytes()
            return Frames.answer(status, headers.filter { (name, _) -> name.lowercase() !in dropped }, body)
        }

        private fun dechunk(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break
                out.write(input.readNBytes(size))
                readLine(input)
            }
            return out.toByteArray()
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }

        private fun indexOf(
            haystack: ByteArray,
            needle: ByteArray,
        ): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
