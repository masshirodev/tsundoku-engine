package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewConfigTest {
    private fun config(vararg env: Pair<String, String>) =
        WebViewConfig.fromEnvironment(env = env.toMap()::get, rootDir = { "/data/engine" })

    @Test
    fun offUnlessAskedFor() {
        assertFalse(config().enabled)
        assertFalse(config(WebViewConfig.ENABLED_ENV to "off").enabled)
        assertFalse(config(WebViewConfig.ENABLED_ENV to "").enabled)
        assertTrue(config(WebViewConfig.ENABLED_ENV to "on").enabled)
        assertTrue(config(WebViewConfig.ENABLED_ENV to " TRUE ").enabled)
        assertTrue(config(WebViewConfig.ENABLED_ENV to "1").enabled)
    }

    @Test
    fun chromiumLivesInTheEngineDataFolderUnlessToldOtherwise() {
        assertEquals(File("/data/engine", "webview/chromium"), config().dir)
        assertEquals(File("/srv/engine/chromium"), config(WebViewConfig.DIR_ENV to "/srv/engine/chromium").dir)
    }

    @Test
    fun pagesAreCapped() {
        assertEquals(WebViewConfig.DEFAULT_PAGES, config().maxPages)
        assertEquals(3, config(WebViewConfig.PAGES_ENV to "3").maxPages)
        assertEquals(1, config(WebViewConfig.PAGES_ENV to "0").maxPages)
        assertEquals(8, config(WebViewConfig.PAGES_ENV to "50").maxPages)
        assertEquals(WebViewConfig.DEFAULT_PAGES, config(WebViewConfig.PAGES_ENV to "many").maxPages)
    }
}

/** Chromium on Linux aborts its process without an X display; the engine must never let it. */
class HeadlessDisplayTest {
    @Test
    fun withNeitherXvfbNorADisplayItRefusesWithWhatToInstall() {
        if (!isLinux) return
        val error =
            assertFailsWith<ChromiumRuntime.PermanentFailure> {
                HeadlessDisplay.open(env = { null }, findXvfb = { null })
            }
        assertTrue("Xvfb" in error.message.orEmpty())
    }

    @Test
    fun withoutXvfbItFallsBackToTheDesktopsDisplay() {
        if (!isLinux) return
        val handle = HeadlessDisplay.open(env = mapOf("DISPLAY" to ":1")::get, findXvfb = { null })
        assertEquals(":1", handle?.name)
    }

    @Test
    fun xvfbIsFoundOnThePath() {
        val dir = createTempDirectory("path").toFile()
        try {
            assertNull(HeadlessDisplay.onPath("Xvfb", dir.path))
            val binary = File(dir, "Xvfb").apply { writeText("#!/bin/sh\n") }
            binary.setExecutable(true)
            assertEquals(binary, HeadlessDisplay.onPath("Xvfb", "/nonexistent${File.pathSeparator}${dir.path}"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPrivateDisplaySkipsEveryNumberAServerHolds() {
        val tmp = createTempDirectory("x11").toFile()
        try {
            File(tmp, ".X90-lock").writeText("1134\n")
            File(tmp, ".X11-unix").mkdirs()
            File(tmp, ".X11-unix/X91").writeText("")

            assertEquals(92, HeadlessDisplay.freeDisplayNumber(lockDir = tmp, range = 90..95))
            assertNull(HeadlessDisplay.freeDisplayNumber(lockDir = tmp, range = 90..91))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun theSwitchesPointChromiumAtTheDisplayAndCapRenderers() {
        val switches = ChromiumSetup.switches(maxPages = 3, display = ":7").toList()
        assertTrue("--display=:7" in switches)
        assertTrue("--renderer-process-limit=3" in switches)
        // Chromium honours only the last --disable-features, so there must be one.
        assertEquals(1, switches.count { it.startsWith("--disable-features=") })
        assertFalse(ChromiumSetup.switches(maxPages = 2, display = null).any { it.startsWith("--display") })
    }
}
