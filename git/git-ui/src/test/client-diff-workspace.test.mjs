import { build } from "esbuild";
import { Window } from "happy-dom";
import { test } from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";

const bundle = await build({
  entryPoints: ["src/main/client/diff-workspace.ts"],
  bundle: true, write: false, format: "iife", globalName: "DiffWorkspace",
});

function setup(t, count = 1024) {
  const window = new Window({ url: "https://git.test/pulls/large?view=files" });
  const { document } = window;
  document.body.innerHTML = '<main style="overflow-y:auto"><div id="root"></div></main>';
  const root = document.getElementById("root");
  const scroller = root.parentElement;
  const updates = [], cleanup = [], frames = new Map();
  let serial = 0, reads = 0, scans = 0, writes = 0;
  const render = (size) => {
    root.innerHTML = `<aside class="diff-sidebar"><div class="sidebar-tree">${Array.from({ length: size }, (_, i) =>
      `<div role="treeitem" data-path="file-${i}" aria-selected="false"><a href="#file-${i}">File ${i}</a></div>`).join("")}</div></aside>` +
      Array.from({ length: size }, (_, i) => `<section class="diff-file" data-path="file-${i}"><button class="diff-file-toggle"></button><div class="diff-file-body"></div></section>`).join("");
    for (const [i, file] of [...root.querySelectorAll(".diff-file")].entries()) {
      file.getBoundingClientRect = () => { reads++; return { top: i * 100 - scroller.scrollTop, bottom: (i + 1) * 100 - scroller.scrollTop }; };
    }
    for (const item of root.querySelectorAll('[role="treeitem"]')) {
      const original = item.setAttribute.bind(item);
      item.setAttribute = (key, value) => { if (key === "aria-selected") writes++; original(key, value); };
    }
  };
  render(count);
  const original = root.querySelectorAll.bind(root);
  root.querySelectorAll = (...args) => { scans++; return original(...args); };
  const ctx = {
    root, ref: () => null,
    on: (target, type, listener, options) => {
      target.addEventListener(type, listener, options);
      cleanup.push(() => target.removeEventListener(type, listener, options));
    },
    onUpdate: (callback) => updates.push(callback),
    onUnmount: (callback) => cleanup.push(callback),
  };
  const sandbox = {
    location: window.location, document, localStorage: window.localStorage,
    HTMLElement: window.HTMLElement, Element: window.Element,
    getComputedStyle: window.getComputedStyle.bind(window),
    requestAnimationFrame: (callback) => { const id = ++serial; frames.set(id, callback); return id; },
    cancelAnimationFrame: (id) => frames.delete(id),
  };
  vm.runInNewContext(bundle.outputFiles[0].text, sandbox);
  sandbox.DiffWorkspace.diffWorkspace(ctx);
  const flush = () => { for (const [id, callback] of [...frames]) { frames.delete(id); callback(); } };
  flush();
  const reset = () => { reads = 0; scans = 0; writes = 0; };
  const scroll = (position) => { scroller.scrollTop = position; scroller.dispatchEvent(new window.Event("scroll")); };
  t.after(() => { cleanup.forEach((callback) => callback()); window.happyDOM.abort(); });
  return { root, render, updates, flush, reset, scroll, frames,
    counts: () => ({ reads, scans, writes }),
    selected: () => root.querySelector('[aria-selected="true"]')?.dataset.path,
  };
}

test("large diffs bound scroll layout reads and coalesce events without rescanning DOM", (t) => {
  const view = setup(t);
  view.reset();
  for (let i = 0; i < 20; i++) view.scroll(70000 + i);
  assert.equal(view.frames.size, 1);
  view.flush();
  assert.equal(view.selected(), "file-700");
  assert.ok(view.counts().reads <= 11, JSON.stringify(view.counts()));
  assert.equal(view.counts().scans, 0);
  assert.equal(view.counts().writes, 2);
  view.reset();
  view.scroll(70020);
  view.flush();
  assert.equal(view.counts().writes, 0, "No tree writes while the active file stays the same");
  view.scroll(0);
  view.flush();
  assert.equal(view.selected(), "file-0");
});

test("island updates refresh cached elements and restore collapsed files", (t) => {
  const view = setup(t, 10);
  view.root.querySelector('.diff-file[data-path="file-0"] .diff-file-toggle').click();
  view.render(20);
  view.updates.forEach((callback) => callback());
  view.flush();
  assert.equal(view.root.querySelector('.diff-file[data-path="file-0"] .diff-file-body').hidden, true);
  view.root.querySelector('.diff-sidebar [data-path="file-0"] a').click();
  assert.equal(view.root.querySelector('.diff-file[data-path="file-0"] .diff-file-body').hidden, false);
  view.scroll(1500);
  view.flush();
  assert.equal(view.selected(), "file-15", "Newly rendered files are selectable");
});
