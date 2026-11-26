// Runs the headless check (README.md) in Chromium or Firefox through
// Playwright: the page twice in one profile, cold then warm (the warm run
// finds the extensions already converted under /files/), and prints each
// run's timings and walk.
//
//   node run.mjs [chromium|firefox] [--only=a,b] [--noLooper] [--runs=2] [--headed]
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import { chromium, firefox } from 'playwright';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { start, stats } from './server.mjs';

const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split('=')[1];
const browserName = process.argv.find((a) => a === 'chromium' || a === 'firefox') ?? 'chromium';
const runs = Number(arg('runs') ?? 2);
const query = new URLSearchParams();
if (arg('only')) query.set('only', arg('only'));
if (process.argv.includes('--noLooper')) query.set('noLooper', '');
if (arg('probe')) query.set('probe', arg('probe'));

const here = new URL('.', import.meta.url).pathname;
// The profile lives beside the check, not in /tmp: on some machines that is memory.
const profile = mkdtempSync(join(here, 'data', `profile-${browserName}-`));
const { server, url, results, reset } = await start();
const browserType = browserName === 'firefox' ? firefox : chromium;
const context = await browserType.launchPersistentContext(profile, { headless: !process.argv.includes('--headed') });

const all = [];
try {
	for (let run = 1; run <= runs; run++) {
		reset();
		const label = run === 1 ? 'cold' : `warm${run - 1}`;
		const before = { ...stats };
		const page = context.pages()[0] ?? (await context.newPage());
		page.on('console', (m) => {
			const text = m.text();
			if (text.startsWith('[check] ')) console.log(`${browserName} ${label} ${text.slice(8)}`);
			else if (process.env.VERBOSE) console.log(`${browserName} ${label} | ${text}`);
		});
		const t = Date.now();
		await page.goto(`${url}/?${query}`);
		await page.waitForFunction(() => document.title === 'done', null, { timeout: 30 * 60_000, polling: 500 });
		const r = results();
		all.push({
			browser: browserName,
			run: label,
			wallMs: Date.now() - t,
			jarBytes: stats.jarBytes - before.jarBytes,
			fetches: stats.fetches - before.fetches,
			...r
		});
		page.removeAllListeners('console');
		await page.goto('about:blank');
	}
} finally {
	await context.close();
	server.close();
	rmSync(profile, { recursive: true, force: true });
}
console.log(JSON.stringify(all, null, 1));
