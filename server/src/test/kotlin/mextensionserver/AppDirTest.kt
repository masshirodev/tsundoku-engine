package mextensionserver

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
