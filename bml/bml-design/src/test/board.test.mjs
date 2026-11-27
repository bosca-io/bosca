import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/board.ts'], bundle: true, write: false, format: 'esm' });
const { mountBoardCard } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const flush = () => new Promise(resolve => setImmediate(resolve));
async function fixture(check) {
  const window = new Window();
  for (const key of ['document', 'HTMLElement', 'Element', 'Event', 'CustomEvent', 'PointerEvent', 'KeyboardEvent', 'MouseEvent', 'MutationObserver', 'Option']) globalThis[key] = window[key];
  document.body.innerHTML = '<div data-bml-island><div data-bml-board><section data-board-column="todo"><div data-board-column-items><article data-board-card="card" data-movable><a href="/item">An application-owned title</a><button data-ref="moveHandle">Move</button><p data-ref="status" hidden></p><p data-ref="error" hidden></p><div data-board-move-dialog><form data-ref="moveForm"><select data-ref="destination"></select><p data-ref="dialogError" hidden></p><button type="submit">Move item</button></form></div></article></div></section><section data-board-column="done"><div data-board-column-items></div></section><section data-board-column="blocked"><div data-board-column-items></div></section></div></div>';
  const card = document.querySelector('article'), board = document.querySelector('[data-bml-board]'), done = document.querySelector('[data-board-column=done]');
  card.dataset.targets = JSON.stringify([{ id: 'todo', label: 'To do' }, { id: 'done', label: 'Done <safe>' }]);
  const ref = name => card.querySelector(`[data-ref=${name}]`), cleanup = []; let update;
  const dispose = () => cleanup.splice(0).forEach(fn => fn());
  const modal = card.querySelector('[data-board-move-dialog]'); let opened = 0, closed = 0;
  modal.addEventListener('dialogopen', () => opened++); modal.addEventListener('dialogclose', () => closed++);
  let target = done; document.elementFromPoint = () => target;
  board.getBoundingClientRect = () => ({ left: 0, right: 800, top: 0, bottom: 620 });
  mountBoardCard({ root: card, ref,
    on(node, event, handler, options) { node.addEventListener(event, handler, options); cleanup.push(() => node.removeEventListener(event, handler, options)); },
    onUpdate(fn) { update = fn; }, onUnmount(fn) { cleanup.push(fn); },
  });
  const submit = () => { ref('destination').value = 'done'; ref('moveForm').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); };
  const pointer = (name, x = 400, y = 100) => new PointerEvent(name, { pointerId: 1, button: 0, clientX: x, clientY: y, bubbles: true, cancelable: true });
  try { await check({ card, board, done, ref, update, submit, pointer, dispose, target: value => target = value, opened: () => opened, closed: () => closed }); }
  finally { dispose(); window.happyDOM.cancelAsync(); }
}

test('keyboard movement uses safe target labels and waits for the application save before completing', () => fixture(async ({ card, done, ref, submit, closed }) => {
  assert.equal(ref('destination').options.length, 2); assert.equal(ref('destination').options[1].text, 'Done <safe>');
  assert.equal(card.querySelector('safe'), null);
  let accept, requests = 0;
  card.addEventListener('boardmove', event => {
    requests++; assert.equal(event.detail.cardId, 'card'); assert.equal(event.detail.columnId, 'done');
    event.detail.waitUntil(new Promise(resolve => accept = resolve));
  });
  submit(); assert.equal(ref('moveHandle').disabled, true); assert.equal(card.getAttribute('aria-busy'), 'true'); assert.equal(closed(), 0);
  submit(); assert.equal(requests, 1);
  done.querySelector('[data-board-column-items]').append(card); accept(); await flush();
  assert.equal(closed(), 1); assert.equal(card.getAttribute('aria-busy'), 'false'); assert.equal(ref('status').textContent, 'Moved to Done <safe>.');
  assert.equal(document.activeElement, ref('moveHandle'));
}));

test('a rejected request retains the selected destination and can be retried', () => fixture(async ({ card, ref, submit, closed }) => {
  let requests = 0, rejected;
  card.addEventListener('boarderror', event => rejected = event.detail);
  card.addEventListener('boardmove', event => { requests++; event.detail.waitUntil(requests === 1 ? Promise.reject(new Error('Save rejected')) : Promise.resolve()); });
  submit(); await flush(); assert.equal(ref('error').textContent, 'Save rejected'); assert.equal(ref('dialogError').textContent, 'Save rejected');
  assert.equal(ref('destination').value, 'done'); assert.equal(ref('moveHandle').disabled, false); assert.equal(closed(), 0);
  assert.deepEqual(rejected, { cardId: 'card', columnId: 'done', message: 'Save rejected' });
  submit(); await flush(); assert.equal(requests, 2); assert.equal(closed(), 1);
}));

test('pointer movement requests only an allowed column and suppresses its synthetic click', () => fixture(async ({ card, ref, pointer, opened }) => {
  let requests = 0; card.addEventListener('boardmove', event => { requests++; event.detail.waitUntil(Promise.resolve()); });
  ref('moveHandle').dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove', 400));
  assert.equal(document.querySelector('[data-board-column=done]').dataset.boardDrop, 'allowed');
  document.dispatchEvent(pointer('pointerup', 400)); await flush(); assert.equal(requests, 1);
  ref('moveHandle').dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 })); assert.equal(opened(), 0);
  ref('moveHandle').dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 })); assert.equal(opened(), 1);
}));

test('blocked columns and Escape cancel pointer gestures without requesting a write', () => fixture(({ card, ref, pointer, target }) => {
  let requests = 0; card.addEventListener('boardmove', () => requests++);
  target(document.querySelector('[data-board-column=blocked]'));
  ref('moveHandle').dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove'));
  assert.equal(document.querySelector('[data-board-column=blocked]').dataset.boardDrop, 'blocked');
  document.dispatchEvent(pointer('pointerup')); assert.equal(requests, 0);
  ref('moveHandle').dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove'));
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  document.dispatchEvent(pointer('pointerup')); assert.equal(requests, 0); assert.equal(card.hasAttribute('data-board-dragging'), false);
  assert.equal(ref('status').textContent, 'Move canceled.');
}));

test('touch capture moving from a child span to its handle does not cancel the drag', () => fixture(async ({ card, ref, pointer }) => {
  let requests = 0; card.addEventListener('boardmove', event => { requests++; event.detail.waitUntil(Promise.resolve()); });
  const span = document.createElement('span'); span.textContent = 'Move'; ref('moveHandle').append(span);
  span.dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove'));
  span.dispatchEvent(pointer('lostpointercapture')); document.dispatchEvent(pointer('pointerup')); await flush();
  assert.equal(requests, 1);
  ref('moveHandle').dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove'));
  ref('moveHandle').dispatchEvent(pointer('lostpointercapture')); document.dispatchEvent(pointer('pointerup'));
  assert.equal(requests, 1, 'Actual handle capture loss cancels the next unfinished drag.');
}));

test('readonly disabled and malformed targets fail closed and recover without remounting', () => fixture(({ card, board, ref, update, submit }) => {
  let requests = 0; card.addEventListener('boardmove', () => requests++);
  board.setAttribute('data-board-readonly', ''); update(); assert.equal(ref('moveHandle').hidden, true); submit(); assert.equal(requests, 0);
  board.removeAttribute('data-board-readonly'); card.setAttribute('data-disabled', ''); update(); assert.equal(ref('moveHandle').disabled, true);
  card.removeAttribute('data-disabled'); card.dataset.targets = '{'; update(); assert.equal(ref('moveHandle').disabled, true); assert.match(ref('error').textContent, /destinations are unavailable/);
  card.dataset.targets = JSON.stringify([{ id: 'done', label: 'Done' }]); update(); assert.equal(ref('moveHandle').disabled, false); assert.equal(ref('error').hidden, true);
}));

test('preserved cards reconcile new targets without losing a pending request or local content', () => fixture(async ({ card, ref, submit, update }) => {
  let accept; card.addEventListener('boardmove', event => event.detail.waitUntil(new Promise(resolve => accept = resolve)));
  const draft = document.createElement('input'); draft.value = 'Local application draft'; card.prepend(draft);
  submit(); card.removeAttribute('data-board-pending'); update();
  assert.equal(card.hasAttribute('data-board-pending'), true); assert.equal(draft.value, 'Local application draft');
  accept(); await flush(); card.dataset.targets = JSON.stringify([{ id: 'blocked', label: 'Newly allowed' }]); update();
  assert.equal(ref('destination').options[1].text, 'Newly allowed'); assert.equal(ref('destination').value, '');
  assert.equal(draft.value, 'Local application draft');
}));

test('unmount releases pointer handlers and ignores completion of an application-owned pending request', () => fixture(async ({ card, ref, submit, dispose, pointer, closed }) => {
  let accept, requests = 0; card.addEventListener('boardmove', event => { requests++; event.detail.waitUntil(new Promise(resolve => accept = resolve)); });
  submit(); dispose(); accept(); await flush(); assert.equal(closed(), 0);
  ref('moveHandle').dispatchEvent(pointer('pointerdown', 20)); document.dispatchEvent(pointer('pointermove')); document.dispatchEvent(pointer('pointerup'));
  assert.equal(requests, 1); assert.equal(card.hasAttribute('data-board-dragging'), false);
}));

test('a missing application save handler does not report a successful move', () => fixture(async ({ ref, submit, closed }) => {
  submit(); await flush(); assert.equal(ref('error').textContent, 'Moving is unavailable.'); assert.equal(closed(), 0);
  assert.equal(ref('moveHandle').disabled, false);
}));
