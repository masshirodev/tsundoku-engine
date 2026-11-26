# The browser target's headless check

A manual check, not part of `scripts/test.sh`: it reaches real sources. It
loads `engine-browser.jar` into CheerpJ in a dedicated worker, in headless
Chromium or Firefox, starts the engine with `BrowserEngine.start`, and walks
several extensions through `BrowserEngine.handle` with the calls tsundoku's
client makes: `getPopularManga` (two pages), `getSearchManga`,
`getDetailsManga`, `getChapterList`, `getPageList`, and one page image
through the engine's `/image/` proxy. The first call sends the APK, so it
also converts it with dex2jar, in the browser.

What it stands in for, and how:

- **The fetcher extension.** The worker's `fetchNative` posts each request
  frame to this server's `/__fetch`, which fetches it with Node's `fetch` (no
  CORS, any header) and answers the frame, with `X-Tsundoku-Final-Url`.
  Node's TLS is not a browser's, so the walk gives the engine a non-browser
  User-Agent: MangaDex's API refuses a browser's User-Agent over another TLS
  (403), which a real browser never shows.
- **The page's script runner.** The worker's `evaluateNative` runs an
  extension's JavaScript in a worker made from a `data:` URL, which has an
  opaque origin, with a 30 s limit (`page/worker.js`). That is also how the
  app should do it.

| File               | What                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------ |
| `extensions.json`  | The extensions walked, one per kind (plain HTML, JSON API, ProtoBuf, QuickJS), and their source |
| `walk.mjs`         | The walk, over any `handle(head, bodyBase64)`                                              |
| `page/worker.js`   | CheerpJ, the natives, the engine and the walk, in a dedicated worker                       |
| `page/index.html`  | Starts the worker and reports what it says                                                 |
| `server.mjs`       | Serves the page, the jar (with HTTP Range), the APKs; `/__fetch`; `/__results`             |
| `run.mjs`          | Playwright: a cold run and a warm one in the same profile, timings as JSON                 |
| `desktop.mjs`      | The same walk against the desktop shadow jar over HTTP: the baseline to compare with       |

## Running it

```sh
# the slim jar, from the repository's root
./gradlew :browser:slimJar && ./gradlew --stop

cd browser/test
npm install                      # Playwright; its browsers: npx playwright install chromium firefox
# the APKs, from Keiyoushi's index.json (each extension's apkUrl), into data/ext/<name>.apk
node run.mjs chromium            # cold, then warm: the extensions converted before are reused
node run.mjs firefox
node run.mjs chromium --only=mangahere --runs=1
node run.mjs chromium --probe=start      # start-up only
node run.mjs chromium --probe=quickjs    # QuickJs as extensions call it, no source
node run.mjs chromium --noLooper         # start without the Android main looper

# the baseline
(cd .. && cd .. && ./gradlew :server:shadowJar && ./gradlew --stop)
node desktop.mjs ../../server/build/MExtensionServer-*.jar
```

`data/` (gitignored) holds the APKs, the browser profiles while a run lasts,
and the desktop engine's folder. Keep it here rather than in `/tmp`, which
may be memory.

CheerpJ comes from its CDN, as its free licence requires, with the "Built
with CheerpJ" credit on the page.
