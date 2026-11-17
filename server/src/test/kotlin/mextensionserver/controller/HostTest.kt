package mextensionserver.controller

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * ENGINE_HOST (doc/FORK.md): bound to 127.0.0.1, the engine answers on
 * loopback and nowhere else, which is what keeps it off a desktop's LAN.
 */
class HostTest {
    private val controller = MExtensionServerController(bindHost = "127.0.0.1", token = null)

    @AfterTest
    fun stopServer() {
        controller.pause()
    }

    /** This machine's own LAN-side address, if it has one (a container does). */
    private fun outsideAddress(): String? =
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress

    private fun connect(host: String) {
        Socket().use { it.connect(InetSocketAddress(host, controller.getPort()), 2000) }
    }

    @Test
    fun `bound to loopback, it answers there and not on the machine's other address`() {
        controller.start(0)

        connect("127.0.0.1")
        val outside = outsideAddress() ?: return
        assertFailsWith<ConnectException> { connect(outside) }
    }
}
