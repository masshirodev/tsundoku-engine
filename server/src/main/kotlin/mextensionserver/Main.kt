package mextensionserver

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import android.os.Looper
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.controller.MExtensionServerController
import mextensionserver.impl.WebViewSupport
import mextensionserver.ui.ServerWindow
import org.kodein.di.DI
import org.kodein.di.conf.global
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.ts.config.ApplicationRootDir
import xyz.nulldev.ts.config.CONFIG_PREFIX
import xyz.nulldev.ts.config.ConfigKodeinModule
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}
private val androidCompat by lazy { AndroidCompat() }
private val applicationInitLock = Any()

@Volatile
private var applicationInitialized = false

@Suppress("BlockingMethodInNonBlockingContext")
fun main(args: Array<String>) {
    val useUI = "--ui" in args
    val filteredArgs = args.filter { it != "--ui" }
    val port = filteredArgs.getOrNull(0)?.toIntOrNull() ?: 0
    val appDir = filteredArgs.getOrNull(1)

    if (useUI) {
        // Must be set before any AWT/Swing initialisation
        System.setProperty("apple.awt.application.name", "MExtension Server")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
    } else {
        // Headless Chromium (ENGINE_WEBVIEW) brings AWT classes in. Without a
        // window to show, AWT has no business on a desktop's display, or in
        // macOS's Dock. Set before anything touches AWT.
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.startsWith("linux")) System.setProperty("java.awt.headless", "true")
        if (os.startsWith("mac")) System.setProperty("apple.awt.UIElement", "true")
    }

    initApplication(appDir)

    if (useUI) {
        // Show the Swing window; the AWT event-dispatch thread (non-daemon)
        // keeps the JVM alive until the window is closed.
        SwingUtilities.invokeLater { ServerWindow().show() }
    } else {
        val controller = MExtensionServerController()
        controller.start(port)
        Runtime.getRuntime().addShutdownHook(Thread { controller.stop() })
        // Keep running
        while (controller.isRunning()) {
            Thread.sleep(1000)
        }
    }
}

/**
 * Starts the engine's Android shim and its dependencies, once per process.
 * [mainLooper] and [webView] are on for every engine with a listener; an
 * engine in a browser tab (the fork's browser target) has no WebView to run
 * and turns them off to start sooner (`doc/FORK.md`, "The browser target").
 */
fun initApplication(
    appDir: String?,
    mainLooper: Boolean = true,
    webView: Boolean = true,
) {
    synchronized(applicationInitLock) {
        if (applicationInitialized) return

        logger.info("Running MExtensionServer ${BuildConfig.VERSION} revision ${BuildConfig.REVISION}")

        // Set custom app directory if provided
        appDir?.let(::useAppDir)
        CookieHandler.setDefault(CookieManager())

        if (mainLooper) startMainLooper()

        // Load config API
        DI.global.addImport(ConfigKodeinModule().create())
        // Load Android compatibility dependencies
        AndroidCompatInitializer().init()
        // start app
        androidCompat.startApp(App())
        // A WebView's cookies and the extensions' HTTP client's are one jar in
        // Mihon; join them here (HeadlessWebView, doc/FORK.md).
        if (webView) WebViewSupport.connect(Injekt.get<NetworkHelper>().cookieJar)
        applicationInitialized = true
    }
}

/**
 * Makes [appDir] the root every engine file goes under. Upstream set only
 * `ts.server.rootDir`, which nothing reads: [ApplicationRootDir] reads
 * `suwayomi.tachidesk.config.server.rootDir`, so the argument was ignored and
 * the engine wrote preferences and AndroidCompat files into Suwayomi's own
 * default data folder (`~/.local/share/Tachidesk` on Linux), where a Suwayomi
 * running as the same user keeps its own `settings/<source>.xml`.
 */
internal fun useAppDir(appDir: String) {
    System.setProperty("ts.server.rootDir", appDir)
    System.setProperty("$CONFIG_PREFIX.server.rootDir", appDir)
}

private fun startMainLooper() {
    if (Looper.getMainLooper() != null) return

    val ready = CompletableFuture<Unit>()
    Thread(
        {
            try {
                Looper.prepareMainLooper()
                ready.complete(Unit)
                Looper.loop()
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                logger.error(error) { "Android main looper stopped" }
            }
        },
        "Android main looper",
    ).apply {
        isDaemon = true
        start()
    }
    ready.join()
}
