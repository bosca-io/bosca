import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/board-moves.ts'], bundle: true, write: false, format: 'esm', platform: 'browser' });
const { workOpsBoardMoves } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
async function fixture(run) {
  const window = new Window();
  for (const name of ['window', 'document', 'Element', 'HTMLElement', 'CustomEvent']) Object.defineProperty(globalThis, name, { configurable: true, value: window[name] });
  document.body.innerHTML = `<div data-workops-board-view data-workops-move-sequence="0" data-workops-move-result=""><article data-workops-board-card="task" data-workops-card-version="7" data-workops-closing-columns="done"><span>Task</span></article></div>`;
  const root = document.body.firstElementChild, cleanups = [], updates = [];
  const context = { root, onUpdate: callback => updates.push(callback), onUnmount: callback => cleanups.push(callback), on(target, type, listener) {
    target.addEventListener(type, listener); cleanups.push(() => target.removeEventListener(type, listener));
  } };
  const request = columnId => {
    const waits = [];
    root.querySelector('article').dispatchEvent(new CustomEvent('boardmove', { bubbles: true, detail: { cardId: 'task', columnId, waitUntil: promise => waits.push(promise) } }));
    assert.equal(waits.length, 1); return waits[0];
  };
  const update = (sequence, result) => { root.dataset.workopsMoveSequence = String(sequence); root.dataset.workopsMoveResult = result; updates.forEach(callback => callback()); };
  const unmount = () => cleanups.splice(0).forEach(callback => callback());
  try { await run({ root, context, request, update, unmount }); }
  finally { unmount(); await window.happyDOM.abort(); }
}
test('nonterminal requests await the owning action and preserve captured versions and failures', () => fixture(async ({ context, request }) => {
  const pending = deferred(); let calls = 0;
  workOpsBoardMoves(context, { prepare() { throw Error('Unexpected closing move'); }, move(...args) { calls++; assert.deepEqual(args, ['task', 'doing', 7]); return pending.promise; } });
  const result = request('doing').catch(error => error); assert.equal(calls, 1);
  pending.reject(new Error('Task changed')); assert.equal((await result).message, 'Task changed');
}));
test('closing requests stay pending through preparation and rejection patches until explicitly saved', () => fixture(async ({ context, request, update }) => {
  workOpsBoardMoves(context, { move() { throw Error('Direct closing write'); }, async prepare(...args) { assert.deepEqual(args, ['task', 'done', 7]); } });
  let settled = false; const result = request('done').then(() => settled = true);
  await tick(); update(0, ''); await tick(); assert.equal(settled, false);
  const duplicate = await request('done').catch(error => error); assert.match(duplicate.message, /Finish the current/);
  update(1, 'saved'); await result; assert.equal(settled, true);
}));
test('canceling and unmounting reject outstanding closing requests without resolving a save', () => fixture(async ({ context, request, update, unmount }) => {
  workOpsBoardMoves(context, { async move() {}, async prepare() {} });
  const cancel = request('done').catch(error => error); update(1, 'canceled'); assert.match((await cancel).message, /has not moved/);
  const closing = request('done').catch(error => error); unmount(); assert.match((await closing).message, /board was closed/);
}));
test('an older prepare rejection cannot reject the next independently pending move', () => fixture(async ({ context, request, update }) => {
  const first = deferred(); let calls = 0;
  workOpsBoardMoves(context, { async move() {}, prepare() { return ++calls === 1 ? first.promise : Promise.resolve(); } });
  const canceled = request('done').catch(error => error); update(1, 'canceled'); await canceled;
  let settled = false; const next = request('done').then(() => settled = true);
  first.reject(new Error('Late preparation failure')); await tick(); assert.equal(settled, false);
  update(2, 'saved'); await next; assert.equal(settled, true);
}));
test('missing versions and preparation failures are returned to the shared board feedback', () => fixture(async ({ root, context, request }) => {
  workOpsBoardMoves(context, { async move() { throw Error('Unexpected write'); }, async prepare() { throw Error('Permission denied'); } });
  assert.equal((await request('done').catch(error => error)).message, 'Permission denied');
  root.querySelector('article').removeAttribute('data-workops-card-version');
  assert.match((await request('doing').catch(error => error)).message, /Reload this task/);
}));
