import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/release-live.ts'], bundle: true, write: false, format: 'esm' });
const { workOpsReleaseLive } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const tick = (ms = 20) => new Promise(resolve => setTimeout(resolve, ms));
const source = (appId = 'mobile', appVersion = '1.0.0', projectId = 'project') => ({ appId, appVersion, projectId, projectLabel: projectId });
const point = (overrides = {}) => ({ sessionId: 'visitor', lat: 40, lon: -74, appId: 'mobile', appVersion: '1.0.0', ...overrides });
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

async function fixture(run, selected = [source()], ready = Promise.resolve()) {
  const window = new Window({ url: 'https://atlas.example.test/workops/releases/release' });
  const keys = ['window', 'document', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'WebSocket', 'location', 'requestAnimationFrame', 'cancelAnimationFrame', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'boscaAuth', 'boscaAuthFlow'];
  const original = new Map(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const nativeTimeout = setTimeout, nativeClearTimeout = clearTimeout, nativeInterval = setInterval, nativeClearInterval = clearInterval, nativeNow = Date.now;
  const timers = new Set(), intervals = new Map(), callbacks = new Map(), calls = [], updates = [], cleanups = [];
  let now = 0, token = 'initial-token', tokenRead = async () => token, tokenReads = 0, dispatch;
  for (const key of ['window', 'document', 'Element', 'HTMLElement', 'Event', 'CustomEvent', 'location']) Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  class Socket {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 3; static all = [];
    readyState = 0; sent = []; onopen = null; onmessage = null; onerror = null; onclose = null;
    constructor(url, protocol) { this.url = url; this.protocol = protocol; Socket.all.push(this); }
    send(value) { assert.equal(this.readyState, 1); this.sent.push(JSON.parse(value)); }
    open() { this.readyState = 1; this.onopen?.({}); this.message({ type: 'connection_ack' }); }
    message(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
    next(value) { this.message({ type: 'next', id: 'sessions', payload: { data: { liveSessions: value } } }); }
    close() { this.readyState = 3; this.onclose?.({}); }
  }
  Object.assign(globalThis, {
    WebSocket: Socket,
    requestAnimationFrame: callback => nativeTimeout(() => callback(0), 1), cancelAnimationFrame: nativeClearTimeout,
    setTimeout: (callback, delay, ...args) => { const id = nativeTimeout(() => { timers.delete(id); callback(...args); }, delay); if (delay >= 1000) timers.add(id); return id; },
    clearTimeout: id => { timers.delete(id); nativeClearTimeout(id); },
    setInterval: (callback, delay) => { const id = nativeInterval(callback, delay); intervals.set(id, callback); return id; },
    clearInterval: id => { intervals.delete(id); nativeClearInterval(id); },
    boscaAuthFlow: { ready },
    boscaAuth: { getToken: () => { tokenReads++; return tokenRead(); }, on(event, callback) { if (!callbacks.has(event)) callbacks.set(event, new Set()); callbacks.get(event).add(callback); return () => callbacks.get(event).delete(callback); } },
  });
  Date.now = () => now;
  document.body.innerHTML = '<section data-workops-release-live data-live-loaded data-stream-revision="0"><span data-live-status></span><span data-live-count></span><p data-live-error></p><div data-workops-live-chart></div></section>';
  let root = document.querySelector('section'); root.dataset.subscription = 'subscription TypedLive { liveSessions { sessionId } }';
  const setSources = values => {
    root.dataset.liveSources = JSON.stringify(values);
    root.dataset.liveOptions = JSON.stringify({ geo: { map: 'world' }, series: [...new Map(values.map(value => [value.projectId, value])).values()].map((value, index) => ({ id: value.projectId, name: value.projectLabel, type: 'scatter', itemStyle: { color: `color-${index}` }, data: [] })) });
  };
  setSources(selected);
  const update = () => updates.forEach(callback => callback({ target: root }));
  const revision = () => { root.dataset.streamRevision = String(Number(root.dataset.streamRevision) + 1); update(); };
  dispatch = async () => revision();
  const replaceOwner = () => { const next = root.cloneNode(true); root.replaceWith(next); root = next; update(); return next; };
  const ctx = { root: document.body, onUpdate: callback => updates.push(callback), onUnmount: callback => cleanups.push(callback),
    on(target, type, listener, options) { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); },
    dispatch: async (...args) => { calls.push(args); await dispatch(...args); } };
  const unmount = () => cleanups.splice(0).forEach(callback => callback());
  const emit = name => { if (name === 'signedOut') token = null; for (const callback of callbacks.get(name) ?? []) callback(); };
  try {
    workOpsReleaseLive(ctx);
    await run({ root, Socket, calls, intervals, timers, callbacks, update, revision, unmount, setSources, emit, replaceOwner,
      readToken: value => tokenRead = value, setToken: value => token = value, reads: () => tokenReads, dispatch: value => dispatch = value, clock: value => now = value,
      plotted: () => JSON.parse(root.querySelector('[data-workops-live-chart]').dataset.config).series.flatMap(series => series.data) });
  } finally {
    unmount(); Date.now = nativeNow;
    for (const id of timers) nativeClearTimeout(id); for (const id of intervals.keys()) nativeClearInterval(id);
    for (const [key, descriptor] of original) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
    await window.happyDOM.abort();
  }
}

test('the typed subscription uses the authenticated Atlas relay and updates native chart data', () => fixture(async ({ Socket, root, plotted }) => {
  await tick(); assert.equal(Socket.all.length, 1); const socket = Socket.all[0]; socket.open();
  assert.equal(socket.url, 'wss://atlas.example.test/_git/subscriptions'); assert.equal(socket.protocol, 'graphql-transport-ws');
  assert.deepEqual(socket.sent[0], { type: 'connection_init', payload: { authToken: 'initial-token' } });
  assert.deepEqual(socket.sent[1].payload.variables, { appId: 'mobile', appVersion: '1.0.0' });
  assert.equal(socket.sent[1].payload.query, root.dataset.subscription);
  socket.message({ type: 'ping', payload: { clock: 1 } }); assert.deepEqual(socket.sent.at(-1), { type: 'pong', payload: { clock: 1 } });
  socket.next(point()); await tick(); assert.equal(root.querySelector('[data-live-count]').textContent, '1 sessions received'); assert.deepEqual(plotted()[0].value, [-74, 40]);
}));
test('shared project sources deduplicate subscriptions and both map instances receive the same points', () => fixture(async ({ Socket, root, update, plotted }) => {
  await tick(); assert.equal(Socket.all.length, 1); Socket.all[0].open(); Socket.all[0].next(point()); await tick();
  assert.equal(plotted().length, 2); assert.equal(root.querySelector('[data-live-count]').textContent, '1 sessions received');
  const second = document.createElement('div'); second.setAttribute('data-workops-live-chart', ''); root.append(second); update(); await tick();
  assert.equal(Socket.all.length, 1); assert.equal(second.dataset.config, root.querySelector('[data-workops-live-chart]').dataset.config);
}, [source(), source('mobile', '1.0.0', 'other')]));
test('server replacement rebinds presentation and source authority without duplicating unchanged streams', () => fixture(async ({ Socket, replaceOwner, plotted, setSources, update }) => {
  await tick(); const socket = Socket.all[0]; socket.open(); socket.next(point()); await tick();
  const next = replaceOwner(); next.querySelector('[data-live-count]').textContent = 'Waiting';
  const expanded = document.createElement('div'); expanded.setAttribute('data-workops-live-chart', ''); next.append(expanded);
  await tick(); assert.equal(next.querySelector('[data-live-count]').textContent, '1 sessions received'); assert.equal(Socket.all.length, 1);
  assert.deepEqual(JSON.parse(expanded.dataset.config).series[0].data[0].value, [-74, 40]);
  socket.next(point({ lon: -73 })); await tick(); assert.deepEqual(plotted()[0].value, [-73, 40]);
  setSources([source('replacement')]); update(); await tick(); assert.equal(socket.readyState, Socket.CLOSED); assert.equal(Socket.all.length, 2); assert.deepEqual(plotted(), []);
}));
test('source replacement closes old sockets and ignores late messages and tokens', () => fixture(async ({ Socket, setSources, update, plotted, readToken }) => {
  const held = deferred(); readToken(() => held.promise); await tick(); assert.equal(Socket.all.length, 0);
  readToken(async () => 'new-token'); setSources([source('replacement')]); update(); await tick();
  assert.equal(Socket.all.length, 1); held.resolve('old-token'); await tick(); assert.equal(Socket.all.length, 1);
  const socket = Socket.all[0]; socket.open(); const late = socket.onmessage;
  setSources([source('newer')]); update(); await tick(); assert.equal(socket.readyState, Socket.CLOSED);
  late({ data: JSON.stringify({ type: 'next', id: 'sessions', payload: { data: { liveSessions: point({ appId: 'replacement' }) } } }) }); await tick(); assert.deepEqual(plotted(), []);
}));
test('auth initialization and unmount prevent deferred connections and release every timer and listener', async () => {
  const ready = deferred();
  await fixture(async ({ Socket, reads, unmount, callbacks, intervals, timers }) => {
    await tick(); assert.equal(reads(), 0); unmount(); ready.resolve(); await tick();
    assert.equal(Socket.all.length, 0); assert.equal(intervals.size, 0); assert.equal(timers.size, 0); assert.equal([...callbacks.values()].reduce((sum, value) => sum + value.size, 0), 0);
  }, [source()], ready.promise);
});
test('token adoption reauthorizes sources before reopening with the current token', () => fixture(async ({ Socket, emit, setToken, dispatch, revision, calls }) => {
  await tick(); const old = Socket.all[0]; old.open(); const gate = deferred(); dispatch(async () => { await gate.promise; revision(); });
  setToken('adopted-token'); emit('tokenAdopted'); await tick(); assert.equal(old.readyState, Socket.CLOSED); assert.equal(Socket.all.length, 1);
  assert.deepEqual(calls[0], ['model', 'refreshContext', [''], { coalesce: true }]);
  gate.resolve(); await tick(); assert.equal(Socket.all.length, 2); Socket.all[1].open(); assert.equal(Socket.all[1].sent[0].payload.authToken, 'adopted-token');
}));
test('signout wins over a late reauthorization response and denied metadata cannot reopen a stream', () => fixture(async ({ Socket, emit, dispatch, revision, root, setSources }) => {
  await tick(); Socket.all[0].open(); const gate = deferred(); dispatch(async () => { await gate.promise; revision(); });
  emit('tokenRefreshed'); emit('signedOut'); gate.resolve(); await tick(); assert.equal(Socket.all.length, 1); assert.equal(Socket.all[0].readyState, Socket.CLOSED);
  root.removeAttribute('data-live-loaded'); setSources([]); updateDenied();
  function updateDenied() { revision(); }
  await tick(); assert.equal(Socket.all.length, 1); assert.equal(root.querySelector('[data-live-count]').textContent, 'Unavailable');
}));
test('sweeps expire received locations while stream completion retries and disposal cancels retries', () => fixture(async ({ Socket, intervals, clock, root, timers, unmount }) => {
  await tick(); Socket.all[0].open(); Socket.all[0].next(point()); await tick();
  clock(15 * 60 * 1000 + 1); for (const callback of intervals.values()) callback(); await tick(); assert.equal(root.querySelector('[data-live-count]').textContent, '0 sessions received');
  Socket.all[0].message({ type: 'error', id: 'sessions', payload: [{ message: 'Fixture stream interrupted' }] }); await tick(); assert.match(root.querySelector('[data-live-error]').textContent, /Fixture stream interrupted/);
  assert.equal(timers.size, 1); await tick(1050); assert.equal(Socket.all.length, 2); Socket.all[1].open(); Socket.all[1].close(); await tick();
  assert.equal(timers.size, 1); unmount(); assert.equal(timers.size, 0); assert.equal(intervals.size, 0);
}));
