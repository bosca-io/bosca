import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom');
const { build } = require('esbuild');
const bundle = await build({ stdin: { contents: 'export * from "./src/main/client/helpers/schema-form-choices.ts"; export * from "./src/main/client/helpers/file-field.ts"; export * from "./src/main/client/helpers/schema-form.ts";', resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser', loader: { '.css': 'empty' } });
const api = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const settle = () => new Promise(resolve => setTimeout(resolve, 0));
async function fixture(markup, run) {
  const window = new Window({ url: 'http://forms.test/' });
  for (const name of ['window', 'document', 'Element', 'Node', 'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLTextAreaElement', 'HTMLButtonElement', 'HTMLFormElement', 'CustomEvent', 'Event', 'MutationObserver', 'FileReader', 'File']) globalThis[name] = window[name];
  document.body.innerHTML = markup;
  const cleanup = [], updates = [];
  const context = root => ({ root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`), on: (target, name, listener, options) => { target.addEventListener(name, listener, options); cleanup.push(() => target.removeEventListener(name, listener, options)); }, onUpdate: listener => updates.push(listener), onUnmount: listener => cleanup.push(listener) });
  const unmount = () => cleanup.splice(0).forEach(listener => listener());
  try { await run({ context, unmount, update: () => updates.forEach(listener => listener()) }); }
  finally { unmount(); await window.happyDOM.abort(); }
}
const choices = '<div data-bml-schema-form><div data-schema-control="remote-select" data-schema-field-key="form-1" data-schema-property="owner"><div data-bml-design-select data-select-ready></div><p data-schema-choices-status></p><button data-schema-choices-retry hidden>Retry</button></div></div>';
test('remote choices recover from late adapters and ignore aborted responses', () => fixture(choices, async ({ context, unmount }) => {
  const root = document.querySelector('[data-schema-field-key]'), form = root.parentElement;
  api.schemaFormChoices(context(root)); await settle(); assert.match(root.textContent, /unavailable/);
  const requests = [], offered = [];
  form.addEventListener('schemaformchoicesrequest', event => { const request = event.detail; requests.push(request); request.respondWith(new Promise(resolve => request.finish = resolve)); });
  root.querySelector('[data-bml-design-select]').addEventListener('selectoptions', event => offered.push(event.detail));
  form.dispatchEvent(new Event('schemaformchoicesready'));
  assert.equal(requests[0].fieldKey, 'form-1'); assert.equal(requests[0].query, null);
  const search = query => new Promise((resolve, reject) => root.querySelector('[data-bml-design-select]').dispatchEvent(new CustomEvent('selectsearch', { detail: { query, offset: 25, resolve, reject } })));
  const next = search('new'); assert.equal(requests[0].signal.aborted, true);
  requests[1].finish({ options: [{ value: 'new', label: 'New choice' }], previousOffset: 0 }); assert.equal((await next).previousOffset, 0);
  requests[0].finish({ options: [{ value: 'stale', label: 'Old choice' }] }); await settle(); assert.deepEqual(offered, [[{ value: 'new', label: 'New choice' }]]);
  const last = search('close').catch(error => error);
  unmount(); assert.equal(requests[2].signal.aborted, true);
  requests[2].finish({ options: [] }); assert.equal((await last).name, 'AbortError');
}));
test('remote lookup errors remain visible and retry restores available choices', () => fixture(choices, async ({ context }) => {
  const root = document.querySelector('[data-schema-field-key]'); let fails = true;
  root.parentElement.addEventListener('schemaformchoicesrequest', event => event.detail.respondWith(fails ? Promise.reject(new Error('Access changed')) : { options: [] }));
  api.schemaFormChoices(context(root)); await settle(); assert.match(root.textContent, /Access changed/); assert.equal(root.querySelector('button').hidden, false);
  assert.equal(root.querySelector('[data-schema-choices-status]').hidden, false);
  fails = false; root.querySelector('button').click(); await settle(); assert.equal(root.querySelector('[data-schema-choices-status]').textContent, ''); assert.equal(root.querySelector('button').hidden, true);
  assert.equal(root.querySelector('[data-schema-choices-status]').hidden, true);
}));
const files = '<form><div data-bml-schema-form><input type="hidden" name="attributes" data-bml-ref="value"><p data-bml-ref="feedback" hidden></p><div data-schema-property="attachment" data-schema-control="file-upload"><div data-file-field data-image><input type="hidden" data-file-field-value value="null"><img data-bml-ref="filePreview"><div data-bml-ref="fileDrop"><input type="file"></div><p data-bml-ref="fileStatus"></p><progress data-bml-ref="fileProgress"></progress><button type="button" data-bml-ref="fileCancel">Cancel</button><button type="button" data-bml-ref="fileClear">Clear</button></div><p data-schema-error></p></div></div></form>';
test('retry repeats the failed search and offset', () => fixture(choices, async ({ context }) => {
  const root = document.querySelector('[data-schema-field-key]'), requests = [];
  let fail = true;
  root.parentElement.addEventListener('schemaformchoicesrequest', event => {
    const request = event.detail; requests.push([request.query, request.offset]);
    request.respondWith(request.query === 'kept query' && fail ? Promise.reject(new Error('Unavailable')) : { options: [] });
  });
  api.schemaFormChoices(context(root)); await settle();
  await new Promise(resolve => root.querySelector('[data-bml-design-select]').dispatchEvent(new CustomEvent('selectsearch', { detail: { query: 'kept query', offset: 25, resolve, reject: resolve } })));
  fail = false; root.querySelector('button').click(); await settle();
  assert.deepEqual(requests, [[null, 0], ['kept query', 25], ['kept query', 25]]);
  assert.equal(root.querySelector('[data-schema-choices-status]').textContent, '');
}));
test('file fields read data URLs, clear to null and retain drafts across parent updates', () => fixture(files, async ({ context, update }) => {
  const schema = document.querySelector('[data-bml-schema-form]'), root = document.querySelector('[data-file-field]');
  schema.dataset.schema = JSON.stringify({ type: 'object', properties: { attachment: { type: ['string', 'null'] } } }); schema.dataset.value = '{}'; root.dataset.value = 'null';
  api.mountSchemaForm(context(schema)); api.fileField(context(root));
  root.querySelector('[data-bml-ref=fileDrop]').dispatchEvent(new CustomEvent('filesselected', { detail: [new File(['hello'], 'note.txt', { type: 'text/plain' })] }));
  const submit = new Event('submit', { cancelable: true }); schema.closest('form').dispatchEvent(submit); assert.equal(submit.defaultPrevented, true);
  await window.happyDOM.waitUntilComplete();
  const data = JSON.parse(schema.querySelector('[name=attributes]').value); assert.equal(data.attachment, 'data:text/plain;base64,aGVsbG8=');
  update(); assert.equal(JSON.parse(root.querySelector('[data-file-field-value]').value), data.attachment);
  root.querySelector('[data-bml-ref=fileClear]').click(); assert.equal(JSON.parse(schema.querySelector('[name=attributes]').value).attachment, null); assert.equal(root.querySelector('img').hidden, true);
}));
test('cancelling a read clears a previous busy-submission error without changing the draft', () => fixture(files, async ({ context }) => {
  globalThis.FileReader = class { readAsDataURL() {} abort() {} };
  const schema = document.querySelector('[data-bml-schema-form]'), root = document.querySelector('[data-file-field]');
  schema.dataset.schema = JSON.stringify({type:'object',properties:{attachment:{type:'string'}}});
  schema.dataset.value = JSON.stringify({attachment:'previous'}); root.dataset.value='"previous"';
  api.mountSchemaForm(context(schema)); api.fileField(context(root));
  root.querySelector('[data-bml-ref=fileDrop]').dispatchEvent(new CustomEvent('filesselected',{detail:[new File(['new'],'new.txt')]}));
  const submit=new Event('submit',{cancelable:true}); schema.closest('form').dispatchEvent(submit);
  assert.equal(submit.defaultPrevented,true); assert.match(schema.querySelector('[data-bml-ref=feedback]').textContent,/finish reading/);
  root.querySelector('[data-bml-ref=fileCancel]').click();
  assert.equal(schema.querySelector('[data-bml-ref=feedback]').hidden,true);
  assert.equal(JSON.parse(schema.querySelector('[name=attributes]').value).attachment,'previous');
}));
test('file reads stop on replacement, readonly and unmount without losing the previous value', () => fixture(files, async ({ context, unmount, update }) => {
  const readers = [];
  globalThis.FileReader = class { constructor() { readers.push(this); } readAsDataURL() {} abort() { this.aborted = true; } };
  const root = document.querySelector('[data-file-field]'); root.dataset.value = '"previous"'; root.querySelector('[data-file-field-value]').value = '"previous"'; api.fileField(context(root));
  const choose = () => root.querySelector('[data-bml-ref=fileDrop]').dispatchEvent(new CustomEvent('filesselected', { detail: [new File(['new'], 'new.txt')] }));
  choose(); choose(); assert.equal(readers[0].aborted, true); readers[0].result = 'stale'; readers[0].onload(); assert.equal(root.querySelector('[data-file-field-value]').value, '"previous"');
  readers[1].onerror(); assert.match(root.textContent, /Could not read/); assert.equal(root.hasAttribute('data-file-field-busy'), false);
  choose(); root.setAttribute('data-readonly', ''); update(); assert.equal(readers[2].aborted, true); assert.equal(root.querySelector('[data-bml-ref=fileClear]').disabled, true);
  root.removeAttribute('data-readonly'); update(); choose(); unmount(); assert.equal(readers[3].aborted, true);
}));
