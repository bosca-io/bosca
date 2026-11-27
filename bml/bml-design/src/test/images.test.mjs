import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export * from './src/main/client/helpers/image-picker.ts'; export * from './src/main/client/helpers/document-images.ts';`, resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser' });
const { mountImagePicker, documentImageViews } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const image = id => ({ id, label: `Image ${id}`, src: `/content/image/${id}`, contentType: 'image/svg+xml' });

async function fixture(run) {
  const window = new Window({ url: 'https://images.test/document' });
  for (const key of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLButtonElement', 'CustomEvent', 'Event', 'KeyboardEvent', 'MutationObserver', 'AbortController', 'File', 'DOMException', 'location']) {
    Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  }
  const cleanups = [];
  const root = document.createElement('div'); document.body.append(root);
  root.innerHTML = `<div data-image-dialog>
    <select data-bml-ref="imageChoice"><option value=""></option><option value="one">One</option><option value="two">Two</option></select>
    <div data-bml-ref="imageDrop"></div>
    ${['upload', 'cancelUpload', 'apply', 'cancel', 'useUrl'].map(name => `<button type="button" data-bml-ref="image${name[0].toUpperCase()+name.slice(1)}">${name}</button>`).join('')}
    ${['url', 'alt', 'title'].map(name => `<input data-bml-ref="image${name[0].toUpperCase()+name.slice(1)}" />`).join('')}
    <div data-bml-ref="imagePreview"><img data-bml-ref="imageImage" /><p data-bml-ref="imageCaption"></p></div>
    <p data-bml-ref="imageStatus"></p><p data-bml-ref="imageFile"></p><div data-bml-ref="imageUploadProgress"><progress max="100"></progress></div>
  </div>`;
  const ref = name => root.querySelector(`[data-bml-ref="image${name[0].toUpperCase()+name.slice(1)}"]`);
  const ctx = { root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`), onUpdate() {}, onUnmount: cb => cleanups.push(cb), on(target, type, cb, options) {
    target.addEventListener(type, cb, options); cleanups.push(() => target.removeEventListener(type, cb, options));
  } };
  try { await run({ root, ref, ctx, unmount: () => cleanups.splice(0).forEach(cb => cb()) }); }
  finally { cleanups.forEach(cb => cb()); await window.happyDOM.abort(); }
}
const open = (root, callbacks = {}) => root.dispatchEvent(new CustomEvent('imagepickeropen', { detail: { select() {}, cancel() {}, ...callbacks } }));

test('image choices preserve alt and title, ignore replaced lookups and recheck access before insertion', () => fixture(async ({ root, ref, ctx }) => {
  const first = deferred(), requests = [], selected = [];
  root.addEventListener('imagerequest', event => { const request = event.detail; requests.push(request); request.respondWith(request.id === 'one' ? first.promise : image('two')); });
  mountImagePicker(ctx); open(root, { select: value => selected.push(value) });
  ref('choice').value = 'one'; ref('choice').dispatchEvent(new Event('change'));
  assert.equal(ref('cancelUpload').hidden, true, 'A lookup is not an upload');
  ref('choice').value = 'two'; ref('choice').dispatchEvent(new Event('change')); await tick();
  assert.equal(requests[0].signal.aborted, true);
  first.resolve(image('one')); await tick(); assert.equal(ref('caption').textContent, 'Image two');
  ref('alt').value = 'Useful description'; ref('title').value = 'Figure title';
  ref('apply').click(); await tick();
  assert.equal(requests.length, 3);
  assert.deepEqual(selected, [{ id: 'two', src: '/content/image/two', alt: 'Useful description', title: 'Figure title' }]);
  assert.equal(ref('image').getAttribute('src'), '/content/image/two');
}));

test('private selections remain open and fail explicitly without invoking the insertion callback', () => fixture(async ({ root, ref, ctx }) => {
  let allowed = true, inserted = false;
  root.addEventListener('imagerequest', event => event.detail.respondWith(allowed ? image('one') : null));
  mountImagePicker(ctx); open(root, { initial: { id: 'one', src: '/old', alt: '', title: '' }, select: () => { inserted = true; } }); await tick();
  allowed = false; ref('apply').click(); await tick();
  assert.equal(inserted, false); assert.match(ref('status').textContent, /unavailable/); assert.equal(ref('apply').disabled, true);
  assert.equal(ref('image').hasAttribute('src'), false);
}));

test('cancelled uploads can retry the same File and late completion cannot replace the newer selection', () => fixture(async ({ root, ref, ctx }) => {
  const first = deferred(), second = deferred(), requests = [], selected = [];
  root.addEventListener('imagerequest', event => {
    const request = event.detail;
    if (request.kind === 'upload') { requests.push(request); request.respondWith(requests.length === 1 ? first.promise : second.promise); }
    else request.respondWith(image('two'));
  });
  mountImagePicker(ctx); open(root, { select: value => selected.push(value) });
  const file = new File(['<svg/>'], 'diagram.svg', { type: 'image/svg+xml' });
  ref('drop').dispatchEvent(new CustomEvent('filesselected', { detail: [file] })); ref('upload').click();
  assert.equal(ref('upload').disabled, true); requests[0].progress(35, 'uploading'); assert.equal(root.querySelector('progress').value, 35);
  ref('cancelUpload').click(); assert.equal(requests[0].signal.aborted, true); assert.equal(ref('upload').disabled, false);
  ref('upload').click(); assert.equal(requests[1].file, file);
  requests[1].progress(100, 'processing'); assert.equal(root.querySelector('progress').hasAttribute('value'), false);
  second.resolve(image('two')); await tick(); first.resolve(image('one')); await tick();
  assert.equal(ref('caption').textContent, 'Image two'); assert.equal(ref('upload').disabled, true);
  assert.deepEqual(selected, [], 'upload does not insert until confirmed'); ref('apply').click(); await tick(); assert.equal(selected[0].id, 'two');
}));

test('disabling or unmounting an open picker aborts its requests and prevents late insertion', () => fixture(async ({ root, ref, ctx, unmount }) => {
  const pending = deferred(); let request, cancelled = 0, inserted = 0;
  root.addEventListener('imagerequest', event => { request = event.detail; request.respondWith(pending.promise); });
  mountImagePicker(ctx); open(root, { initial: { id: 'one', src: '/old', alt: '', title: '' }, select: () => inserted++, cancel: () => cancelled++ });
  root.setAttribute('data-disabled', ''); await tick(); assert.equal(request.signal.aborted, true); assert.equal(cancelled, 1);
  pending.resolve(image('one')); await tick(); assert.equal(inserted, 0); assert.equal(ref('apply').disabled, true);
  root.removeAttribute('data-disabled'); await tick(); open(root, { cancel: () => cancelled++ }); unmount(); assert.equal(cancelled, 2);
}));

test('image search forwards offset and query, cancels stale pages and preserves paging controls', () => fixture(async ({ root, ctx }) => {
  const pending = deferred(), requests = [], received = [], errors = [];
  root.addEventListener('imagerequest', event => { requests.push(event.detail); event.detail.respondWith(requests.length === 1 ? pending.promise : { items: [image('two')], previousOffset: 0, nextOffset: 50 }); });
  mountImagePicker(ctx); open(root);
  const dialog = root.querySelector('[data-image-dialog]');
  const search = (query, offset) => dialog.dispatchEvent(new CustomEvent('selectsearch', { bubbles: true, detail: { query, offset, resolve: page => received.push(page), reject: error => errors.push(error) } }));
  search('first', 0); search('second', 25); await tick(); pending.resolve({ items: [image('one')] }); await tick();
  assert.equal(requests[0].signal.aborted, true); assert.equal(requests[1].query, 'second'); assert.equal(requests[1].offset, 25);
  assert.deepEqual(received, [{ options: [{ value: 'two', label: 'Image two' }], previousOffset: 0, nextOffset: 50 }]); assert.equal(errors[0].name, 'AbortError');
}));

test('URL mode validates input and upload failures retain the chosen file for retry', () => fixture(async ({ root, ref, ctx }) => {
  root.addEventListener('imagerequest', event => event.detail.respondWith(Promise.reject(new Error('Storage unavailable. Retry this file.'))));
  mountImagePicker(ctx); const selected = []; open(root, { select: value => selected.push(value) });
  ref('url').value = 'javascript:alert(1)'; ref('useUrl').click(); assert.equal(ref('apply').disabled, true);
  ref('url').value = '/image.png'; ref('useUrl').click(); assert.equal(ref('apply').disabled, false);
  ref('drop').dispatchEvent(new CustomEvent('filesselected', { detail: [new File(['x'], 'image.avif', { type: 'image/avif' })] }));
  ref('upload').click(); await tick(); assert.match(ref('status').textContent, /Storage unavailable/); assert.equal(ref('upload').disabled, false);
  ref('apply').click(); await tick(); assert.equal(selected[0].src, '/image.png');
}));

test('stored Content images resolve under the viewer, keep document attributes and ignore stale results', () => fixture(async ({ root }) => {
  root.replaceChildren(); const requests = [], first = deferred();
  root.addEventListener('imagerequest', event => { requests.push(event.detail); event.detail.respondWith(event.detail.id === 'one' ? first.promise : null); });
  const views = documentImageViews(root), type = {}, attrs = { metadataId: 'one', src: '/stale-private-image', alt: 'Meaningful', title: 'Figure', class: 'wide' };
  const view = views.image({ type, attrs }); root.append(view.dom);
  assert.equal(view.dom.querySelector('img').hasAttribute('src'), false);
  assert.equal(view.dom.querySelector('img').alt, 'Meaningful');
  view.update({ type, attrs: { ...attrs, metadataId: 'two' } }); await tick();
  assert.equal(requests[0].signal.aborted, true); assert.match(view.dom.textContent, /unavailable/);
  first.resolve(image('one')); await tick(); assert.equal(view.dom.querySelector('img').hasAttribute('src'), false);
  assert.equal(attrs.src, '/stale-private-image'); views.reload(); assert.equal(requests.length, 3); view.destroy();
  assert.equal(requests[2].signal.aborted, true);
}));

test('Enter keeps text fields inside the picker without intercepting native file selection or IME composition', () => fixture(({ root, ref, ctx }) => {
  const file = document.createElement('input'); file.type = 'file'; ref('drop').append(file);
  mountImagePicker(ctx); open(root);
  const press = (target, isComposing = false) => {
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, isComposing }); target.dispatchEvent(event); return event.defaultPrevented;
  };
  assert.equal(press(ref('alt')), true); assert.equal(press(file), false); assert.equal(press(ref('alt'), true), false);
}));
