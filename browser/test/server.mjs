// The headless check's server (README.md): the page and worker, the slim jar
// (with HTTP Range, which CheerpJ reads jars by), the extensions' APKs, and
// /__fetch, the CORS-free route the worker's native sends the engine's
// requests to. It stands in for the fetcher extension: Node's fetch has no
// CORS and sets any header, as the extension does. Results come back on
// /__results.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import { createServer } from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';

const here = new URL('.', import.meta.url).pathname;
const jar = process.env.ENGINE_JAR ?? join(here, '../build/libs/engine-browser.jar');
const port = Number(process.env.PORT ?? 42191);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.jar': 'application/java-archive' };

export const stats = { fetches: 0, fetchMs: 0, jarBytes: 0 };
let results = null;

function fileFor(path) {
	if (path === '/engine-browser.jar') return jar;
	if (path === '/walk.mjs' || path === '/extensions.json') return join(here, path);
	const dir = join(here, path.startsWith('/ext/') ? 'data' : 'page');
	const file = normalize(join(dir, path.startsWith('/ext/') ? path : path === '/' ? 'index.html' : path));
	return file.startsWith(dir + '/') ? file : null;
}

async function readBody(req) {
	const chunks = [];
	for await (const c of req) chunks.push(c);
	return Buffer.concat(chunks);
}

/** One of the engine's requests, a frame in and a frame out. */
async function relay(head, bodyBase64) {
	const t = Date.now();
	const lines = head.split('\n');
	const [method, url] = lines;
	const headers = new Headers();
	for (const line of lines.slice(2)) {
		const i = line.indexOf(':');
		if (i > 0) headers.append(line.slice(0, i).trim(), line.slice(i + 1).trim());
	}
	try {
		const res = await fetch(url, {
			method,
			headers,
			body: bodyBase64 ? Buffer.from(bodyBase64, 'base64') : undefined,
			redirect: 'follow',
			signal: AbortSignal.timeout(60_000)
		});
		const body = Buffer.from(await res.arrayBuffer());
		let frame = `${res.status}\n`;
		for (const [name, value] of res.headers) frame += `${name}: ${value.replace(/[\r\n]+/g, ' ')}\n`;
		// Where the browser's fetch ended up, as the real fetcher reports it.
		frame += `X-Tsundoku-Final-Url: ${res.url}\n`;
		return frame + '\n' + body.toString('base64');
	} catch (e) {
		return `ERR ${e.name}: ${e.message}`;
	} finally {
		stats.fetches++;
		stats.fetchMs += Date.now() - t;
		if (process.env.TRACE) console.log(`[fetch] ${method} ${url.slice(0, 100)} ${Date.now() - t} ms`);
	}
}

export function start() {
	return new Promise((resolve) => {
		const server = createServer(async (req, res) => {
			const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
			if (path === '/__fetch' && req.method === 'POST') {
				const { head, body } = JSON.parse((await readBody(req)).toString());
				res.setHeader('Content-Type', 'text/plain');
				return res.end(await relay(head, body));
			}
			if (path === '/__results') {
				if (req.method === 'POST') {
					results = JSON.parse((await readBody(req)).toString());
					return res.end('ok');
				}
				res.setHeader('Content-Type', 'application/json');
				return res.end(JSON.stringify(results));
			}
			const file = fileFor(path);
			if (!file || !existsSync(file) || !statSync(file).isFile()) {
				res.statusCode = 404;
				return res.end();
			}
			const size = statSync(file).size;
			res.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream');
			res.setHeader('Accept-Ranges', 'bytes');
			res.setHeader('Cache-Control', 'no-cache');
			let start = 0;
			let end = size - 1;
			const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range ?? '');
			if (range) {
				start = range[1] ? Number(range[1]) : size - Number(range[2]);
				end = range[1] && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
				res.statusCode = 206;
				res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
			}
			res.setHeader('Content-Length', end - start + 1);
			if (req.method === 'HEAD') return res.end();
			if (file === jar) stats.jarBytes += end - start + 1;
			createReadStream(file, { start, end }).pipe(res);
		});
		server.listen(port, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${port}`, results: () => results, reset: () => (results = null) }));
	});
}

if (import.meta.url === `file://${process.argv[1]}`) {
	const { url } = await start();
	console.log(`headless check on ${url}/`);
}
