// The engine in a dedicated worker, driven as the app will drive it: CheerpJ
// from its CDN, the slim jar, BrowserEngine.start, then the walk
// (../walk.mjs) through BrowserEngine.handle for each extension. The native
// the engine fetches through posts each request to the server's /__fetch.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

const t0 = performance.now();
const say = (type, data) => self.postMessage({ type, at: Math.round(performance.now() - t0), ...data });
const log = (line) => say('log', { line });

// tsundoku.browser.BrowserTransport.fetchNative: the engine's one door to the network.
async function Java_tsundoku_browser_BrowserTransport_fetchNative(lib, head, body) {
	try {
		const res = await fetch('/__fetch', { method: 'POST', body: JSON.stringify({ head, body }) });
		return await res.text();
	} catch (e) {
		return `ERR ${e?.message ?? 'the relay failed'}`;
	}
}

// tsundoku.browser.BrowserScripts.evaluateNative: an extension's JavaScript,
// run by the browser's own engine in a worker made from a data: URL, which
// has an opaque origin: none of the app's storage, cookies or fetcher. A
// script that runs too long has its worker thrown away.
const SANDBOX_SOURCE = `onmessage = ({ data }) => {
	let answer;
	try {
		const value = (0, eval)(data);
		answer = JSON.stringify(value === undefined ? {} : { value });
	} catch (e) {
		answer = JSON.stringify({ error: String((e && e.message) || e) });
	}
	postMessage(answer);
};`;
const SCRIPT_LIMIT_MS = 30_000;
let sandbox = null;
const scriptQueue = [];
function evaluateInSandbox(script) {
	return new Promise((resolve) => {
		scriptQueue.push({ script, resolve });
		if (scriptQueue.length === 1) runNextScript();
	});
}
function runNextScript() {
	const job = scriptQueue[0];
	if (!job) return;
	sandbox ??= new Worker('data:text/javascript,' + encodeURIComponent(SANDBOX_SOURCE));
	const done = (answer) => {
		clearTimeout(timer);
		sandbox.onmessage = sandbox.onerror = null;
		scriptQueue.shift();
		job.resolve(answer);
		runNextScript();
	};
	const timer = setTimeout(() => {
		sandbox.terminate();
		sandbox = null;
		done(JSON.stringify({ error: `The script ran longer than ${SCRIPT_LIMIT_MS / 1000} s` }));
	}, SCRIPT_LIMIT_MS);
	sandbox.onmessage = ({ data }) => done(data);
	sandbox.onerror = (e) => {
		e.preventDefault?.();
		done(JSON.stringify({ error: e.message || 'The script failed' }));
	};
	sandbox.postMessage(job.script);
}
async function Java_tsundoku_browser_BrowserScripts_evaluateNative(lib, script) {
	return evaluateInSandbox(script);
}

// A Java exception reaching here is a Java object; String() on it throws.
async function text(e) {
	try {
		return e instanceof Error ? `${e.message}\n${e.stack}` : String(e);
	} catch {
		try {
			return 'Java ' + (await e.toString());
		} catch {
			return 'unprintable ' + Object.prototype.toString.call(e);
		}
	}
}

self.onmessage = async ({ data: params }) => {
	const timings = {};
	const mark = async (name, run) => {
		const t = performance.now();
		const value = await run();
		timings[name] = Math.round(performance.now() - t);
		log(`${name.padEnd(12)} ${String(timings[name]).padStart(6)} ms ${typeof value === 'string' ? value.slice(0, 200) : ''}`);
		return value;
	};
	const results = { timings, walks: [], error: null };
	try {
		const { walk } = await import('/walk.mjs');
		const extensions = (await (await fetch('/extensions.json')).json()).filter((e) => !params.only || params.only.includes(e.name));
		importScripts('https://cjrtnc.leaningtech.com/5.0/loader.js');
		await mark('cheerpjInit', () => cheerpjInit({ version: 21, natives: { Java_tsundoku_browser_BrowserTransport_fetchNative, Java_tsundoku_browser_BrowserScripts_evaluateNative } }));
		const lib = await mark('runLibrary', () => cheerpjRunLibrary('/app/engine-browser.jar'));
		const Engine = await mark('class', () => lib.tsundoku.browser.BrowserEngine);
		if (params.noLooper) await Engine.setMainLooper(false);
		const started = await mark('start', () => Engine.start('/files/engine', params.origin));
		if (!String(started).startsWith('{"ok"')) throw new Error('start: ' + started);
		results.start = JSON.parse(started);
		// Start-up only, for measuring it.
		if (params.probe === 'start') throw new Error('probe done');
		if (params.probe === 'quickjs') {
			// QuickJs as extensions call it (the browser target's, which runs
			// the script in the sandbox above), without a source.
			const QuickJs = await mark('quickjsClass', () => lib.app.cash.quickjs.QuickJs);
			const q = await mark('quickjsCreate', () => QuickJs.create());
			for (let i = 1; i <= 3; i++) await mark(`evaluate${i}`, async () => String(await (await q.evaluate('1 + 1')).toString()));
			throw new Error('probe done');
		}
		const handle = (head, body) => Engine.handle(head, body);
		await mark('capabilities', () => handle('GET\n/capabilities\n', ''));
		for (const ext of extensions) {
			const apk = new Uint8Array(await (await fetch(`/ext/${ext.name}.apk`)).arrayBuffer());
			const t = performance.now();
			const steps = await walk(handle, ext, apk, log);
			results.walks.push({ ...ext, totalMs: Math.round(performance.now() - t), steps });
		}
	} catch (e) {
		results.error = await text(e);
		log('ERROR ' + results.error);
	}
	results.totalMs = Math.round(performance.now() - t0);
	say('done', { results });
};
