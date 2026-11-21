import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const result = await build({ stdin: { contents: `export * from './src/main/client/helpers/document-images.ts';`, resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser' });
const { workOpsDocumentImages } = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const image = { id: 'prepared', label: 'Diagram', src: '/content/image/prepared', contentType: 'image/png' };
const target = { id: 'prepared', url: '/api/v1/content/metadata/upload?id=prepared', headers: JSON.stringify([{ name: 'Authorization', value: 'Bearer fixture' }]) };

async function fixture(run) {
  const window = new Window({ url: 'https://workops.test/' });
  for (const key of ['window', 'document', 'CustomEvent', 'Event', 'File', 'FormData', 'DOMException']) Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  const requests = [], cleanups = [];
  globalThis.XMLHttpRequest = class {
    upload = {}; headers = {};
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(name, value) { this.headers[name] = value; }
    send(body) { this.body = body; requests.push(this); }
    abort() { this.onabort?.(); }
    succeed() { this.status = 201; this.onload(); }
  };
  const root = document.createElement('div'); document.body.append(root);
  const ctx = { root, onUnmount: cb => cleanups.push(cb), on(node, type, cb) { node.addEventListener(type, cb); cleanups.push(() => node.removeEventListener(type, cb)); } };
  const file = new File(['png bytes'], 'diagram.png', { type: 'image/png' });
  const upload = (controller = new AbortController()) => {
    let response; const progress = [];
    root.dispatchEvent(new CustomEvent('imagerequest', { detail: { kind: 'upload', file, signal: controller.signal,
      progress: (...args) => progress.push(args), respondWith: value => { response = value; } } }));
    response.catch(() => {}); return { response, progress, controller };
  };
  try { await run({ root, ctx, upload, requests, file, unmount: () => cleanups.splice(0).forEach(cb => cb()) }); }
  finally { cleanups.forEach(cb => cb()); await window.happyDOM.abort(); }
}

test('cancel during prepare shares the pending draft, while completion retry reuses already uploaded bytes', () => fixture(async ({ ctx, upload, requests, file }) => {
  const prepared = deferred(), preparations = []; let completions = 0;
  workOpsDocumentImages(ctx, { prepare(...args) { preparations.push(args); return prepared.promise; }, async complete(id) {
    assert.equal(id, 'prepared'); if (++completions === 1) throw new Error('Ready temporarily unavailable'); return image;
  } });
  const first = upload(); first.controller.abort(); const retry = upload();
  assert.equal(preparations.length, 1); prepared.resolve(target); await tick();
  await assert.rejects(first.response, { name: 'AbortError' }); assert.equal(requests.length, 1);
  assert.equal(requests[0].url, target.url); assert.equal(requests[0].headers.Authorization, 'Bearer fixture');
  assert.equal(requests[0].body.get('file').name, file.name);
  requests[0].upload.onprogress({ lengthComputable: true, loaded: 5, total: 10 });
  assert.deepEqual(retry.progress[0], [50, 'uploading']); requests[0].succeed();
  await assert.rejects(retry.response, /Ready temporarily unavailable/);
  const finish = upload(); assert.deepEqual(await finish.response, image);
  assert.equal(preparations.length, 1); assert.equal(requests.length, 1); assert.equal(completions, 2);
}));

test('failed and cancelled byte transfers refresh the existing upload target and unmount aborts the active transfer', () => fixture(async ({ ctx, upload, requests, unmount }) => {
  const ids = [];
  workOpsDocumentImages(ctx, { async prepare(name, type, length, existingId) { ids.push(existingId); return target; }, async complete() { return image; } });
  const first = upload(); await tick(); requests[0].status = 403; requests[0].onload();
  await assert.rejects(first.response, /403/);
  const second = upload(); await tick(); assert.deepEqual(ids, ['', 'prepared']); second.controller.abort();
  await assert.rejects(second.response, { name: 'AbortError' });
  const third = upload(); await tick(); assert.deepEqual(ids, ['', 'prepared', 'prepared']); unmount();
  await assert.rejects(third.response, { name: 'AbortError' }); assert.equal(requests.length, 3);
}));
