package xyz.nulldev.androidcompat.webkit

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBuilder
import dev.datlag.kcef.KCEFClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.callback.CefDragData
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private val osName = System.getProperty("os.name").orEmpty().lowercase()
internal val isLinux = osName.startsWith("linux")
internal val isWindows = osName.startsWith("windows")
internal val isMac = osName.startsWith("mac")

/**
 * How the engine's Chromium is set up. Measured on the workstation
 * (2026-11-11, Chromium 111.0.5563.64): with Chromium's defaults one open
 * page meant six processes (two zygotes, a GPU process, the network service,
 * a renderer and a spare renderer), and five stayed after the page closed.
 * With these switches one page is one renderer, and nothing stays.
 */
internal object ChromiumSetup {
    /**
     * The JetBrains Runtime KCEF downloads Chromium from. Its JCEF must match
     * the `jcef` jar KCEF 2024.04.20.4 brings (a native interface), so this is
     * pinned to the release that jar was built for, as Suwayomi pins it; KCEF's
     * default, the latest release, would load a libjcef the jar doesn't fit.
     */
    const val JBR_RELEASE = "jbr-release-17.0.10b1087.23"

    fun switches(
        maxPages: Int,
        display: String?,
    ): Array<String> =
        listOfNotNull(
            display?.let { "--display=$it" },
            // KCEF already runs Chromium with no_sandbox (JCEF has no working
            // sandbox here), so no zygote buys nothing; without it no zygote
            // processes stay behind.
            "--no-sandbox",
            "--no-zygote",
            // Nothing is ever drawn on a screen: software only, GPU work in the
            // browser process rather than a process of its own.
            "--disable-gpu",
            "--in-process-gpu",
            "--enable-features=NetworkServiceInProcess2",
            // One --disable-features: Chromium reads only the last one given.
            "--disable-features=AudioServiceOutOfProcess,SpareRendererForSitePerProcess",
            "--renderer-process-limit=$maxPages",
            "--mute-audio",
            "--disable-extensions",
            // Not --js-flags=--jitless, though Chromium 111 runs unsandboxed
            // here: V8 then turns WebAssembly off, and players and decryptors
            // that use it fail without a word (doc/FORK.md, "Headless WebView").
            // No calls home: updates, metrics, safe browsing lists.
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-domain-reliability",
            "--no-pings",
        ).toTypedArray()

    fun builder(
        dir: File,
        switches: Array<String>,
        progress: (ChromiumRuntime.Progress) -> Unit,
    ): KCEFBuilder.() -> Unit =
        {
            installDir(dir)
            progress {
                var lastPercent = -1
                onLocating { progress(ChromiumRuntime.Progress("locating")) }
                onDownloading { downloaded ->
                    val percent = downloaded.toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        progress(ChromiumRuntime.Progress("downloading", percent))
                    }
                }
                onExtracting { progress(ChromiumRuntime.Progress("extracting")) }
                onInstall { progress(ChromiumRuntime.Progress("installing")) }
                onInitializing { progress(ChromiumRuntime.Progress("initializing")) }
            }
            download { github { release(JBR_RELEASE) } }
            settings {
                windowlessRenderingEnabled = true
                noSandbox = true
                logSeverity = KCEFBuilder.Settings.LogSeverity.Error
            }
            appHandler(KCEF.AppHandler(switches))
        }

    /** Windowless, and without even an AWT component: nothing for a window manager to see. */
    fun offscreen(): CefRendering = CefRendering.CefRenderingWithHandler(DiscardingRenderHandler, null)
}

/**
 * Chromium paints into this and it throws every frame away: the engine reads
 * pages, it never looks at them. A fixed 1280x720 viewport, so layouts that
 * depend on a size (players that only load when visible) still lay out.
 */
internal object DiscardingRenderHandler : CefRenderHandler {
    private val viewport = Rectangle(0, 0, 1280, 720)

    override fun getViewRect(browser: CefBrowser?): Rectangle = viewport

    override fun getScreenInfo(
        browser: CefBrowser?,
        screenInfo: CefScreenInfo?,
    ): Boolean = false

    override fun getScreenPoint(
        browser: CefBrowser?,
        viewPoint: Point,
    ): Point = viewPoint

    override fun getDeviceScaleFactor(browser: CefBrowser?): Double = 1.0

    override fun onPopupShow(
        browser: CefBrowser?,
        show: Boolean,
    ) {}

    override fun onPopupSize(
        browser: CefBrowser?,
        size: Rectangle?,
    ) {}

    override fun onPaint(
        browser: CefBrowser?,
        popup: Boolean,
        dirtyRects: Array<out Rectangle>?,
        buffer: ByteBuffer?,
        width: Int,
        height: Int,
    ) {}

    override fun onCursorChange(
        browser: CefBrowser?,
        cursorType: Int,
    ): Boolean = true

    override fun startDragging(
        browser: CefBrowser?,
        dragData: CefDragData?,
        mask: Int,
        x: Int,
        y: Int,
    ): Boolean = false

    override fun updateDragCursor(
        browser: CefBrowser?,
        operation: Int,
    ) {}
}

/**
 * The X display Chromium needs on Linux. CEF 111 checks for one even when
 * rendering windowless and aborts the whole process without it
 * (`temp_window_x11.cc: Check failed: g_xdisplay`, also with
 * `--ozone-platform=headless`), which in-process would be the engine. So the
 * engine never starts Chromium without one.
 *
 * A private Xvfb is preferred even on a desktop: nothing Chromium does can
 * then reach the desktop's display. It lives as long as the engine (a
 * shutdown hook, and tsundoku's launcher stops the engine's process group).
 * Not `-terminate`: Xvfb then exits when its last client lets go, and a
 * connection Chromium opens and closes while starting is enough to take the
 * display away under it, which aborts it (seen in a container, 2026-11-12).
 */
internal object HeadlessDisplay {
    class Handle(
        val name: String,
        private val process: Process?,
    ) : AutoCloseable {
        override fun close() {
            process?.destroy()
        }
    }

    private const val NO_DISPLAY =
        "Chromium on Linux needs an X display, and this machine has neither Xvfb nor a DISPLAY. " +
            "Install Xvfb (Debian and Ubuntu: xvfb; Fedora: xorg-x11-server-Xvfb; Arch: xorg-server-xvfb) and restart the engine."

    /** Null where Chromium needs no display (Windows, macOS). */
    fun open(
        env: (String) -> String? = System::getenv,
        findXvfb: () -> File? = { onPath("Xvfb", env("PATH")) },
    ): Handle? {
        if (!isLinux) return null
        findXvfb()?.let { return startXvfb(it) }
        env("DISPLAY")?.takeIf { it.isNotBlank() }?.let { display ->
            logger.warn { "No Xvfb found; Chromium will use the desktop's display $display (windowless: nothing is shown on it)" }
            return Handle(display, null)
        }
        throw ChromiumRuntime.PermanentFailure(NO_DISPLAY)
    }

    private fun startXvfb(binary: File): Handle {
        // Numbered by hand: -displayfd handed back :1, the desktop's own
        // XWayland, on the workstation (2026-11-11), and with -terminate it
        // exits at once. From 90 up, past anything a desktop or ssh -X uses.
        // A number free on this filesystem can still be taken: X also listens on
        // an abstract socket, which belongs to the network namespace, so a
        // container on the host's network collides with the host's servers.
        var from = 90
        repeat(5) {
            val number = freeDisplayNumber(range = from..199) ?: return@repeat
            from = number + 1
            val socket = File("/tmp/.X11-unix/X$number")
            val process =
                ProcessBuilder(binary.path, ":$number", "-nolisten", "tcp", "-noreset", "-screen", "0", "1280x720x24")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val deadline = System.currentTimeMillis() + 10_000
            while (process.isAlive && !socket.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            if (process.isAlive && socket.exists()) {
                Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
                logger.info { "Started a private Xvfb on :$number for Chromium (pid ${process.pid()})" }
                return Handle(":$number", process)
            }
            // Another server took the number first: try the next one.
            process.destroyForcibly()
        }
        throw WebViewUnavailableException("Xvfb (${binary.path}) did not start a display")
    }

    internal fun freeDisplayNumber(
        lockDir: File = File("/tmp"),
        range: IntRange = 90..199,
    ): Int? =
        range.firstOrNull { number ->
            !File(lockDir, ".X$number-lock").exists() && !File(lockDir, ".X11-unix/X$number").exists()
        }

    internal fun onPath(
        name: String,
        path: String?,
    ): File? =
        path
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.map { File(it, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }
}

/**
 * Starts Chromium once in a child JVM, with the switches the engine will use,
 * before the engine starts it in its own process. A Chromium that can't start
 * often doesn't throw: it aborts the process it runs in (no display, a
 * sandbox it can't set up, a library its GPU code needs), and in-process that
 * would be the engine, every loaded extension and every proxied stream. The
 * child also does the download, so its progress is the download's progress.
 */
internal object ChromiumProbe {
    private const val OK = "TSUNDOKU-WEBVIEW-PROBE-OK"
    private const val PROGRESS = "TSUNDOKU-WEBVIEW-PROBE-PROGRESS "
    private const val ERROR = "TSUNDOKU-WEBVIEW-PROBE-ERROR "

    /** A download that stops moving for this long is given up (the probe prints progress as it goes). */
    private const val STALL_MS = 180_000L
    private const val TOTAL_MS = 60 * 60_000L
    private const val PAGE_MS = 60_000L

    /** Lines that say why better than the probe's own error does. */
    private val NOTABLE = listOf("Check failed", "cannot open shared object", "Could not load", "UnsatisfiedLinkError")

    fun run(
        dir: File,
        switches: Array<String>,
        progress: (ChromiumRuntime.Progress) -> Unit,
    ) {
        val java = File(System.getProperty("java.home"), if (isWindows) "bin/java.exe" else "bin/java")
        val command =
            buildList {
                add(java.path)
                add("-Xmx384m")
                add("-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}")
                // As the engine itself runs (mextensionserver.Main): no AWT on a desktop's display, no Dock icon.
                if (isLinux) add("-Djava.awt.headless=true")
                if (isMac) add("-Dapple.awt.UIElement=true")
                add("-cp")
                add(System.getProperty("java.class.path"))
                add(ChromiumProbe::class.java.name)
                add(dir.path)
                addAll(switches)
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        val lines = LinkedBlockingQueue<String>()
        val reader =
            Thread({
                process.inputStream.bufferedReader().useLines { all -> all.forEach(lines::put) }
            }, "webview-probe-output").apply {
                isDaemon = true
                start()
            }
        val tail = ArrayDeque<String>()
        var ok = false
        var error: String? = null
        val started = System.currentTimeMillis()
        var lastActivity = started
        try {
            while (true) {
                val line = lines.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                if (line != null) {
                    lastActivity = now
                    when {
                        line.startsWith(PROGRESS) -> {
                            val (phase, percent) = line.removePrefix(PROGRESS).split(' ', limit = 2) + listOf("")
                            progress(ChromiumRuntime.Progress(phase, percent.trim().toIntOrNull()))
                            if (phase == "downloading" && percent.trim().toIntOrNull()?.rem(10) == 0) {
                                logger.info { "Downloading Chromium for WebView extensions: $percent%" }
                            }
                        }
                        line == OK -> ok = true
                        line.startsWith(ERROR) -> error = line.removePrefix(ERROR)
                        !line.startsWith("Fontconfig") && line.isNotBlank() -> {
                            tail.addLast(line)
                            if (tail.size > 40) tail.removeFirst()
                        }
                    }
                    continue
                }
                if (!process.isAlive && !reader.isAlive && lines.isEmpty()) break
                if (now - lastActivity > STALL_MS || now - started > TOTAL_MS) {
                    throw WebViewUnavailableException(
                        "Chromium's start check made no progress for ${STALL_MS / 1000} s" +
                            (tail.lastOrNull()?.let { " (last: $it)" } ?: ""),
                    )
                }
            }
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        if (ok) return
        val exit = process.waitFor()
        val fatal =
            tail.lastOrNull { line -> NOTABLE.any { it in line } }
                ?: tail.lastOrNull { "FATAL" in it }
                ?: tail.lastOrNull { "ERROR" in it }
        throw WebViewUnavailableException(
            when {
                error != null -> "Chromium could not be set up: $error" + (fatal?.takeIf { it !in error }?.let { " ($it)" }.orEmpty())
                fatal?.contains("g_xdisplay") == true || fatal?.contains("Missing X server") == true ->
                    "Chromium found no usable X display ($fatal)"
                else -> "Chromium exited during its start check (exit $exit)" + (fatal ?: tail.lastOrNull())?.let { ": $it" }.orEmpty()
            },
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val switches = args.drop(1).toTypedArray()
        var failure: Throwable? = null
        var restart = false
        try {
            KCEF.initBlocking(
                builder =
                    ChromiumSetup.builder(dir, switches) { step ->
                        println(PROGRESS + step.phase + (step.percent?.let { " $it" } ?: ""))
                    },
                onError = { failure = it ?: IllegalStateException("KCEF reported an error without a cause") },
                onRestartRequired = { restart = true },
            )
            failure?.let { throw it }
            if (restart) throw IllegalStateException("KCEF asks for a restart before Chromium can start")
            // KCEF reports a library that won't load only as "Could not load
            // 'jcef' library" and then waits forever; loading it here names the
            // file that's missing (libXtst.so.6 on a bare Ubuntu server).
            if (isLinux) {
                File(dir, "libjcef.so").takeIf { it.isFile }?.let { library ->
                    try {
                        System.load(library.path)
                    } catch (error: UnsatisfiedLinkError) {
                        throw IllegalStateException("a library Chromium needs is missing: ${error.message}", error)
                    }
                }
            }
            val client = KCEF.newClientBlocking()
            val loaded = CountDownLatch(1)
            client.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(
                        browser: CefBrowser,
                        frame: CefFrame,
                        httpStatusCode: Int,
                    ) {
                        if (frame.isMain) loaded.countDown()
                    }

                    override fun onLoadError(
                        browser: CefBrowser,
                        frame: CefFrame,
                        errorCode: CefLoadHandler.ErrorCode,
                        errorText: String,
                        failedUrl: String,
                    ) {
                        println("$ERROR a blank page failed to load: $errorText")
                    }
                },
            )
            client
                .createBrowser("data:text/html,<title>probe</title>", ChromiumSetup.offscreen(), false)
                .createImmediately()
            if (!loaded.await(PAGE_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("a blank page did not load in ${PAGE_MS / 1000} s")
            println(OK)
            System.out.flush()
        } catch (error: Throwable) {
            println(ERROR + (error.message ?: error.javaClass.name))
            System.out.flush()
            Runtime.getRuntime().halt(2)
        }
        // CEF's own shutdown can hang; the parent kills what's left either way.
        Runtime.getRuntime().halt(0)
    }
}

/** Chromium in this process, for [ChromiumRuntime]. */
internal class KcefStarter(
    private val config: WebViewConfig,
) : ChromiumRuntime.Starter<KCEFClient> {
    private var display: HeadlessDisplay.Handle? = null

    override fun start(progress: (ChromiumRuntime.Progress) -> Unit) {
        config.dir.mkdirs()
        HeadlessDisplay.open().use { probeDisplay ->
            ChromiumProbe.run(config.dir, ChromiumSetup.switches(config.maxPages, probeDisplay?.name), progress)
        }
        progress(ChromiumRuntime.Progress("starting"))
        val engineDisplay = HeadlessDisplay.open()
        display = engineDisplay
        var failure: Throwable? = null
        var restart = false
        try {
            KCEF.initBlocking(
                builder = ChromiumSetup.builder(config.dir, ChromiumSetup.switches(config.maxPages, engineDisplay?.name), progress),
                onError = { failure = it ?: IllegalStateException("KCEF reported an error without a cause") },
                onRestartRequired = { restart = true },
            )
            failure?.let { throw it }
            if (restart) throw IllegalStateException("KCEF asks for an engine restart before Chromium can start")
            // Waits for CEF's own initialisation, and throws if that failed.
            KCEF.newClientBlocking().dispose()
        } catch (error: Throwable) {
            // KCEF can't be initialised twice in one process, and neither can Chromium.
            throw ChromiumRuntime.PermanentFailure(
                "Chromium passed its start check but did not start in the engine: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
        logger.info { "Chromium started for WebView extensions (${config.dir}, at most ${config.maxPages} pages)" }
    }

    override fun newClient(): KCEFClient = KCEF.newClientBlocking()

    override fun disposeClient(client: KCEFClient) {
        client.dispose()
    }
}
