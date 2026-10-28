# This fork

tsundoku-engine is a pinned fork of
[kodjodevf/M-Extension-Server](https://github.com/kodjodevf/M-Extension-Server)
(MPL-2.0), the headless engine that runs Mihon and Aniyomi extensions for
[tsundoku](https://github.com/masshirodev/tsundoku). Why this engine and not
our own, and everything it was measured doing, is tsundoku's
`doc/EXTENSION-ENGINE.md` (the spike, card c113 on the Tsundoku board). This
file is what differs from upstream, and how to follow it.

## Pinned at

Upstream `775a6fd` (2026-10-28, "feat(webview): secure WebView stubs and
bridge headless resolution to Mangayomi"), the commit the spike measured.
Everything after it on `main` is ours.

## What differs, and why

| Change                                                         | Why                                                                                                                                                                                            | Upstream?                         |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| QuickJs reads the result after the whole `QUICJS_RESULT:` marker | It read from index 7 of a 14-character marker, so every JavaScript result failed to parse: every extension that evaluates JS broke (Mangahere's page list).                                   | to send                           |
| QuickJs takes the last result marker, not the first            | The runner's stdout accumulates, so a reused instance answered every call with its first result. Found writing the test for the fix above.                                                   | to send                           |
| Injekt provides `ProtoBuf` under its own type                  | Mihon's app module does; protobuf sources (MANGA Plus) died in a static initialiser without it.                                                                                               | to send                           |
| NewPipe Extractor and `/youtube/resolve` removed               | NewPipe is GPLv3, so a jar with it is GPLv3 as a whole. tsundoku has no YouTube-backed sources. `/capabilities` reports `"youtubeResolver":false`.                                              | no (upstream wants it)            |
| `ENGINE_TOKEN`: a bearer token on every route but `/`          | No endpoint was authenticated, `/stop` stops the server, and the proxies fetch through each source's client and cookies.                                                                       | could offer                       |
| `ENGINE_APK_DIR`: loaded APKs kept under their handle          | Handles lived in memory only, so every restart meant a 409 per source and every APK resent and reconverted.                                                                                   | could offer                       |
| `scripts/test.sh`                                              | Runs the Gradle suite in a throwaway JDK container: no JDK on the host, no daemon left running (on the workstation, Suwayomi's JVM runs as the same user, so killing "the java processes" is not safe). | no                                |

Both environment variables are optional. Unset, the engine behaves exactly as
upstream does, which is what Mangayomi's embedded bridge relies on.

**Preferences are not persisted here, on purpose.** The engine writes a
preference it is sent only when the key isn't present yet (unless the
request's `__mangayomi_bridge_context__` says `preferenceApplyMode:
replace-present`). A copy kept across restarts would outlive the client's
change. tsundoku owns every source's values and sends them with every call in
replace mode, so the engine's copy is only ever this process's.

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

- **Keiyoushi's prebuilt JARs.** Loading them instead of converting APKs with
  dex2jar most likely fixes Toonily and Toonily.me search (the spike's
  finding); the Aniyomi repositories publish APKs only, so dex2jar stays
  either way.
- **The upstream pull requests** for the three fixes marked "to send".
