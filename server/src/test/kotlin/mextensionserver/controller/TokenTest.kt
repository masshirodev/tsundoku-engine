package mextensionserver.controller

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenTest {
    private val controller = MExtensionServerController(bindHost = "127.0.0.1", token = "s3cret")

    @AfterTest
    fun stopServer() {
        controller.pause()
    }

    private fun status(
        path: String,
        authorization: String? = null,
    ): Int {
        val connection =
            URI("http://127.0.0.1:${controller.getPort()}$path").toURL().openConnection() as HttpURLConnection
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `with a token, every route but the health check asks for it`() {
        controller.start(0)

        assertEquals(200, status("/"))
        assertEquals(401, status("/capabilities"))
        assertEquals(401, status("/capabilities", "Bearer wrong"))
        assertEquals(401, status("/capabilities", "s3cret"))
        assertEquals(200, status("/capabilities", "Bearer s3cret"))
        assertEquals(401, status("/image/anything"))
        assertEquals(401, status("/video/anything"))
    }

    // /stop is the one route that does harm on its own, so it is the case
    // the token exists for.
    @Test
    fun `an unauthenticated stop leaves the server running`() {
        controller.start(0)

        assertEquals(401, status("/stop"))
        Thread.sleep(300)
        assertTrue(controller.isRunning())
    }
}
