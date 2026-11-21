import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom');
const { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/slug-input.ts'], bundle: true, write: false, format: 'esm' });
const { slugInput } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
async function fixture(check) {
  const window = new Window();
  for (const key of ['document', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'Event', 'CustomEvent']) globalThis[key] = window[key];
  document.body.innerHTML = '<form><input name="title"><div data-source-field="title"><input name="key"><input type="hidden" name="keyManual" data-slug-manual value="false"></div></form>';
  const form = document.querySelector('form'), root = form.querySelector('div'), source = form.elements.namedItem('title'), input = form.elements.namedItem('key'), marker = form.elements.namedItem('keyManual');
  const cleanup = []; let update;
  slugInput({ root, on: (node, name, fn) => { node.addEventListener(name, fn); cleanup.push(() => node.removeEventListener(name, fn)); }, onUpdate: fn => update = fn, onUnmount: fn => cleanup.push(fn) });
  const edit = (node, value) => { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); };
  try { await check({ form, root, source, input, marker, edit, update, dispose: () => cleanup.forEach(fn => fn()) }); }
  finally { cleanup.forEach(fn => fn()); window.happyDOM.cancelAsync(); }
}
test('suggestions follow the named source and keep their native marker automatic', () => fixture(({ source, input, marker, edit }) => {
  edit(source, '  Preview / API v2  '); assert.equal(input.value, 'preview-api-v2'); assert.equal(marker.value, 'false');
  edit(source, 'Development'); assert.equal(input.value, 'development'); edit(source, ''); assert.equal(input.value, '');
}));
test('manual edits including a cleared slug stop later suggestions', () => fixture(({ source, input, marker, edit }) => {
  edit(source, 'Testing'); edit(input, 'explicit'); edit(source, 'Production'); assert.equal(input.value, 'explicit'); assert.equal(marker.value, 'true');
  edit(input, ''); edit(source, 'Another name'); assert.equal(input.value, '');
}));
test('server updates restore manual state and ignore unrelated components', () => fixture(({ form, root, source, input, marker, edit, update }) => {
  edit(input, 'custom'); document.dispatchEvent(new CustomEvent('bml:update', { detail: { target: document.createElement('div') } }));
  edit(source, 'First'); assert.equal(input.value, 'custom');
  root.setAttribute('data-manual', ''); update(); edit(source, 'Second'); assert.equal(input.value, 'custom'); assert.equal(marker.value, 'true');
  root.removeAttribute('data-manual'); document.dispatchEvent(new CustomEvent('bml:update', { detail: { target: form } }));
  edit(source, 'Automatic again'); assert.equal(input.value, 'automatic-again'); assert.equal(marker.value, 'false');
}));
test('a replaced source and textarea resolve within the current form', () => fixture(({ form, source, input, edit }) => {
  const replacement = document.createElement('textarea'); replacement.name = 'title'; source.replaceWith(replacement);
  edit(source, 'Detached source'); assert.equal(input.value, ''); edit(replacement, 'Current source'); assert.equal(input.value, 'current-source');
  const other = document.createElement('form'); other.innerHTML = '<input name="title">'; document.body.append(other);
  edit(other.elements.namedItem('title'), 'Other form'); assert.equal(input.value, 'current-source'); assert.equal(input.form, form);
}));
test('disabled and readonly slugs retain their value', () => fixture(({ source, input, edit }) => {
  input.value = 'stored'; input.disabled = true; edit(source, 'New disabled name'); assert.equal(input.value, 'stored');
  input.disabled = false; input.readOnly = true; edit(source, 'New readonly name'); assert.equal(input.value, 'stored');
}));
test('native reset restores suggestions and unmount removes listeners', () => fixture(async ({ form, source, input, marker, edit, dispose }) => {
  edit(source, 'Testing'); edit(input, 'custom'); form.reset(); await Promise.resolve();
  assert.equal(marker.value, 'false'); edit(source, 'After reset'); assert.equal(input.value, 'after-reset');
  dispose(); edit(source, 'Unmounted'); assert.equal(input.value, 'after-reset');
}));
