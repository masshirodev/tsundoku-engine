package mextensionserver.controller

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The JVM's regex recurses once per repetition; Android's doesn't. An
 * extension's pattern over a long page (AniZone) must not kill its request.
 */
class DeepStackAsyncRunnerTest {
    // One recursion per repetition: 20k of them is about 7 MB of stack, past
    // the JVM's default (1 MB) and well inside a request thread's 64 MB.
    private val longPage = "ab".repeat(10_000)
    private val pattern = Regex("(?:a|b)*")

    private fun runOn(thread: (Runnable) -> Thread): Result<Boolean> {
        val outcome = CompletableFuture<Result<Boolean>>()
        thread(Runnable { outcome.complete(runCatching { pattern.matches(longPage) }) }).start()
        return outcome.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun aDefaultThreadOverflowsOnTheExtensionsPattern() {
        // What a request thread was before: the same pattern dies.
        val outcome = runOn { Thread(null, it, "default", 0) }
        assertIs<StackOverflowError>(outcome.exceptionOrNull())
    }

    @Test
    fun aRequestThreadDoesNot() {
        val outcome = runOn { DeepStackAsyncRunner.requestThread(it, "request") }
        assertTrue(outcome.getOrThrow())
    }
}
