# tsundoku-engine

A pinned fork of [M-Extension-Server](https://github.com/kodjodevf/M-Extension-Server)
that runs Mihon and Aniyomi extensions for [tsundoku](https://github.com/masshirodev/tsundoku).
What differs from upstream, and why: [`doc/FORK.md`](doc/FORK.md). Upstream's own
README follows.

---

# M-Extension-Server — headless Mihon (Tachiyomi)/Aniyomi extensions server

M-Extension-Server is a lightweight, headless service for running Mihon (Tachiyomi)/Aniyomi extensions (APKs). It dynamically loads extensions from Base64-encoded APKs, executes catalogue and content methods (manga/anime), and returns results via a small HTTP API.


## Desktop and iOS runtimes

The normal `:server:shadowJar` task builds the desktop server and retains
KCEF/JCEF plus its native desktop dependencies. Mangayomi's on-device iOS
bridge uses `-PiosRuntime=true`, which replaces the logging backend and
excludes Chromium, JCEF, JOGL, and other desktop-only runtime classes.

Both modes compile the same bridge and extension-compatibility code. Pull
requests build and test both variants. Each changed iOS server JAR requires a
new immutable `ios-runtime-v*` release and checksum. The separate embedded
OpenJDK release only needs rebuilding when its OpenJDK sources, patches, or
toolchain change.

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
