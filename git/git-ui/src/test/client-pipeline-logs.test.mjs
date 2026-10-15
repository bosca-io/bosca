import { build } from "esbuild";
import { Window } from "happy-dom";
import { test } from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";

const bundle = await build({
  entryPoints: ["src/main/client/pipeline-logs.ts"],
  bundle: true,
  write: false,
  format: "iife",
  globalName: "PipelineLogs",
});
const line = (number, content = `Line ${number}`, stream = "STDOUT") => ({
  lineNumber: number, content, stream, timestamp: "2026-10-14T15:00:00Z",
});
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

function setup(t, { initial = [line(1)], active = true, refresh } = {}) {
  const window = new Window({ url: "https://git.test/pipelines/run" });
  const { document } = window;
  document.body.innerHTML = `<section data-step-status="${active ? "RUNNING" : "SUCCESS"}">
    <div id="root"><section class="step-logs" data-log-step="step" data-repository="repo">
      <span data-bml-ref="logStatus"></span>
      <button data-bml-ref="pause"></button><button data-bml-ref="follow"></button>
      <div data-bml-ref="output"><button data-bml-method="earlier">Load earlier</button>
        <div data-bml-ref="lines"></div>
      </div>
    </section></div></section>`;
  const root = document.querySelector("#root");
  const ref = (name) => root.querySelector(`[data-bml-ref="${name}"]`);
  const output = ref("output");
  Object.defineProperties(output, {
    clientHeight: { get: () => 80 },
    scrollHeight: { get: () => ref("lines").children.length * 20 },
  });
  window.HTMLElement.prototype.getBoundingClientRect = function () {
    const top = this.matches("[data-line]")
      ? Array.from(this.parentElement.children).indexOf(this) * 20 - output.scrollTop : 0;
    return { top, bottom: top + 20 };
  };
  const history = (batch) => {
    const surface = ref("lines");
    surface.replaceChildren();
    for (const value of batch) {
      const row = document.createElement("div");
      row.className = "log-line";
      row.dataset.line = String(value.lineNumber);
      row.dataset.stream = value.stream;
      row.dataset.timestamp = value.timestamp;
      const gutter = document.createElement("span");
      gutter.className = "line-number";
      gutter.textContent = String(value.lineNumber);
      const content = document.createElement("code");
      content.className = "line-content";
      content.textContent = value.content;
      row.append(gutter, content);
      surface.append(row);
    }
  };
  history(initial);
  const updates = [], cleanup = [], sockets = [], dispatches = [];
  const frames = new Map(), timers = new Map(), intervals = new Map();
  let serial = 0;
  class Socket {
    static OPEN = 1;
    readyState = 1;
    sent = [];
    constructor(url, protocol) { this.url = url; this.protocol = protocol; sockets.push(this); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; this.onclose?.({ code: 1000 }); }
    receive(message) { this.onmessage?.({ data: JSON.stringify(message) }); }
  }
  const ctx = {
    root, ref,
    on: (target, type, listener, options) => {
      target.addEventListener(type, listener, options);
      cleanup.push(() => target.removeEventListener(type, listener, options));
    },
    onUpdate: (callback) => updates.push(callback),
    onUnmount: (callback) => cleanup.push(callback),
    dispatch: async (...args) => {
      dispatches.push(args);
      const batch = await refresh?.(dispatches.length);
      if (batch) history(batch);
      updates.forEach((callback) => callback());
    },
  };
  const sandbox = {
    document, location: window.location, WebSocket: Socket,
    gitAuth: {
      initialize: async (fetchProfile) => { assert.equal(fetchProfile, false); }, getAuthHeaders: async () => ({ Authorization: "Bearer test" }),
      onAuthStateChanged: () => () => {},
    },
    requestAnimationFrame: (callback) => { frames.set(++serial, callback); return serial; },
    cancelAnimationFrame: (id) => frames.delete(id),
    setTimeout: (callback) => { timers.set(++serial, callback); return serial; },
    clearTimeout: (id) => timers.delete(id),
    setInterval: (callback) => { intervals.set(++serial, callback); return serial; },
    clearInterval: (id) => intervals.delete(id),
  };
  vm.runInNewContext(bundle.outputFiles[0].text, sandbox);
  sandbox.PipelineLogs.mountPipelineLogs(ctx);
  const unmount = () => { cleanup.splice(0).forEach((callback) => callback()); };
  t.after(() => { unmount(); window.happyDOM.cancelAsync(); });
  return {
    root, ref, output, sockets, frames, timers, dispatches, history, unmount,
    update: () => updates.forEach((callback) => callback()),
    tick: () => intervals.forEach((callback) => callback()),
    finish: () => { root.parentElement.dataset.stepStatus = "SUCCESS"; },
    flush: () => { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach((callback) => callback()); },
    emit: (value, socket = sockets.at(-1)) => socket.receive({ type: "next", payload: { data: { pipelineStepLogs: value } } }),
    numbers: () => Array.from(ref("lines").children).filter((row) => row.dataset.line).map((row) => Number(row.dataset.line)),
    scroll: (top) => { output.scrollTop = top; output.dispatchEvent(new window.Event("scroll")); },
    refreshRun: () => {
      // Browsers reset the scroll position when a retained panel is reinserted.
      output.scrollTop = 0;
      const target = root.parentElement;
      target.dispatchEvent(new window.CustomEvent("bml:update", {
        bubbles: true, detail: { target, added: [], retained: [root] },
      }));
      output.dispatchEvent(new window.Event("scroll"));
    },
  };
}

test("live bursts render once and retain existing rows, literal text and timestamp tooltips", async (t) => {
  const f = setup(t);
  await settle();
  const socket = f.sockets[0];
  assert.equal(socket.url, "wss://git.test/_git/subscriptions");
  socket.onopen();
  assert.equal(socket.sent[0].payload.authToken, "test");
  socket.receive({ type: "connection_ack" });
  await settle();
  assert.equal(f.ref("logStatus").dataset.state, "streaming");
  const first = f.ref("lines").firstElementChild;
  for (let n = 2; n <= 1001; n++) f.emit(line(n));
  assert.equal(f.frames.size, 1);
  assert.deepEqual(f.numbers(), [1]);
  f.flush();
  assert.equal(f.numbers().length, 1001);
  assert.equal(f.ref("lines").firstElementChild, first);
  f.emit(line(9, "<script>literal output</script>", "stderr"));
  f.flush();
  const ninth = f.root.querySelector('[data-line="9"]');
  assert.equal(ninth.querySelector("code").textContent, "<script>literal output</script>");
  assert.equal(ninth.querySelector("script"), null);
  assert.equal(ninth.querySelector("time"), null);
  assert.equal(ninth.querySelector(".line-number").title, line(9).timestamp);
  assert.equal(ninth.children.length, 2);
  assert.equal(f.output.scrollTop, f.output.scrollHeight);
});

test("out-of-order output and overlapping history merge in order without duplicate rows", async (t) => {
  const f = setup(t, { initial: [line(4), line(5)] });
  await settle();
  const fifth = f.root.querySelector('[data-line="5"]');
  f.emit(line(7)); f.emit(line(3)); f.emit(line(6)); f.emit(line(5));
  f.flush();
  assert.deepEqual(f.numbers(), [3, 4, 5, 6, 7]);
  assert.equal(f.root.querySelector('[data-line="5"]'), fifth);
  f.history([line(2), line(3), line(4), line(5)]);
  f.update();
  assert.deepEqual(f.numbers(), [2, 3, 4, 5, 6, 7]);
});

test("browsing earlier lines pauses the stream and preserves the visible line", async (t) => {
  const f = setup(t, { initial: Array.from({ length: 50 }, (_, i) => line(101 + i)) });
  await settle();
  f.scroll(100);
  assert.equal(f.ref("follow").hidden, false);
  f.root.querySelector('[data-bml-method="earlier"]').click();
  assert.equal(f.ref("pause").textContent, "Resume");
  assert.equal(f.sockets[0].readyState, 3);
  f.history(Array.from({ length: 150 }, (_, i) => line(1 + i)));
  f.update();
  assert.equal(f.output.scrollTop, 2100);
  assert.equal(f.ref("follow").getAttribute("aria-pressed"), "false");
  f.ref("follow").click();
  assert.equal(f.output.scrollTop, f.output.scrollHeight);
  assert.equal(f.ref("follow").hidden, true);
});

test("pause ignores stale sockets and resume backfills before continuing", async (t) => {
  const f = setup(t, { refresh: () => [line(1), line(2)] });
  await settle();
  const old = f.sockets[0];
  f.ref("pause").click();
  f.emit(line(3), old);
  f.flush();
  assert.deepEqual(f.numbers(), [1]);
  f.ref("pause").click();
  await settle();
  assert.deepEqual(f.numbers(), [1, 2]);
  assert.equal(f.sockets.length, 2);
  f.emit(line(4), old);
  f.emit(line(3)); f.flush();
  assert.deepEqual(f.numbers(), [1, 2, 3]);
});

test("run refreshes preserve following and subsequent live output stays at the tail", async (t) => {
  const f = setup(t, { initial: Array.from({ length: 50 }, (_, i) => line(i + 1)) });
  await settle();
  f.refreshRun();
  assert.equal(f.output.scrollTop, f.output.scrollHeight);
  assert.equal(f.ref("follow").hidden, true);
  f.emit(line(51));
  f.flush();
  assert.equal(f.output.scrollTop, f.output.scrollHeight);
});

test("run refreshes preserve the reader's position without resuming following", async (t) => {
  const f = setup(t, { initial: Array.from({ length: 50 }, (_, i) => line(i + 1)) });
  await settle();
  f.scroll(100);
  f.refreshRun();
  assert.equal(f.output.scrollTop, 100);
  assert.equal(f.ref("follow").getAttribute("aria-pressed"), "false");
  f.emit(line(51));
  f.flush();
  f.refreshRun();
  assert.equal(f.output.scrollTop, 100);
  assert.equal(f.ref("follow").hidden, false);
});

test("completion queues a final backfill behind an in-flight historical request", async (t) => {
  let resolve;
  const f = setup(t, { refresh: (count) => count === 1
    ? new Promise((done) => { resolve = done; }) : [line(1), line(2)] });
  await settle();
  f.sockets[0].receive({ type: "connection_ack" });
  await settle();
  f.finish(); f.tick();
  assert.equal(f.ref("pause").hidden, true);
  assert.equal(f.ref("logStatus").hidden, true);
  assert.equal(f.dispatches.length, 1);
  resolve([line(1)]);
  await settle();
  assert.equal(f.dispatches.length, 2);
  assert.deepEqual(f.numbers(), [1, 2]);
});

test("run polling keeps the stream and only reconnect and completion backfill history", async (t) => {
  const f = setup(t);
  await settle();
  const socket = f.sockets[0];
  socket.receive({ type: "connection_ack" });
  await settle();
  assert.equal(f.dispatches.length, 1);
  for (let i = 0; i < 5; i++) {
    f.refreshRun();
    f.tick();
  }
  await settle();
  assert.equal(f.sockets.length, 1);
  assert.equal(socket.readyState, 1);
  assert.equal(f.dispatches.length, 1);
  f.emit(line(2));
  f.flush();
  assert.deepEqual(f.numbers(), [1, 2]);

  socket.close();
  const callbacks = [...f.timers.values()];
  f.timers.clear();
  callbacks.forEach((callback) => callback());
  await settle();
  assert.equal(f.sockets.length, 2);
  f.sockets[1].receive({ type: "connection_ack" });
  await settle();
  assert.equal(f.dispatches.length, 2);

  f.finish();
  f.refreshRun();
  f.tick();
  await settle();
  assert.equal(f.sockets[1].readyState, 3);
  assert.equal(f.dispatches.length, 3);
  f.tick();
  await settle();
  assert.equal(f.dispatches.length, 3);
});

test("terminal steps have no live subscription and unmount cancels scheduled rendering", async (t) => {
  const terminal = setup(t, { active: false });
  await settle();
  assert.equal(terminal.sockets.length, 0);
  assert.equal(terminal.ref("pause").hidden, true);
  const f = setup(t);
  await settle();
  f.emit(line(2));
  assert.equal(f.frames.size, 1);
  f.unmount();
  assert.equal(f.frames.size, 0);
  assert.equal(f.sockets[0].readyState, 3);
  assert.equal(f.timers.size, 0);
});

test("the visible tail stays bounded at 20,000 lines", async (t) => {
  const f = setup(t);
  await settle();
  for (let n = 2; n <= 20005; n++) f.emit(line(n));
  f.flush();
  assert.equal(f.numbers().length, 20000);
  assert.equal(f.numbers()[0], 6);
  assert.equal(f.numbers().at(-1), 20005);
});
