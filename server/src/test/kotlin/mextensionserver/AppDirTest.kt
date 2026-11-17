package mextensionserver

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import xyz.nulldev.ts.config.ApplicationRootDir
import xyz.nulldev.ts.config.CONFIG_PREFIX
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine's second argument is its data folder (doc/FORK.md). Upstream set
 * a property nothing reads, so preferences went to Suwayomi's default folder.
 */
class AppDirTest {
    private val key = "$CONFIG_PREFIX.server.rootDir"
    private val previous = System.getProperty(key)

    @AfterTest
    fun restore() {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }

    @Test
    fun theAppDirArgumentBecomesTheRootEveryFileGoesUnder() {
        useAppDir("/srv/engine-data")

        assertEquals("/srv/engine-data", ApplicationRootDir)
    }
}
