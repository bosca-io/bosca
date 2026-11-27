import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom');
const { build } = require('esbuild');
const bundle = await build({ stdin: { contents: 'export * from "./src/main/client/helpers/dialog.ts";', resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser' });
const { modalDialog } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);

test('programmatic dialog events open and close only their target and notify a containing picker once', async () => {
  const window = new Window({ url: 'http://dialogs.test/' });
  for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'MouseEvent']) globalThis[name] = window[name];
  document.body.innerHTML = '<div id="root"><dialog><input id="draft" /></dialog></div>';
  const root = document.getElementById('root'), panel = root.querySelector('dialog'), cleanups = [], notifications = [];
  panel.close = () => panel.removeAttribute('open'); panel.showModal = () => panel.setAttribute('open', '');
  const matches = panel.matches.bind(panel); panel.matches = selector => selector === ':modal' ? panel.open : matches(selector);
  root.addEventListener('dialogopened', () => notifications.push('opened')); root.addEventListener('dialogclosed', () => notifications.push('closed'));
  modalDialog({ root, scoped: name => name, ref: () => null, onUpdate() {}, onUnmount: cb => cleanups.push(cb),
    on(target, type, listener, options) { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); } });
  try {
    document.getElementById('draft').dispatchEvent(new Event('dialogopen', { bubbles: true })); assert.equal(panel.open, false);
    root.dispatchEvent(new Event('dialogopen')); root.dispatchEvent(new Event('dialogopen')); assert.equal(panel.open, true);
    root.dispatchEvent(new Event('dialogclose')); root.dispatchEvent(new Event('dialogclose')); assert.equal(panel.open, false);
    root.dispatchEvent(new Event('dialogopen')); assert.equal(panel.open, true);
    assert.deepEqual(notifications, ['opened', 'closed', 'opened']);
  } finally { cleanups.forEach(callback => callback()); await window.happyDOM.abort(); }
});

test('completed closed siblings leave focus alone while an open dialog returns to its caller', async () => {
  const window = new Window({ url: 'http://dialogs.test/' });
  for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'MouseEvent']) globalThis[name] = window[name];
  document.body.innerHTML = `<div data-bml-island>
    <input id="outside" /><button id="edit">Edit task</button><button id="other">Other action</button>
    <div id="first" data-completed data-return-focus="#edit"><dialog><input id="draft" /></dialog></div>
    <div id="second" data-completed data-return-focus="#other"><dialog><input /></dialog></div>
  </div>`;
  const cleanups = [];
  const mount = id => {
    const root = document.getElementById(id), panel = root.querySelector('dialog'), updates = [];
    panel.close = () => panel.removeAttribute('open');
    panel.showModal = () => panel.setAttribute('open', '');
    const matches = panel.matches.bind(panel);
    panel.matches = selector => selector === ':modal' ? panel.open : matches(selector);
    const ctx = { root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`), scoped: name => `${id}-${name}`,
      onUpdate: callback => updates.push(callback), onUnmount: callback => cleanups.push(callback),
      on: (target, type, listener, options) => { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); } };
    modalDialog(ctx);
    return { root, panel, update: () => updates.forEach(callback => callback()) };
  };
  try {
    document.getElementById('outside').focus();
    const first = mount('first'), second = mount('second');
    assert.equal(document.activeElement.id, 'outside', 'idle completion must not move focus');
    first.root.removeAttribute('data-completed');
    first.root.setAttribute('data-force-open', '');
    first.update();
    assert.equal(first.panel.open, true);
    document.getElementById('draft').focus();
    first.root.removeAttribute('data-force-open');
    first.root.setAttribute('data-completed', '');
    first.update(); second.update();
    assert.equal(first.panel.open, false);
    assert.equal(document.activeElement.id, 'edit');
    document.dispatchEvent(new CustomEvent('bml:update', { detail: { target: document.body } }));
    assert.equal(document.activeElement.id, 'edit');
  } finally { cleanups.forEach(callback => callback()); await window.happyDOM.abort(); }
});

test('replacing an open dialog returns to its declared current trigger and leaves a reopened modal focused', async () => {
  const window = new Window({ url: 'http://dialogs.test/' });
  for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'MouseEvent']) globalThis[name] = window[name];
  document.body.innerHTML = '<div data-bml-island><button id="edit">Edit</button><div id="old" data-force-open data-return-focus="#edit"><dialog><input /></dialog></div></div>';
  const owner = document.body.firstElementChild, cleanups = [];
  const modal = panel => {
    panel.close = () => panel.removeAttribute('open'); panel.showModal = () => panel.setAttribute('open', '');
    const matches = panel.matches.bind(panel); panel.matches = selector => selector === ':modal' ? panel.open : matches(selector);
  };
  const mount = root => {
    modal(root.querySelector('dialog'));
    const callbacks = [];
    modalDialog({ root, scoped: name => root.id + name, ref: () => null, onUpdate() {}, onUnmount: cb => callbacks.push(cb),
      on: (target, type, listener, options) => { target.addEventListener(type, listener, options); callbacks.push(() => target.removeEventListener(type, listener, options)); } });
    cleanups.push(...callbacks);
    return () => { callbacks.forEach(cb => cb()); callbacks.length = 0; };
  };
  try {
    const first = owner.querySelector('#old'), removeFirst = mount(first);
    owner.querySelector('#edit').replaceWith(Object.assign(document.createElement('button'), { id: 'edit', textContent: 'Current trigger' }));
    first.remove(); removeFirst(); await Promise.resolve();
    assert.equal(document.activeElement, owner.querySelector('#edit'));
    owner.insertAdjacentHTML('beforeend', '<div id="other" data-force-open data-return-focus="#edit"><dialog><input /></dialog></div>');
    const second = owner.querySelector('#other'), removeSecond = mount(second);
    second.remove();
    owner.insertAdjacentHTML('beforeend', '<dialog open id="reopened"><input id="reopened-field" /></dialog>');
    modal(owner.querySelector('#reopened')); owner.querySelector('#reopened-field').focus();
    removeSecond(); await Promise.resolve();
    assert.equal(document.activeElement.id, 'reopened-field');
  } finally { cleanups.forEach(cb => cb()); await window.happyDOM.abort(); }
});

test('a client dismissal keeps its declared return focus through the subsequent action replacement', async () => {
  const window = new Window({ url: 'http://dialogs.test/' });
  for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'MouseEvent']) globalThis[name] = window[name];
  document.body.innerHTML = '<input id="outside" /><div data-bml-island><button id="edit">Edit</button><div id="old" data-bml-design-dialog data-force-open data-return-focus="#edit"><dialog><button data-dialog-close>Cancel</button></dialog></div></div>';
  const owner = document.querySelector('[data-bml-island]'), cleanups = [];
  const mount = root => {
    const panel = root.querySelector('dialog'), callbacks = [];
    panel.close = () => panel.removeAttribute('open'); panel.showModal = () => panel.setAttribute('open', '');
    const matches = panel.matches.bind(panel); panel.matches = selector => selector === ':modal' ? panel.open : matches(selector);
    modalDialog({ root, scoped: name => root.id + name, ref: () => null, onUpdate() {}, onUnmount: cb => callbacks.push(cb),
      on: (target, type, listener, options) => { target.addEventListener(type, listener, options); callbacks.push(() => target.removeEventListener(type, listener, options)); } });
    cleanups.push(...callbacks);
    return () => { callbacks.forEach(cb => cb()); callbacks.length = 0; };
  };
  try {
    const first = owner.querySelector('#old'), removeFirst = mount(first);
    first.querySelector('button').click(); assert.equal(document.activeElement.id, 'edit');
    owner.querySelector('#edit').replaceWith(Object.assign(document.createElement('button'), { id: 'edit', textContent: 'Current trigger' }));
    first.remove(); removeFirst(); await Promise.resolve();
    assert.equal(document.activeElement.id, 'edit', 'The action response returns to the current target.');
    owner.insertAdjacentHTML('beforeend', '<div id="other" data-bml-design-dialog data-force-open data-return-focus="#edit"><dialog><button data-dialog-close>Cancel</button></dialog></div>');
    const second = owner.querySelector('#other'), removeSecond = mount(second);
    second.querySelector('button').click(); document.getElementById('outside').focus();
    owner.querySelector('#edit').replaceWith(Object.assign(document.createElement('button'), { id: 'edit', textContent: 'New trigger' }));
    second.remove(); removeSecond(); await Promise.resolve();
    assert.equal(document.activeElement.id, 'outside', 'A later action response leaves deliberately moved focus alone.');
  } finally { cleanups.forEach(cb => cb()); await window.happyDOM.abort(); }
});
