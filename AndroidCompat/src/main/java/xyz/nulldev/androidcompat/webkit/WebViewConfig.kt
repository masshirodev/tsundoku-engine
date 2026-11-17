package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import xyz.nulldev.ts.config.ApplicationRootDir
import java.io.File

/**
 * The fork's headless WebView switches (doc/FORK.md). Off unless
 * `ENGINE_WEBVIEW` says otherwise, so an engine nobody configured behaves as
 * upstream's does: KCEF is never started, and a WebView falls back to
 * Mangayomi's bridge or fails.
 */
data class WebViewConfig(
    val enabled: Boolean,
    /** Where Chromium is downloaded to and run from. */
    val dir: File,
    val maxPages: Int,
    val pageLifetimeMs: Long,
) {
    companion object {
        const val ENABLED_ENV = "ENGINE_WEBVIEW"
        const val DIR_ENV = "ENGINE_WEBVIEW_DIR"
        const val PAGES_ENV = "ENGINE_WEBVIEW_PAGES"

        const val DEFAULT_PAGES = 2
        private const val MAX_PAGES = 8

        /** Longer than any extension's own WebView timeout seen (the longest wait 30-60 s). */
        const val PAGE_LIFETIME_MS = 120_000L

        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            rootDir: () -> String = { ApplicationRootDir },
        ): WebViewConfig =
            WebViewConfig(
                enabled = env(ENABLED_ENV)?.trim()?.lowercase() in setOf("1", "on", "true", "yes"),
                dir =
                    env(DIR_ENV)?.takeIf { it.isNotBlank() }?.let(::File)
                        ?: File(rootDir(), "webview/chromium"),
                maxPages =
                    env(PAGES_ENV)
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceIn(1, MAX_PAGES)
                        ?: DEFAULT_PAGES,
                pageLifetimeMs = PAGE_LIFETIME_MS,
            )
    }
}
