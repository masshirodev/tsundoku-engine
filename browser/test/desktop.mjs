// The same walk (walk.mjs) against the desktop engine, the shadow jar over
// HTTP, as tsundoku runs it today: the baseline the browser target's answers
// and timings are compared with.
//
//   node desktop.mjs <engine shadow jar> [--only=a,b]
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { walk, fromBase64 } from './walk.mjs';

const here = new URL('.', import.meta.url).pathname;
const jar = process.argv[2];
const only = process.argv.find((a) => a.startsWith('--only='))?.slice(7).split(',');
const port = 42192;
const appDir = mkdtempSync(join(here, 'data', 'desktop-'));
const engine = spawn('java', ['-jar', jar, String(port), appDir], { stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
engine.stdout.on('data', (d) => (output += d));
engine.stderr.on('data', (d) => (output += d));

// A frame handler over HTTP, so walk.mjs runs unchanged.
async function handle(head, bodyBase64) {
	const [method, path, ...lines] = head.split('\n');
	const headers = {};
	for (const line of lines) {
		const i = line.indexOf(':');
		if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
	}
	const res = await fetch(`http://127.0.0.1:${port}${path}`, { method, headers, body: bodyBase64 ? fromBase64(bodyBase64) : undefined });
	let frame = `${res.status}\n`;
	for (const [name, value] of res.headers) frame += `${name}: ${value}\n`;
	return frame + '\n' + Buffer.from(await res.arrayBuffer()).toString('base64');
}

try {
	const t = Date.now();
	for (;;) {
		if (Date.now() - t > 60_000) throw new Error('the engine did not start:\n' + output.slice(-2000));
		const up = await fetch(`http://127.0.0.1:${port}/`).then((r) => r.ok, () => false);
		if (up) break;
		await new Promise((r) => setTimeout(r, 200));
	}
	console.log(`engine up in ${Date.now() - t} ms`);
	const extensions = JSON.parse(readFileSync(join(here, 'extensions.json'), 'utf8')).filter((e) => !only || only.includes(e.name));
	for (const ext of extensions) {
		const apk = new Uint8Array(readFileSync(join(here, 'data', 'ext', `${ext.name}.apk`)));
		await walk(handle, ext, apk, console.log);
	}
} finally {
	engine.kill();
	rmSync(appDir, { recursive: true, force: true });
}
