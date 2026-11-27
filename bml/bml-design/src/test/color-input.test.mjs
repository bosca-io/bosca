import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/color-input.ts'], bundle: true, write: false, format: 'esm' });
const { colorInput } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
async function fixture(run) {
  const window = new Window(), keys = ['window', 'document', 'Element', 'HTMLElement', 'Event', 'FormData'];
  const original = new Map(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const key of keys) Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  document.body.innerHTML = '<form><div><label>Color<input name="color" data-color-field value="#12345678"></label><span data-color-current></span><button type="button" data-color-preset="#abcdef"><span data-color-value="#abcdef"></span>Preset</button></div></form>';
  const root = document.querySelector('form > div'), form = document.querySelector('form'), input = root.querySelector('input'), preset = root.querySelector('button'), cleanup = [];
  let update;
  colorInput({ root, on(target, name, listener) { target.addEventListener(name, listener); cleanup.push(() => target.removeEventListener(name, listener)); }, onUpdate(callback) { update = callback; }, onUnmount(callback) { cleanup.push(callback); } });
  const dispose = () => cleanup.splice(0).forEach(callback => callback());
  const edit = value => { input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); };
  try { await run({ window, root, form, input, preset, edit, update: () => update(), dispose }); }
  finally { dispose(); await window.happyDOM.abort(); for (const [key, value] of original) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key]; } }
}
test('empty, alpha and custom text values remain exact native form values', () => fixture(({ input, form, edit }) => {
  for (const value of ['', '#12345678', 'red', 'custom']) { edit(value); assert.equal(input.value, value); assert.equal(new FormData(form).get('color'), value); }
}));
test('preset selection emits native input and change once and returns focus to the value', () => fixture(({ input, preset, root }) => {
  const events = []; for (const name of ['input', 'change']) root.addEventListener(name, event => events.push([name, event.target]));
  preset.click(); assert.equal(input.value, '#abcdef'); assert.deepEqual(events, [['input', input], ['change', input]]); assert.equal(document.activeElement, input); assert.equal(preset.getAttribute('aria-pressed'), 'true');
}));
test('readonly and disabled controls reject programmatic preset clicks without changing submitted values', () => fixture(({ input, preset, update, form }) => {
  input.readOnly = true; update(); preset.click(); assert.equal(input.value, '#12345678'); assert.equal(new FormData(form).get('color'), '#12345678');
  input.readOnly = false; input.disabled = true; update(); preset.click(); assert.equal(input.value, '#12345678'); assert.equal(new FormData(form).get('color'), null);
  input.disabled = false; update(); assert.equal(preset.disabled, false);
}));
test('server updates and native reset repaint current fields without changing a typed draft', () => fixture(async ({ input, preset, form, edit, update }) => {
  edit('red'); update(); assert.equal(input.value, 'red'); assert.equal(preset.getAttribute('aria-pressed'), 'false');
  input.defaultValue = '#abcdef'; input.value = '#abcdef'; update(); assert.equal(preset.getAttribute('aria-pressed'), 'true');
  edit('blue'); form.reset(); await Promise.resolve(); assert.equal(input.value, '#abcdef'); assert.equal(preset.getAttribute('aria-pressed'), 'true');
}));
test('replacement fields become authoritative and unmount removes preset behavior', () => fixture(({ root, input, preset, update, dispose }) => {
  const next = input.cloneNode(true); next.value = 'red'; input.replaceWith(next); update(); preset.click(); assert.equal(next.value, '#abcdef'); assert.equal(input.value, '#12345678');
  dispose(); next.value = 'red'; preset.click(); assert.equal(next.value, 'red');
}));
