// The walk the headless check runs through BrowserEngine.handle, for one
// extension: the calls tsundoku's client makes (popular, search, details,
// chapters, pages), as POST /dalvik frames, timed. It only needs a
// `handle(head, bodyBase64)` that answers a frame, so the worker (CheerpJ)
// and any other driver share it.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

const enc = new TextEncoder();
const dec = new TextDecoder();

export function toBase64(bytes) {
	let s = '';
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
	return btoa(s);
}

export function fromBase64(text) {
	const s = atob(text);
	const out = new Uint8Array(s.length);
	for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
	return out;
}

/** A frame `STATUS\nName: value\n...\n\nBASE64` as {status, headers, body}. */
export function parseFrame(frame) {
	const blank = frame.indexOf('\n\n');
	const lines = (blank < 0 ? frame : frame.slice(0, blank)).split('\n');
	const headers = {};
	for (const line of lines.slice(1)) {
		const i = line.indexOf(':');
		if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
	}
	return { status: Number(lines[0]), headers, body: blank < 0 ? new Uint8Array() : fromBase64(frame.slice(blank + 2)) };
}

// The engine uses the caller's User-Agent for a source's requests (as it does
// tsundoku's). Not a browser's: the check's relay fetches with Node's TLS, and
// a source may refuse a browser's User-Agent over a TLS that isn't one
// (MangaDex's API answers 403), which a real browser never shows.
const CHECK_UA = 'tsundoku-engine-check';

/** One POST /dalvik through the engine, its JSON answer and how long it took. */
async function dalvik(handle, body) {
	const t = performance.now();
	const frame = await handle(`POST\n/dalvik\nContent-Type: application/json\nUser-Agent: ${CHECK_UA}\n`, toBase64(enc.encode(JSON.stringify(body))));
	const ms = Math.round(performance.now() - t);
	const answer = parseFrame(String(frame));
	const text = dec.decode(answer.body);
	let json = null;
	try {
		json = JSON.parse(text);
	} catch {}
	return { ms, status: answer.status, json, text, handle: answer.headers['x-mangayomi-extension-id'] };
}

const short = (s, n = 70) => (s == null ? '' : String(s).replace(/\s+/g, ' ').slice(0, n));

/**
 * Walks one extension. `ext` is {name, kind, sourceId, query}; `apk` its
 * bytes. Every step is recorded, failures included, and the walk stops at
 * the first step that leaves nothing to walk on.
 */
export async function walk(handle, ext, apk, log = () => {}) {
	const steps = [];
	let extensionId = null;
	const base = () => (extensionId ? { extensionId } : { data: toBase64(apk) });
	const step = async (name, body, summarise) => {
		const r = await dalvik(handle, { ...base(), sourceId: ext.sourceId, ...body });
		if (r.handle) extensionId = r.handle;
		const ok = r.status === 200 && r.json != null;
		const summary = ok ? summarise(r.json) : `HTTP ${r.status} ${short(r.json?.error ?? r.text, 300)}`;
		steps.push({ name, ms: r.ms, ok, summary });
		log(`${ext.name.padEnd(12)} ${name.padEnd(9)} ${String(r.ms).padStart(6)} ms  ${ok ? '' : 'FAILED '}${summary}`);
		return ok ? r.json : null;
	};

	const popular = await step('popular', { method: 'getPopularManga', page: 1 }, (j) => `${j.mangas?.length} series, next ${j.hasNextPage}: ${short(j.mangas?.[0]?.title, 40)}`);
	await step('popular2', { method: 'getPopularManga', page: 2 }, (j) => `${j.mangas?.length} series: ${short(j.mangas?.[0]?.title, 40)}`);
	const search = await step('search', { method: 'getSearchManga', page: 1, search: ext.query, filterList: [] }, (j) => `${j.mangas?.length} series: ${short(j.mangas?.[0]?.title, 40)}`);
	const manga = popular?.mangas?.[0] ?? search?.mangas?.[0];
	if (!manga) return steps;
	await step('details', { method: 'getDetailsManga', mangaData: manga }, (j) => `${short(j.title, 30)} by ${short(j.author, 20)}, status ${j.status}`);
	const chapters = await step('chapters', { method: 'getChapterList', mangaData: manga }, (j) => `${j.length} chapters: ${short(j[0]?.name, 40)}`);
	// The oldest chapter: the newest is often locked or not out yet on official sources.
	const chapter = chapters?.[ext.chapter === 'first' ? 0 : chapters.length - 1];
	if (!chapter) return steps;
	const pages = await step('pages', { method: 'getPageList', chapterData: chapter }, (j) => `${j.pageList?.length ?? j.length} pages: ${short((j.pageList ?? j)[0]?.imageUrl ?? (j.pageList ?? j)[0]?.url, 60)}`);
	const first = (pages?.pageList ?? pages ?? [])[0];
	const imageUrl = first?.imageUrl ?? first?.url;
	// An image through the engine's own /image/ proxy, when it handed one out.
	const proxy = /\/image\/[^/?#]+$/.exec(imageUrl ?? '');
	if (proxy) {
		const t = performance.now();
		const answer = parseFrame(String(await handle(`GET\n${proxy[0]}\n`, '')));
		const ms = Math.round(performance.now() - t);
		const b = answer.body;
		const magic = [...b.slice(0, 4)].map((x) => x.toString(16).padStart(2, '0')).join('');
		const ok = answer.status === 200 && b.length > 100;
		const summary = `${answer.status} ${b.length} bytes ${answer.headers['content-type'] ?? ''} ${magic}`;
		steps.push({ name: 'image', ms, ok, summary });
		log(`${ext.name.padEnd(12)} ${'image'.padEnd(9)} ${String(ms).padStart(6)} ms  ${ok ? '' : 'FAILED '}${summary}`);
	}
	return steps;
}
