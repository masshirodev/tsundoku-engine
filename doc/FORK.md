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

| Change                                                                                 | Why                                                                                                                                                                                                                                                                                                                                                                                                                                         | Upstream?              |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| QuickJs reads the result after the whole `QUICJS_RESULT:` marker                       | It read from index 7 of a 14-character marker, so every JavaScript result failed to parse: every extension that evaluates JS broke (Mangahere's page list).                                                                                                                                                                                                                                                                                 | to send                |
| QuickJs takes the last result marker, not the first                                    | The runner's stdout accumulates, so a reused instance answered every call with its first result. Found writing the test for the fix above.                                                                                                                                                                                                                                                                                                  | to send                |
| Injekt provides `ProtoBuf` under its own type                                          | Mihon's app module does; protobuf sources (MANGA Plus) died in a static initialiser without it.                                                                                                                                                                                                                                                                                                                                             | to send                |
| NewPipe Extractor and `/youtube/resolve` removed                                       | NewPipe is GPLv3, so a jar with it is GPLv3 as a whole. tsundoku has no YouTube-backed sources. `/capabilities` reports `"youtubeResolver":false`.                                                                                                                                                                                                                                                                                          | no (upstream wants it) |
| `ENGINE_TOKEN`: a bearer token on every route but `/`                                  | No endpoint was authenticated, `/stop` stops the server, and the proxies fetch through each source's client and cookies.                                                                                                                                                                                                                                                                                                                    | could offer            |
| `ENGINE_APK_DIR`: loaded APKs kept under their handle                                  | Handles lived in memory only, so every restart meant a 409 per source and every APK resent and reconverted.                                                                                                                                                                                                                                                                                                                                 | could offer            |
| `ENGINE_HOST`: the address to listen on                                                | NanoHTTPD bound every interface. In a container that is published to localhost only; run on a desktop (tsundoku's native bundle) it put the engine on the LAN and raised a firewall prompt.                                                                                                                                                                                                                                                 | could offer            |
| `filterSchemaManga` / `filterSchemaAnime`: a source's filters, typed                   | `filtersManga` returns the FilterList as Jackson dumps it: no type, and each extension's minified fields (`a`, `b`, ...) beside the base ones, so a client can only guess a tri-state from a header, and a Select's values may be objects. These say each filter's type from its base class, and give every label as the toString Mihon's UI draws (`impl/FilterSchema.kt`). `filtersManga` is untouched; filters still go back in by name. | could offer            |
| `GET /challenges`: hosts whose browser check is being solved, and when each last was   | A first call through a Cloudflare check takes 20-45 s, and nothing told the client why. `ChallengeLog` (beside `CloudflareInterceptor`) records the host while its solve runs and when it last succeeded; tsundoku asks while it waits and names the check at once. In memory only.                                                                                                                                                         | could offer            |
| Anime posters go through the `/image/` proxy, as manga posters do                      | Anime thumbnails came back as the site's bare URL, so a site that answers only the extension's client got nothing: AnimePahe's posters answer a plain request with a Cloudflare 403. The poster entry now holds a client and headers rather than an `HttpSource`, so an `AnimeHttpSource` fits too (`registerAnimePoster`, `bridgeAnime`).                                                                                                  | to send                |
| A dex allocation is restored when any ancestor was written, not only the direct parent | R8 drops trivial constructors all the way up a chain (legal on ART), and dex2jar then writes `new-instance Child` plus `Object.<init>` as `new Object`. `repairAllocation` put the type back only when the class extended Object directly, so a Kotlin `object` under an abstract base failed in `<clinit>`: "Type 'java/lang/Object' is not assignable to 'p'" (Aethon Webcomics 1.6.0, Weeb Central 1.6.26, 2026-11-01).                  | to send                |
| `scripts/test.sh`                                                                      | Runs the Gradle suite in a throwaway JDK container: no JDK on the host, no daemon left running (on the workstation, Suwayomi's JVM runs as the same user, so killing "the java processes" is not safe).                                                                                                                                                                                                                                     | no                     |

**GitHub Actions are turned off** for this repository (Settings, or
`gh api -X PUT repos/masshirodev/tsundoku-engine/actions/permissions -F enabled=false`).
Upstream's workflows stay in `.github/` so rebases stay clean, but they build
iOS runtimes on macOS runners for every pull request and publish releases on
`v*` tags, and on a private repository those are metered minutes for nothing
tsundoku uses. `scripts/test.sh` is the CI.

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
