package mextensionserver.impl

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import mextensionserver.model.AnimeResponse
import xyz.nulldev.androidcompat.webkit.WebViewIncidents
import java.net.URLClassLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * An extension whose WebView failed answers with nothing, and the client took
 * "no videos" for an answer. It must hear the failure instead.
 */
class EmptyAfterWebViewIncidentTest {
    private val extension = URLClassLoader(emptyArray())
    private val another = URLClassLoader(emptyArray())

    @AfterTest
    fun clear() = WebViewIncidents.clear()

    @Test
    fun anEmptyAnswerAfterTheExtensionsWebViewFailedIsThatFailure() {
        val since = WebViewIncidents.now()
        WebViewIncidents.record(extension, "the extension's WebView callback threw NullPointerException: callback")

        val error =
            assertFailsWith<IllegalStateException> {
                MihonInvoker.failIfEmptyAfterWebViewIncident(emptyList<Any>(), since, extension)
            }
        assertEquals("The extension's WebView failed: the extension's WebView callback threw NullPointerException: callback", error.message)
        assertFailsWith<IllegalStateException> {
            MihonInvoker.failIfEmptyAfterWebViewIncident(AnimeResponse(emptyList(), false), since, extension)
        }
    }

    @Test
    fun anAnswerWithSomethingInItStands() {
        val since = WebViewIncidents.now()
        WebViewIncidents.record(extension, "a page failed")

        MihonInvoker.failIfEmptyAfterWebViewIncident(listOf("a video"), since, extension)
    }

    @Test
    fun anotherExtensionsIncidentOrAnEarlierOneIsNotThisCalls() {
        WebViewIncidents.record(extension, "before the call")
        val since = WebViewIncidents.now()
        WebViewIncidents.record(another, "someone else's WebView")

        MihonInvoker.failIfEmptyAfterWebViewIncident(emptyList<Any>(), since, extension)
    }
}
