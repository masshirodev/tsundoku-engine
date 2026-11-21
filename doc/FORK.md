# This fork

tsundoku-engine is a pinned fork of
[kodjodevf/M-Extension-Server](https://github.com/kodjodevf/M-Extension-Server)
(MPL-2.0), the headless engine that runs Mihon and Aniyomi extensions for
[tsundoku](https://github.com/masshirodev/tsundoku). Why this engine and not
one of our own, and what it was measured doing, is in tsundoku's
[`doc/EXTENSION-ENGINE.md`](https://github.com/masshirodev/tsundoku/blob/main/doc/EXTENSION-ENGINE.md)
("Why a fork of M-Extension-Server"). This file is what differs from
upstream, and how to follow it.

## Pinned at

Upstream `775a6fd` (2026-10-28, "feat(webview): secure WebView stubs and
bridge headless resolution to Mangayomi"). Everything after it on `main` is
ours.

## What differs, and why

| Change                                                                                 | Why                                                                                                                                                                                                                                                                                                                                                                                                                                         | Upstream?              |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| QuickJs reads the result after the whole `QUICJS_RESULT:` marker                       | It read from index 7 of a 14-character marker, so every JavaScript result failed to parse: every extension that evaluates JS broke (a source's page list, for one).                                                                                                                                                                                                                                                                                 | to send                |
| QuickJs takes the last result marker, not the first                                    | The runner's stdout accumulates, so a reused instance answered every call with its first result. Found writing the test for the fix above.                                                                                                                                                                                                                                                                                                  | to send                |
| Injekt provides `ProtoBuf` under its own type                                          | Mihon's app module does; protobuf sources died in a static initialiser without it.                                                                                                                                                                                                                                                                                                                                             | to send                |
| NewPipe Extractor and `/youtube/resolve` removed                                       | NewPipe is GPLv3, so a jar with it is GPLv3 as a whole. tsundoku has no YouTube-backed sources. `/capabilities` reports `"youtubeResolver":false`.                                                                                                                                                                                                                                                                                          | no (upstream wants it) |
| `ENGINE_TOKEN`: a bearer token on every route but `/`                                  | No endpoint was authenticated, `/stop` stops the server, and the proxies fetch through each source's client and cookies.                                                                                                                                                                                                                                                                                                                    | could offer            |
| `ENGINE_APK_DIR`: loaded APKs kept under their handle                                  | Handles lived in memory only, so every restart meant a 409 per source and every APK resent and reconverted.                                                                                                                                                                                                                                                                                                                                 | could offer            |
| `ENGINE_HOST`: the address to listen on                                                | NanoHTTPD bound every interface. In a container that is published to localhost only; run on a desktop (tsundoku's native bundle) it put the engine on the LAN and raised a firewall prompt.                                                                                                                                                                                                                                                 | could offer            |
| `filterSchemaManga` / `filterSchemaAnime`: a source's filters, typed                   | `filtersManga` returns the FilterList as Jackson dumps it: no type, and each extension's minified fields (`a`, `b`, ...) beside the base ones, so a client can only guess a tri-state from a header, and a Select's values may be objects. These say each filter's type from its base class, and give every label as the toString Mihon's UI draws (`impl/FilterSchema.kt`). `filtersManga` is untouched; filters still go back in by name. | could offer            |
| `GET /challenges`: hosts whose browser check is being solved, and when each last was   | A first call through a Cloudflare check takes 20-45 s, and nothing told the client why. `ChallengeLog` (beside `CloudflareInterceptor`) records the host while its solve runs and when it last succeeded; tsundoku asks while it waits and names the check at once. In memory only.                                                                                                                                                         | could offer            |
| Anime posters go through the `/image/` proxy, as manga posters do                      | Anime thumbnails came back as the site's bare URL, so a site that answers only the extension's client got nothing: one source's posters answer a plain request with a Cloudflare 403. The poster entry now holds a client and headers rather than an `HttpSource`, so an `AnimeHttpSource` fits too (`registerAnimePoster`, `bridgeAnime`).                                                                                                  | to send                |
| A dex allocation is restored when any ancestor was written, not only the direct parent | R8 drops trivial constructors all the way up a chain (legal on ART), and dex2jar then writes `new-instance Child` plus `Object.<init>` as `new Object`. `repairAllocation` put the type back only when the class extended Object directly, so a Kotlin `object` under an abstract base failed in `<clinit>`: "Type 'java/lang/Object' is not assignable to 'p'" (seen in two sources' extensions).                  | to send                |
| The bytecode repairs analyse a method with stack headroom, and say when they skip one | dex2jar can record a `max_stack` one short of what ASM's Analyzer needs, and ASM's own recount (`COMPUTE_MAXS`) agrees with dex2jar. The Analyzer then refused the method, and every repair skipped it in silence (`runCatching { }.getOrNull()`), so an erased allocation in it stayed `new Object` and the JVM refused the class: a source's extension, `h.intercept` @387, "Type 'java/lang/Object' is not assignable to 'x'". The analysis now runs with 16 slots of headroom (the declared size is put back; `transform()` writes the real one), and a skipped method is logged as a warning. | to send |
| Episodes carry their picture (`preview_url`), through the `/image/` proxy              | `JEpisode` dropped Aniyomi's `SEpisode.preview_url`, so tsundoku's episode list had no pictures even from extensions that set one. It goes through the poster proxy (`bridgeEpisode`) for the same reason posters do. The field is new and nullable; a client that ignores it sees upstream's answer.                                                                                                                                       | could offer            |
| `RenderProcessGoneDetail`, and `android.net`'s `Network`, `NetworkCapabilities` and `NetworkRequest`, are AndroidCompat's own classes | Suwayomi regenerated `android-jar` 1.0.0 in place on its `android-jar` branch without them (it ships its own copies now; the three `android.net` ones here are Suwayomi's), so a fresh resolve (the Dockerfile's) failed to compile `KcefWebViewProvider` and `ConnectivityManager` while a warm Gradle cache, holding the old jar, still passed. | to send |
| The `android-jar` stubs come from a pinned commit of Suwayomi's `android-jar` branch, not the branch (`build.gradle.kts`) | Suwayomi regenerates `android-jar` 1.0.0 in place whenever its AndroidCompat takes classes over, so the same coordinates change under a build. Its latest copy dropped `android.content.res.Configuration`, `Resources` and `android.util.DisplayMetrics` (its AndroidCompat now implements them), and a fresh resolve failed to compile `KcefWebViewProvider`. The pin is the jar this fork last built with; taking Suwayomi's own `Resources` implementation is a change of behaviour for extensions and goes in on its own. | no (upstream resolves the branch) |
| The app-dir argument is the root every engine file goes under                          | `Main` set `ts.server.rootDir`, which nothing reads (`ApplicationRootDir` reads `suwayomi.tachidesk.config.server.rootDir`). The argument was ignored, and preferences (`settings/<source>.xml`) went to Suwayomi's own default folder, `~/.local/share/Tachidesk`, beside a same-user Suwayomi's copies of the same files. | to send |
| Request threads have 64 MB stacks (`DeepStackAsyncRunner`)                             | Extensions run on the request thread. Android's regex (ICU) doesn't recurse; the JVM's recurses once per repetition, so a pattern over a whole page overflowed the default 1 MB: a source's extension died with `StackOverflowError` in `Pattern$Loop` and the client saw the connection close. The stack is reserved, not committed. (It looked like a WebView failure; that extension has no WebView code.) | to send |
| `shouldOverrideUrlLoading` is asked about navigations only; `isForMainFrame` means the main frame's document | Every resource was put to it, with `isForMainFrame` true for anything the main frame loaded, so an extension's "stay on the site" check cancelled every script and stylesheet from a CDN and the page never ran. Android asks about navigations only, and not about the one `loadUrl` started. | to send |
| `evaluateJavascript` takes a null callback                                              | Android allows it and extensions pass one (from `onPageFinished`); the Kotlin override was non-null, so it threw before the script ran. | to send |
| An empty answer after the extension's WebView failed is that failure (`WebViewIncidents`) | An extension waits on its WebView from the request thread; when the WebView fails (none to be had, a page that won't load, the extension's own WebView callback throwing on the looper, where the Looper only logs it), the extension often gives up with an empty list, and the client took "no videos" for an answer. The provider keeps each such incident with the extension's class loader, and a call to that extension that comes back empty after one answers with an error saying what happened. | could offer |
| `ENGINE_WEBVIEW`: WebViews in a headless Chromium (below)                              | WebView extensions failed: `KcefWebViewProvider` was registered but `KCEF.init` was never called. | could offer |
| `docker-compose.yml` publishes `127.0.0.1:8080` and requires `ENGINE_TOKEN` | Upstream's published `8080` on every interface with no token: anyone on the network could load and run an APK. Compose now refuses to start without a token, and the engine, listening on every interface inside the container, is reachable from the host's loopback only. | no |
| `scripts/test.sh`                                                                      | Runs the Gradle suite in a throwaway JDK container: no JDK on the host, no daemon left running (where another JVM, such as Suwayomi's, runs as the same user, killing "the java processes" is not safe).                                                                                                                                                                                                                                     | no                     |

Every environment variable is optional. Unset, the engine behaves exactly as
upstream does, which is what Mangayomi's embedded bridge relies on.

**Preferences are not persisted here, on purpose.** The engine writes a
preference it is sent only when the key isn't present yet (unless the
request's `__mangayomi_bridge_context__` says `preferenceApplyMode:
replace-present`). A copy kept across restarts would outlive the client's
change. tsundoku owns every source's values and sends them with every call in
replace mode, so the engine's copy is only ever this process's.

## GitHub Actions

**GitHub Actions are turned off** in this repository's settings, and upstream's two
workflows stay off. They stay in `.github/workflows/` unchanged, so a rebase
onto upstream doesn't conflict on them, but neither does anything this fork
wants:

- `build-ios-runtime.yml` builds and tests Mangayomi's on-device iOS runtime
  (`-PiosRuntime=true`) on every push and pull request, and publishes it as an
  immutable `ios-runtime-v*` release. This fork makes no iOS runtime: the
  `iosRuntime` build switch is upstream's and is left as it is, untested here.
- `publish.yml` fires on any `v*` tag and builds and publishes desktop
  installers (DMG, Windows zip and installer, deb, AppImage) and platform JREs
  as a GitHub release. This fork publishes no releases of its own: tsundoku
  builds the shadow jar from a commit and ships it inside its own release.
  Left on, any `v*` tag pushed here would publish a release of installers
  built by upstream's packaging scripts.

`scripts/test.sh build` (lint, tests, the jar) is the check, run before a
push. A workflow for this fork, if one is wanted, would be a new file that
runs that, not one of these turned back on.

## Headless WebView

The engine's WebView support. `ENGINE_WEBVIEW=on` gives every extension that
uses `android.webkit.WebView`, Mihon (manga) or Aniyomi (anime), for any site,
a real browser nobody sees: a page loaded in a hidden WebView, a script
evaluated in it, a JavaScript interface, the requests the page makes seen in
`shouldInterceptRequest`, its cookies shared with the extension's HTTP client.
Nothing here knows any extension or site. (The engine loads no novel
extensions at all; one that did would get the same WebView.) Off, nothing below happens and a WebView fails
as upstream's does (Mangayomi's bridge, or a load error).

| Variable               | Default                       | What it is                                                     |
| ---------------------- | ----------------------------- | -------------------------------------------------------------- |
| `ENGINE_WEBVIEW`       | off                           | `on` (or `1`, `true`, `yes`) turns it on                        |
| `ENGINE_WEBVIEW_DIR`   | `<app dir>/webview/chromium`  | where Chromium is downloaded to and runs from                  |
| `ENGINE_WEBVIEW_PAGES` | 2 (1 to 8)                    | pages open at once; one renderer each, more wait for a slot    |

The code: `ChromiumRuntime` (the lifecycle, generic so it is tested without
Chromium), `KcefChromium.kt` (the switches, the display, the start check),
`HeadlessWebView` (the engine's one runtime, its status, the cookie copy), the
headless branches of `KcefWebViewProvider`, and `WebViewSupport` on the
server's side.

### Decisions, and why

- **Chromium comes when something needs it.** Loading an extension whose dex
  names `android.webkit.WebView` (a byte search, `WebViewSupport.usesWebView`)
  starts the download in the background, so it is there by the extension's
  first call: extensions give a WebView 10 to 60 s, a download doesn't fit in
  that. A first page asked for also starts it. An install whose extensions
  never use a WebView never downloads anything. Bundled instead, every
  release would carry 170-190 MB per platform for the few extensions that
  need it.
- **What KCEF downloads is pinned**: the JetBrains Runtime release
  `jbr-release-17.0.10b1087.23` (`ChromiumSetup.JBR_RELEASE`), the one KCEF
  2024.04.20.4's `jcef` jar was built against; its native side must match the
  jar, and KCEF's default, the latest release, would not. It is Chromium
  111.0.5563.64: 178.7 MB to download (linux-x64; 181.1 linux-aarch64, 186.4
  windows-x64, 176.5 osx-x64, 169.1 osx-aarch64), 359 MB unpacked; seconds
  on a fast line, with progress in the log every 10 %.
- **Chromium is started in a child JVM first** (`ChromiumProbe`), with the
  engine's switches, and only then in the engine. A Chromium that can't start
  often doesn't throw: it aborts its process (no X display:
  `Check failed: g_xdisplay`; a missing library; a GPU driver), and in the
  engine that is every loaded extension and every proxied stream with it.
  The child also does the download. It costs about 5 s once per engine run.
  Seen working: an engine whose child died of exactly that abort stayed up and
  said why.
- **Nothing is ever on a screen.** Pages render windowless into a handler that
  throws every frame away (`CefRenderingWithHandler` with no AWT component, so
  no JOGL canvas either); AWT is headless on Linux and kept out of macOS's
  Dock (`apple.awt.UIElement`). On Linux, CEF 111 still wants an X display even
  windowless, and aborts without one (also with `--ozone-platform=headless`),
  so the engine starts a **private Xvfb** (`:90` up, numbered by hand:
  `-displayfd` can hand back a display the desktop already uses) even
  on a desktop, so Chromium never touches the user's display. No Xvfb but a
  `DISPLAY`: that one, windowless (logged as a warning). Neither: the WebView
  fails with what to install, and nothing is downloaded. Xvfb runs with
  `-noreset`, not `-terminate`: with `-terminate` it exits when its last
  client lets go, which took the display away under a starting Chromium. It
  stops with the engine (a shutdown hook; tsundoku's launcher stops the
  engine's process group).
- **Lean Chromium.** KCEF already runs it with `no_sandbox` (JCEF has no
  working sandbox here), so `--no-zygote` costs nothing; GPU and the network
  service run in the browser process; no spare renderer; renderers capped at
  `ENGINE_WEBVIEW_PAGES`; no calls home (component updates, metrics). Measured
  on an Arch Linux desktop: Chromium's defaults left five processes after a page
  closed (two zygotes, GPU, network service, a spare renderer); with these,
  one page is one renderer (110-130 MB), and none stays.
- **Pages are given back.** A WebView's page is closed when the extension
  destroys it, and after 120 s at the latest (`WebViewConfig.PAGE_LIFETIME_MS`,
  past every extension timeout seen), so a leaked WebView can't hold a
  renderer. A page beyond the cap waits; a WebView destroyed while waiting
  never gets one. A WebView has one browser, navigated by each later
  `loadUrl` or `postUrl` as on Android (upstream closed it and made another
  each time), and its page goes back only once Chromium says its browsers
  are closed (5 s at most): a browser is created asynchronously, and one
  closed before it existed outlived its client and kept its renderer, one
  per call of an extension that loads about:blank and then destroys its WebView.
- **"Shut down when idle" means the pages, not Chromium.** Chromium (and KCEF)
  can be initialised once per process: after `KCEF.dispose` it can't come back
  without an engine restart. So at idle every page and renderer is gone, and
  what stays is Chromium's runtime inside the engine plus the private Xvfb.
  Measured with one WebView extension loaded: 318 MB RSS without Chromium, 576 MB with it
  idle, of which about 200 MB is file-backed (`libcef.so` and friends, which
  the kernel can drop), plus Xvfb 37 MB. Running Chromium in its own process
  instead (JetBrains' remote JCEF) would give that back; KCEF's download
  doesn't include its server.
- **A failure is an answer, never a throw.** A WebView that can't get a page
  hears `onReceivedError` and `onPageFinished` on its own thread, as a real
  WebView reports a failed load; a script asked for with no page coming is
  answered null at once (an extension evaluating from `onPageFinished`
  otherwise looped: 538 reports in 30 s, now 1). The WebView constructor never
  waits for Chromium (it runs on the main looper every extension shares). A
  failed start is retried after 60 s (no network comes back); one that can't
  get better without a restart (no display, a failure inside the engine) is
  not retried.
- **Cookies cross.** In Mihon a WebView and OkHttp share one jar
  (AndroidCookieJar over CookieManager) and WebView extensions rely on it:
  they clear a check or earn a token in the page, then fetch. Here the
  extensions' client keeps its own jar, so cookies a page set go into it (and
  into `CookieManager`) when a page finishes and when it closes, and its
  cookies for a URL go into Chromium before the page loads it.
- **The client can tell.** `GET /capabilities` has `"webview": true|false`
  (whether this engine runs them at all); `GET /webview` says how it is
  (`off`, `idle`, `starting` with the phase and download percent, `ready`,
  `failed` with the reason; pages open and waiting); `extensionInfo` has
  `usesWebView`; and an error from a WebView extension carries the same status
  under `webview`, since the extension's own message is just "timed out".

### Proven, and not

- An Arch Linux desktop (Temurin 21.0.13, `DISPLAY` unset): an extension
  that uses a page, a JavaScript interface and a script evaluated on
  `onPageFinished`, with the site's own app decrypting the reply, resolves an
  episode's 8 videos in 8-17 s; an extension that runs its site's own player
  in the page, presses Play from a script and catches the playlist request in
  `shouldInterceptRequest` resolves HLS episodes in 3-11 s; the desktop's
  window list did not change while pages ran; after each call no renderer
  remained, only the Xvfb.
- A container with no X and no Xvfb: the WebView fails at once with what to
  install, nothing is downloaded, the engine stays up.
- A headless Ubuntu 22.04 container with Xvfb and Chromium's libraries
  (`xvfb libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2
  libxkbcommon0 libxcomposite1 libxdamage1 libxrandr2 libgbm1 libpango-1.0-0
  libcairo2 libasound2 libxshmfence1 libxtst6`; without `libxtst6` the start
  check names the missing file): Chromium starts and a bare page loads, but
  a WebView extension's page did not finish loading in the engine in that container (3
  runs), and the start check failed once in 3. Not the network, `/dev/shm`,
  fonts or the user; not resolved.
- Not run: Windows, macOS, WSL, a desktop without Xvfb.

## Following upstream

`upstream` is the original repository; `origin` is this one.

```sh
git fetch upstream
git log --oneline 775a6fd..upstream/main     # what upstream did since the pin
git rebase upstream/main                      # or merge: our changes are small and separate
scripts/test.sh                               # the whole suite
```

Then move the pin above, and check that tsundoku's contract still holds
(`doc/EXTENSION-ENGINE.md`, "API reference"): upstream is shaped around
Mangayomi and has one active maintainer, so a rename there is a break here.
Once a fix marked "to send" is merged upstream, drop our copy in the rebase.

## Not done yet

- **Prebuilt extension JARs.** Some Mihon extension repositories publish
  JARs beside the APKs. Loading those instead of converting APKs with dex2jar
  most likely fixes search in sources whose code dex2jar gets wrong
  (tsundoku's `doc/EXTENSION-ENGINE.md` has the case); the Aniyomi
  repositories publish APKs only, so dex2jar stays either way.
- **The upstream pull requests** for the fixes marked "to send".
- **Cloudflare through the same browser.** `CloudflareInterceptor` solves a
  challenge through FlareSolverr only. With the WebView running it could solve
  one itself, as Mihon's own interceptor does in its WebView: on a 403/503
  from a Cloudflare server, open a page at the URL with the client's user
  agent (`ChallengeLog.solving` already marks the host), wait until a
  `cf_clearance` cookie appears for the host (`visitUrlCookies`, polled, a
  30-60 s budget), copy the cookies across (the copy here already does) and
  set the client's user agent for the host to the page's (`setSolvedUA`), then
  retry. What it needs: a third branch beside FlareSolverr and the bridge,
  behind a switch; the request thread to wait without holding a page slot
  longer than the solve; and testing against real checks. Chromium 111 is the
  risk: Turnstile and managed challenges may fingerprint an old, headless
  browser and refuse it, which only trying shows. FlareSolverr would stay the
  first choice where it runs.
- **The headless WebView on a headless server**: the container case above
  ("Proven, and not"), and Windows, macOS and WSL, which nobody has run.
- **Chromium 111, unsandboxed**, runs the scripts of the sites extensions
  open as the engine's user; whether to accept that or harden it is open.
  `--js-flags=--jitless` would shrink
  what an exploit can do but turns WebAssembly off, which players and
  decryptors use, so it is not set. A newer Chromium means a newer KCEF/JCEF
  pair, and KCEF's last release is 2024.04.20.4.
