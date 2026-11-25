package mextensionserver.controller

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import mextensionserver.impl.MihonImageProxy
import mextensionserver.impl.MihonVideoProxy

/**
 * Every route the engine answers, apart from the listener and its token.
 * [MExtensionServerController]'s NanoHTTPD server sends each request here,
 * and so does an engine with no socket at all (tsundoku-engine's browser
 * target, `doc/FORK.md`, "The browser target"), which builds the session
 * itself: one routing, so the two cannot drift apart.
 */
object EngineRoutes {
    /**
     * [onStop] is what `/stop` does; without one (an engine with no
     * listener to stop) the route is not there.
     */
    fun route(
        session: IHTTPSession,
        onStop: (() -> Unit)? = null,
    ): Response =
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
            "/stop" ->
                if (onStop == null) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                } else {
                    newFixedLengthResponse("Server stopping").also {
                        Thread {
                            Thread.sleep(100)
                            onStop()
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

    /**
     * Makes the `/image/` and `/video/` URLs the engine hands out point at
     * [origin] instead of the loopback listener: for an engine with no
     * listener, whose client routes that origin back to [route].
     */
    fun useProxyOrigin(origin: String) {
        MihonImageProxy.configureOrigin(origin)
        MihonVideoProxy.configureOrigin(origin)
    }
}
