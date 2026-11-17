# tsundoku-engine

tsundoku-engine is a fork of
[kodjodevf/M-Extension-Server](https://github.com/kodjodevf/M-Extension-Server):
a headless Kotlin/JVM server that loads Mihon (manga) and Aniyomi (anime)
extension APKs and runs them for [tsundoku](https://github.com/masshirodev/tsundoku)
over a small HTTP API. A client sends an extension's APK, and then calls its
catalogue and content methods (popular, search, details, chapters, pages,
episodes, videos) and fetches images and video through the engine, which
uses the extension's own HTTP client and headers.

It is an unofficial project, not affiliated with or endorsed by Mihon,
Aniyomi, or M-Extension-Server's authors. It ships no extensions and no
content: what it runs is whatever APKs its client sends it.

## How it differs from upstream

The fork stays close to upstream and keeps upstream's behaviour unless one of
its environment variables (below) is set. On top of that it fixes bugs
found running real extensions (QuickJS results, R8-shrunk bytecode, regex
stack depth, WebView callbacks), removes the GPL-licensed NewPipe Extractor
and the `/youtube/resolve` route, and adds an optional bearer token, a bind
address, APKs kept across restarts, typed filter schemas, a log of the
browser checks being solved, and a headless Chromium for extensions that use
a WebView. Every change, and why it was made, is listed in
[`doc/FORK.md`](doc/FORK.md).

## Building

You need JDK 21.

```sh
./AndroidCompat/getAndroid.sh      # once: generates AndroidCompat/lib/android.jar
./gradlew :server:shadowJar        # server/build/MExtensionServer-v<version>-r<revision>.jar
```

With Docker and no JDK on the host, `scripts/test.sh` runs Gradle in a
throwaway JDK 21 container (and runs `getAndroid.sh` there if needed):

```sh
scripts/test.sh :server:shadowJar  # the jar
scripts/test.sh                    # the test suite
scripts/test.sh build              # lint, tests and the jar
```

Its Gradle cache defaults to `${XDG_CACHE_HOME:-~/.cache}/tsundoku-engine/gradle`;
set `GRADLE_CACHE` to keep it somewhere else. It grows to about a gigabyte.

## Running

```sh
java -jar server/build/MExtensionServer-*.jar <port> <app-dir>
```

- `<port>` is the port to listen on; without it the engine takes a free one
  and logs it.
- `<app-dir>` is the folder every file the engine writes goes under (source
  preferences, AndroidCompat's files, Chromium when `ENGINE_WEBVIEW` is on).
  Without it the engine uses Suwayomi's default data folder
  (`~/.local/share/Tachidesk` on Linux), which a Suwayomi server run by the
  same user also uses, so pass one.

`GET /` answers without a token and says the server is running.

With Docker Compose:

```sh
ENGINE_TOKEN=$(openssl rand -hex 32) docker compose up -d --build
```

The compose file refuses to start without `ENGINE_TOKEN`, and publishes the
engine on `127.0.0.1:8080` only. Inside the container the engine listens on
every interface, which is what a published port needs.

## Configuration

Every variable is optional. With none set, the engine behaves as upstream's
does.

| Variable               | Default                                          | What it does                                                                                                                               |
| ---------------------- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `ENGINE_TOKEN`         | unset: no authentication                         | Every route but `/` requires `Authorization: Bearer <token>`, and answers 401 without it.                                                  |
| `ENGINE_HOST`          | unset: every interface                           | The address to listen on, such as `127.0.0.1`.                                                                                             |
| `ENGINE_APK_DIR`       | unset: loaded APKs are kept in memory only       | A folder where loaded APKs are kept under their SHA-256 handle, so a handle still works after a restart.                                   |
| `ENGINE_WEBVIEW`       | off                                              | `on` (or `1`, `true`, `yes`) runs extensions' WebViews in a headless Chromium, downloaded the first time an extension needs it. See below. |
| `ENGINE_WEBVIEW_DIR`   | `<app-dir>/webview/chromium`                     | Where Chromium is downloaded to and run from (about 360 MB unpacked).                                                                      |
| `ENGINE_WEBVIEW_PAGES` | `2` (clamped to 1 to 8)                          | How many WebView pages may be open at once; one Chromium renderer each, and further pages wait for a slot.                                 |

The headless WebView needs an X display on Linux: the engine starts a
private Xvfb if `Xvfb` is on the `PATH`, and otherwise uses `DISPLAY`
(windowless). [`doc/FORK.md`](doc/FORK.md), "Headless WebView", has the
details, what has been tested, and what has not.

## Security

The engine runs code. `POST /dalvik` loads the extension APK it is sent and
executes it, and the `/image/` and `/video/` proxies fetch through each
extension's HTTP client and cookies. So:

- **With `ENGINE_TOKEN` and `ENGINE_HOST` unset, the engine is
  unauthenticated and listens on every interface**: anyone who can reach the
  port can run code on the machine as the engine's user, and `/stop` stops
  it. That is upstream's behaviour, and only acceptable on a network nobody
  else is on.
- Outside a private network, **always set `ENGINE_TOKEN`** (a long random
  value) **and `ENGINE_HOST=127.0.0.1`**, or publish the port to loopback
  only, as the compose file does. Put a TLS proxy in front if a client
  reaches it from another machine.
- **`ENGINE_WEBVIEW` runs Chromium 111 with `--no-sandbox`**
  (`AndroidCompat/src/main/java/xyz/nulldev/androidcompat/webkit/KcefChromium.kt:61`):
  KCEF's JCEF has no working sandbox, and a newer Chromium needs a newer KCEF
  than exists. Pages that extensions open, and the scripts on them, run
  unsandboxed with the engine user's rights in an old browser. Leave it off
  unless you need WebView extensions, and run the engine as a user, or in a
  container, that has nothing else worth taking.

## API

tsundoku is the client this fork is built for, and its
[`doc/EXTENSION-ENGINE.md`](https://github.com/masshirodev/tsundoku/blob/main/doc/EXTENSION-ENGINE.md)
documents the API it uses ("API reference"), including what this fork adds.

## Following upstream

The fork is pinned to an upstream commit and rebased onto newer ones by
hand; [`doc/FORK.md`](doc/FORK.md), "Following upstream", has the steps.
Fixes that upstream would want are kept as separate commits, to be sent
upstream as pull requests.

## Credits

The `AndroidCompat` module was originally developed by [@null-dev](https://github.com/null-dev) for [TachiWeb-Server](https://github.com/Tachiweb/TachiWeb-server) and is licensed under `Apache License Version 2.0`.

Parts of [Mihon (Tachiyomi)](https://github.com/mihonapp/mihon) is adopted into this codebase, also licensed under `Apache License Version 2.0`.

Parts of [Aniyomi](https://github.com/aniyomiorg/aniyomi) is adopted into this codebase, also licensed under `Apache License Version 2.0`.

You can obtain a copy of `Apache License Version 2.0` from  http://www.apache.org/licenses/LICENSE-2.0

Changes to both codebases is licensed under `MPL 2.0` as the rest of this project.

Upstream resolves YouTube streams with NewPipe Extractor (GPLv3). This fork
removes it (the `/youtube/resolve` route and its resolver), so its bundles
contain no GPL code; `/capabilities` reports `"youtubeResolver":false`.

## License

```
Copyright © 2024 Aniyomi Open Source Project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```

```
Copyright © 2024 Mihon Open Source Project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```

```
Copyright (C) Contributors to the Suwayomi project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
