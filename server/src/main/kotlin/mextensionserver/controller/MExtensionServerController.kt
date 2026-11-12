package mextensionserver.controller

import fi.iki.elonen.NanoHTTPD
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.impl.MExtensionServerLoader
import mextensionserver.impl.MihonImageProxy
import mextensionserver.impl.MihonVideoProxy
import java.io.IOException
import java.security.MessageDigest

/**
 * [token], when set, is required as `Authorization: Bearer <token>` on every
 * route but `/`: no endpoint is otherwise authenticated, `/stop` stops the
 * server, and the proxies fetch through each source's client and cookies, so
 * anything that can reach the port could drive the engine. Unset (the
 * default, as upstream), nothing changes.
 */
class MExtensionServerController(
    private val bindHost: String? = System.getenv(HOST_ENV)?.takeIf { it.isNotBlank() },
    private val token: String? = System.getenv(TOKEN_ENV)?.takeIf { it.isNotBlank() },
) {
    companion object {
        const val TOKEN_ENV = "ENGINE_TOKEN"

        /**
         * The address to listen on. Unset, every interface, as upstream: right
         * in a container, whose port is published to localhost only. Run on a
         * desktop (tsundoku's native bundle), 127.0.0.1 keeps the engine off
         * the LAN, and off the firewall's prompt.
         */
        const val HOST_ENV = "ENGINE_HOST"
    }

    private val logger = KotlinLogging.logger {}
    private var server: WebServer? = null

    fun start(port: Int) {
        try {
            server = WebServer(port)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val actualPort = server?.listeningPort ?: 0
            MihonImageProxy.configure(actualPort)
            MihonVideoProxy.configure(actualPort)
            logger.info { "mextensionserver server started on port $actualPort" }
        } catch (e: IOException) {
            logger.error(e) { "Failed to start mextensionserver server" }
            throw e
        }
    }

    fun stop() {
        pause()
        MExtensionServerLoader.cleanupTempFiles()
    }

    /**
     * Stops only the loopback listener while retaining loaded extension
     * instances. The embedded iOS bridge uses this during app suspension so a
     * resume does not repeat APK conversion and source initialization.
     */
    fun pause() {
        server?.stop()
        server = null
        logger.info { "mextensionserver server stopped" }
    }

    fun isRunning(): Boolean = server?.isAlive == true

    fun getPort(): Int = server?.listeningPort ?: 0

    private inner class WebServer(
        port: Int,
    ) : NanoHTTPD(bindHost, port) {
        init {
            setAsyncRunner(DeepStackAsyncRunner())
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/" && !authorized(session)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
            }
            return route(session)
        }

        private fun authorized(session: IHTTPSession): Boolean {
            val expected = token ?: return true
            val header = session.headers["authorization"] ?: return false
            if (!header.startsWith("Bearer ")) return false
            return MessageDigest.isEqual(header.removePrefix("Bearer ").toByteArray(), expected.toByteArray())
        }

        private fun route(session: IHTTPSession): Response =
            when (session.uri) {
                "/dalvik" -> DalvikHandler().serve(session)
                "/" -> newFixedLengthResponse("mextensionserver Server Running")
                "/capabilities" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"mangayomiMihonBridge":1,"sourceFactory":true,"preferenceCallbacks":true,"imageProxy":true,"videoProxy":true,"youtubeResolver":false,"sourceUrls":true,"extensionHandles":true,"extensionInfo":true,"webviewBridge":true,"webview":${xyz.nulldev.androidcompat.webkit.HeadlessWebView.enabled}}""",
                    )
                // The headless Chromium WebView extensions run in, and how it is (the fork's, ENGINE_WEBVIEW).
                "/webview" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        xyz.nulldev.androidcompat.webkit.HeadlessWebView
                            .statusJson(),
                    )
                // Which hosts' browser checks are being solved now, and when each last was (the fork's).
                "/challenges" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        eu.kanade.tachiyomi.network.interceptor.ChallengeLog
                            .json(),
                    )
                "/stop" -> {
                    newFixedLengthResponse("Server stopping").also {
                        Thread {
                            Thread.sleep(100)
                            stop()
                        }.start()
                    }
                }
                else ->
                    if (session.uri.startsWith(ImageProxyHandler.ROUTE_PREFIX)) {
                        ImageProxyHandler().serve(session)
                    } else if (session.uri.startsWith(VideoProxyHandler.ROUTE_PREFIX)) {
                        VideoProxyHandler().serve(session)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                    }
            }
    }
}
