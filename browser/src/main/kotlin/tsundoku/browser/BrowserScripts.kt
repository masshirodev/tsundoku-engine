package tsundoku.browser

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/**
 * Where an extension's JavaScript runs in the browser: in the browser's own
 * JavaScript engine, through [evaluateNative], which the page implements.
 * The engine's QuickJS (quickjs4j: QuickJS built to WebAssembly and
 * interpreted by Chicory, in Java) works in CheerpJ but takes about 20 s to
 * create and minutes for a source's page script, where the browser takes
 * milliseconds; so the browser target's `app.cash.quickjs.QuickJs` sends
 * scripts here instead (`doc/FORK.md`, "The browser target").
 *
 * The scripts are a site's, so the native must run them where they can
 * reach nothing of the app's: a worker or iframe with an opaque origin (a
 * `data:` URL worker, a sandboxed iframe without `allow-same-origin`), with
 * a time limit.
 */
object BrowserScripts {
    /** Tests only: evaluates in place of the native. */
    @Volatile
    internal var evaluator: ((script: String) -> String)? = null

    /**
     * Implemented in JavaScript (`Java_tsundoku_browser_BrowserScripts_evaluateNative`).
     * Evaluates [script] as global code (an indirect `eval`), and answers JSON:
     * `{"value": <the completion value, JSON.stringify'd>}` (no `value` for
     * `undefined`), or `{"error": "<message>"}` for a script that threw or
     * ran out of time.
     */
    @JvmStatic
    external fun evaluateNative(script: String): String?

    @JvmStatic
    fun evaluate(script: String): String =
        (evaluator ?: ::evaluateNative).invoke(script)
            ?: """{"error":"The page's script runner answered nothing"}"""
}
