import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/release-refresh.ts'], bundle: true, write: false, format: 'esm', platform: 'browser' });
const { workOpsReleaseRefresh } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const tick = (ms = 10) => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

async function fixture(run) {
  const window = new Window();
  for (const key of ['window', 'document', 'Element', 'HTMLElement', 'Event', 'CustomEvent']) Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  let hidden = false;
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  document.body.innerHTML = '<div id="wrapper" data-workops-release-context="1"><section id="section"><p data-bml-ref="pollError"></p><input value="Unsent draft" /></section></div><div id="other"></div>';
  const wrapper = document.querySelector('#wrapper'), root = document.querySelector('#section'), calls = [], cleanups = [], updates = [];
  let handler = async () => {};
  const ctx = { root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`), onUpdate: cb => updates.push(cb), onUnmount: cb => cleanups.push(cb),
    on(target, type, listener, options) { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); },
    dispatch: (...args) => { calls.push(args); return handler(...args); } };
  const update = (target = wrapper) => document.dispatchEvent(new CustomEvent('bml:update', { detail: { target } }));
  const change = revision => { wrapper.dataset.workopsReleaseContext = String(revision); update(); };
  const visibility = value => { hidden = value; document.dispatchEvent(new Event('visibilitychange')); };
  const unmount = () => cleanups.splice(0).forEach(cb => cb());
  try { await run({ ctx, root, wrapper, calls, updates, update, change, visibility, unmount, handle: value => handler = value }); }
  finally { unmount(); await window.happyDOM.abort(); }
}

test('overview changes refresh preserved sections once and leave independent drafts untouched', () => fixture(async ({ ctx, calls, root, change, update }) => {
  workOpsReleaseRefresh(ctx);
  root.querySelector('input').value = 'Unsent retained draft';
  change(2); update(); await tick();
  assert.deepEqual(calls, [['model', 'refresh']]); assert.equal(root.querySelector('input').value, 'Unsent retained draft');
  update(document.querySelector('#other')); await tick(); assert.equal(calls.length, 1);
  change(3); await tick(); assert.equal(calls.length, 2);
}));

test('changes received during a refresh coalesce into a single followup using the latest context', () => fixture(async ({ ctx, calls, change, handle }) => {
  const pending = deferred(); handle(() => calls.length === 1 ? pending.promise : Promise.resolve());
  workOpsReleaseRefresh(ctx); change(2); await tick(); assert.equal(calls.length, 1);
  change(3); change(4); await tick(); assert.equal(calls.length, 1);
  pending.resolve(); await tick(20); assert.equal(calls.length, 2);
}));

test('hidden documents and pending dialogs defer context refresh and resume when usable', () => fixture(async ({ ctx, root, calls, change, visibility, update }) => {
  workOpsReleaseRefresh(ctx, { pauseForDialog: true });
  visibility(true); change(2); await tick(); assert.equal(calls.length, 0);
  root.insertAdjacentHTML('beforeend', '<dialog open></dialog>'); visibility(false); await tick(); assert.equal(calls.length, 0);
  root.querySelector('dialog').remove(); root.querySelector('input').setAttribute('data-bml-action-pending', ''); update(); await tick(); assert.equal(calls.length, 0);
  root.querySelector('input').removeAttribute('data-bml-action-pending'); update(); await tick(); assert.equal(calls.length, 1);
}));

test('polling uses actual activity, reports transport errors, and recovers without duplicate concurrent dispatches', () => fixture(async ({ ctx, root, calls, handle, visibility, unmount }) => {
  let active = false;
  workOpsReleaseRefresh(ctx, { active: () => active, intervalMs: 15 });
  await tick(35); assert.equal(calls.length, 0);
  const pending = deferred(); handle(() => pending.promise); active = true;
  await tick(25); assert.equal(calls.length, 1); await tick(35); assert.equal(calls.length, 1);
  pending.reject(new Error('Upstream unavailable')); active = false; await tick(); assert.equal(root.querySelector('p').textContent, 'Upstream unavailable');
  handle(async () => {}); visibility(true); active = true; await tick(35); assert.equal(calls.length, 1);
  visibility(false); await tick(20); assert.ok(calls.length >= 2); assert.equal(root.querySelector('p').textContent, '');
  active = false; await tick(); const stopped = calls.length; await tick(35); assert.equal(calls.length, stopped);
  unmount(); active = true; await tick(35); assert.equal(calls.length, stopped);
}));

test('settle retry runs once and unmount removes the timer, listeners and late error effects', () => fixture(async ({ ctx, root, calls, change, handle, unmount }) => {
  const pending = deferred(); handle(() => pending.promise);
  workOpsReleaseRefresh(ctx, { settleMs: 5 }); await tick(15); assert.equal(calls.length, 1);
  unmount(); change(2); pending.reject(new Error('Late failure')); await tick(20);
  assert.equal(calls.length, 1); assert.equal(root.querySelector('p').textContent, '');
}));

test('a poll deferred by a dialog is discarded at terminal state while explicit context changes still refresh', () => fixture(async ({ ctx, root, calls, change, update }) => {
  let active = true;
  root.insertAdjacentHTML('beforeend', '<dialog open></dialog>');
  workOpsReleaseRefresh(ctx, { active: () => active, intervalMs: 10, pauseForDialog: true });
  await tick(25); assert.equal(calls.length, 0);
  active = false; root.querySelector('dialog').remove(); update(); await tick(15);
  assert.equal(calls.length, 0, 'A deferred poll does not outlive the active run.');
  change(2); await tick(); assert.equal(calls.length, 1, 'A bundle or overview change still refreshes terminal runs.');
}));

test('a deferred section whose initial context is stale reloads using the current context revision', () => fixture(async ({ ctx, calls, change }) => {
  change(3);
  workOpsReleaseRefresh(ctx, { initialRevision: '1', contextual: true }); await tick();
  assert.deepEqual(calls, [['model', 'refreshContext', ['3']]]);
  change(4); await tick(); assert.deepEqual(calls[1], ['model', 'refreshContext', ['4']]);
}));

test('matching deferred contexts and standalone sections do not repeat their initial read', () => fixture(async ({ ctx, root, calls, update }) => {
  workOpsReleaseRefresh(ctx, { initialRevision: '1', contextual: true }); update(); await tick(); assert.equal(calls.length, 0);
  root.parentElement.removeAttribute('data-workops-release-context');
  workOpsReleaseRefresh(ctx, { initialRevision: 'before-mount', contextual: true }); await tick(); assert.equal(calls.length, 0);
}));
