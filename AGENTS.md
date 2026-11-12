# AGENTS.md

tsundoku-engine is a pinned fork of M-Extension-Server: a Kotlin/JVM server
that loads Mihon (manga) and Aniyomi (anime) extension APKs and answers
tsundoku's calls into them over HTTP. **Read `doc/FORK.md` before any change**:
it lists every way this fork differs from upstream and why, and a change that
isn't listed there is one the next upstream rebase silently loses.

- tsundoku is the only client. Its side of the contract, and what the spike
  measured, is `~/projects/tsundoku/doc/EXTENSION-ENGINE.md`; keep the methods,
  `/image/` and `/video/` it relies on stable.
- Stay close to upstream. A fix that upstream would want goes in as its own
  commit, in upstream's style, so it can be sent as a pull request. Our own
  additions stay optional (an environment variable, off by default), so
  behaviour without them is upstream's.
- `ENGINE_TOKEN`, `ENGINE_APK_DIR`, `ENGINE_HOST` and `ENGINE_WEBVIEW`
  (with `ENGINE_WEBVIEW_DIR`, `ENGINE_WEBVIEW_PAGES`) are the fork's switches
  (`doc/FORK.md`). Never persist source preferences here: tsundoku owns them.
- The headless WebView (`doc/FORK.md`, "Headless WebView") runs Chromium in
  the engine's own process, so nothing may start it unproven: it is always
  started once in a child JVM first (`ChromiumProbe`), because Chromium that
  can't start aborts the process it is in. Keep that order.
- Licence: MPL-2.0, file by file. No GPL dependency comes back in (NewPipe was
  removed for that reason).

## Before a commit

```sh
scripts/test.sh            # the whole Gradle suite, in a JDK 21 container
```

Never `pkill java` or `killall java` on the workstation: Suwayomi's JVM runs
as the same user. `scripts/test.sh` leaves nothing running; if you ever build
on the host, stop Gradle with `./gradlew --stop` and kill only PIDs you
started. The Gradle cache lives on `/mnt/hdd2` (`GRADLE_CACHE`), not in home.

## Conventions

- Tests sit beside upstream's, in `server/src/test/kotlin`, on `kotlin.test`.
  A fix comes with the test that failed before it.
- Comments say why. No emoji in code or commits. No co-author trailers.
- Push to `main`. The repository is private: it's built and run, not
  distributed. Distributing it would mean publishing its MPL source too.
- Work is tracked on tsundoku's board (Extensions label), card c131 ("Fork
  M-Extension-Server as tsundoku's engine, with a solver beside it").
