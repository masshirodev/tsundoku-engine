# AGENTS.md

tsundoku-engine is a pinned fork of M-Extension-Server: a Kotlin/JVM server
that loads Mihon (manga) and Aniyomi (anime) extension APKs and answers
tsundoku's calls into them over HTTP. **Read `doc/FORK.md` before any change**:
it lists every way this fork differs from upstream and why, and a change that
isn't listed there is one the next upstream rebase silently loses.

If an `AGENTS.local.md` sits beside this file, read it too: it is gitignored
and says what is true of that checkout on that machine (where the client's
checkout is, where caches go, what else runs there). Never commit one.

- tsundoku is the client this fork is built for. Its side of the contract is
  tsundoku's `doc/EXTENSION-ENGINE.md`
  (https://github.com/masshirodev/tsundoku/blob/main/doc/EXTENSION-ENGINE.md);
  keep the methods, `/image/` and `/video/` it relies on stable.
- Stay close to upstream. A fix that upstream would want goes in as its own
  commit, in upstream's style, so it can be sent as a pull request. Our own
  additions stay optional (an environment variable, off by default), so
  behaviour without them is upstream's.
- `ENGINE_TOKEN`, `ENGINE_APK_DIR`, `ENGINE_HOST` and `ENGINE_WEBVIEW`
  (with `ENGINE_WEBVIEW_DIR`, `ENGINE_WEBVIEW_PAGES`) are the fork's switches
  (`doc/FORK.md`, and the table in `README.md`). A new one goes in both.
  Never persist source preferences here: the client owns them.
- The headless WebView (`doc/FORK.md`, "Headless WebView") runs Chromium in
  the engine's own process, so nothing may start it unproven: it is always
  started once in a child JVM first (`ChromiumProbe`), because Chromium that
  can't start aborts the process it is in. Keep that order.
- Licence: MPL-2.0, file by file. A new file carries the MPL-2.0 header the
  fork's other files use (after the `package` line). No GPL dependency comes
  back in (NewPipe was removed for that reason).
- Never name a real person anywhere in the repository; the fork's author is
  `masshirodev`.

## Before a commit

```sh
scripts/test.sh build      # lint, the whole test suite and the jar, in a JDK 21 container
```

`scripts/test.sh` leaves no Gradle daemon or JVM running. If you build on
the host instead, stop Gradle with `./gradlew --stop` afterwards and kill
only the PIDs you started: never `pkill java` or `killall java`, since other
JVMs (Suwayomi, an IDE) may run as the same user. `formatKotlin` fixes what
kotlinter's lint rejects.

## Conventions

- Tests sit beside upstream's, in `server/src/test/kotlin` and
  `AndroidCompat/src/test/kotlin`, on `kotlin.test`. A fix comes with the
  test that failed before it. Test fixtures use reserved hosts
  (`example.org`, `example.net`, `*.example`, `*.test`), never real sites.
- Comments say why. Name a site or extension only where the code genuinely
  special-cases it; otherwise say "a source". No dates or tracker ids as
  provenance: the why is the mechanism, not when it was found.
- No emoji in code or commits. No co-author trailers.
- Push to `main`.
- Only this fork's own workflow runs on GitHub (`.github/workflows/check.yml`,
  `scripts/test.sh build`); upstream's two stay disabled in the repository's
  settings (`doc/FORK.md`, "GitHub Actions"). It is the gate; `scripts/test.sh
  build` is the same check, by hand.
