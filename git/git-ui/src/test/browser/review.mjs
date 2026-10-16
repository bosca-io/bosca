import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const targets = await (
  await fetch(`${process.env.GIT_UI_CDP_URL}/json`)
).json();
const ws = new WebSocket(
  targets.find(
    (t) =>
      t.url.startsWith(base) ||
      t.type === "page",
  ).webSocketDebuggerUrl,
);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let n = 0;
const pending = new Map(),
  errors = [];
let holdNextAction = false;
let heldAction = null;
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Fetch.requestPaused") {
    if (holdNextAction) {
      heldAction = m.params.requestId;
      holdNextAction = false;
    } else void call("Fetch.continueRequest", { requestId: m.params.requestId });
  }
  if (m.method === "Runtime.exceptionThrown")
    errors.push(m.params.exceptionDetails);
  if (m.method === "Page.javascriptDialogOpening")
    void call("Page.handleJavaScriptDialog", { accept: true });
  if (m.id) {
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    clearTimeout(p.timeout);
    m.error ? p.reject(m.error) : p.resolve(m.result);
  }
});
ws.addEventListener("close", (event) => {
  for (const request of pending.values()) {
    clearTimeout(request.timeout);
    request.reject(
      new Error(
        `Chrome connection closed during ${request.method} (${event.code}: ${event.reason})`,
      ),
    );
  }
  pending.clear();
});
const call = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++n;
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(
        new Error(
          `Chrome timed out during ${method}: ${params.expression ?? ""}`,
        ),
      );
    }, 30000);
    pending.set(id, { resolve, reject, timeout, method });
    ws.send(JSON.stringify({ id, method, params }));
  });
async function ev(expression) {
  const r = await call("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
async function until(s) {
  for (let i = 0; i < 100; i++) {
    if (await ev(s)) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  console.error("Review browser state:", await ev(`JSON.stringify({ url: location.href, active: document.activeElement?.outerHTML.slice(0, 250), trigger: document.querySelector('.line-action')?.outerHTML.slice(0, 250) })`));
  console.error("Browser exceptions:", errors);
  throw Error("Timeout " + s);
}
async function pressKey(key) {
  const code = {
    ArrowRight: 39,
    ArrowLeft: 37,
    ArrowDown: 40,
    ArrowUp: 38,
    Home: 36,
    End: 35,
    Escape: 27,
    Enter: 13,
  }[key];
  for (const type of ["keyDown", "keyUp"])
    await call("Input.dispatchKeyEvent", {
      type,
      key,
      code: key,
      windowsVirtualKeyCode: code,
    });
}
async function nav(path) {
  await call("Page.navigate", { url: base + path });
  await until(
    `location.href === ${JSON.stringify(base + path)} && document.readyState==='complete' && document.querySelector('main') && document.querySelector('[data-bml-ref=signout]') && !document.querySelector('[data-bml-ref=signout]').disabled`,
  );
  await ev("document.fonts.ready");
  await new Promise((r) => setTimeout(r, 180));
}
await call("Runtime.enable");
await call("Page.enable");
await call("Page.bringToFront");
await call("Network.setCookie", {
  name: "_git_test",
  value: "ui-test-token",
  url: base,
});
await call("Network.setCookie", {
  name: "_git_test_meta",
  value: encodeURIComponent(
    encodeURIComponent(JSON.stringify({ expiresAt: 4102444800, issuedAt: 1 })),
  ),
  url: base,
});

const artifacts = process.env.GIT_UI_ARTIFACT_DIR;
async function screenshot(name, width) {
  const result = await call("Page.captureScreenshot", { format: "png" });
  await writeFile(`${artifacts}/review-${name}-${width}.png`, Buffer.from(result.data, "base64"));
}
async function click(selector) {
  const bounds = await ev(`(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    element.scrollIntoView({ block: 'center' });
    const r = element.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  })()`);
  await call("Input.dispatchMouseEvent", { type: "mouseMoved", ...bounds });
  await call("Input.dispatchMouseEvent", { type: "mousePressed", button: "left", clickCount: 1, ...bounds });
  await call("Input.dispatchMouseEvent", { type: "mouseReleased", button: "left", clickCount: 1, ...bounds });
}

const CONTEXT_FILE =
  "src/main/kotlin/bosca/git/ui/review/expansion/ContextExpansionReviewFixtureWithAVeryLongName.kt";
const file = `.diff-file[data-path="${CONTEXT_FILE}"]`;
const shown = (n) =>
  `[...document.querySelectorAll('${file} .diff-line code')].some(c => c.textContent === 'line ${n}')`;
// The page body scrolls inside the layout, not the document.
const scroller = "document.querySelector('main.page')";

for (const width of [1440, 390]) {
  await call("Emulation.setDeviceMetricsOverride", { width, height: 900, deviceScaleFactor: 1, mobile: false });
  await ev("try { localStorage.removeItem('git-ui:diff-sidebar-width') } catch {}");
  await nav("/pulls/pull-context?repo=repo&number=12&view=files");

  const label = await ev(`(() => {
    const item = document.querySelector('.diff-sidebar [role="treeitem"][data-path="${CONTEXT_FILE}"] .tree-label');
    const row = item.closest('.ui-tree-row');
    const tree = document.querySelector('.sidebar-tree');
    return { text: item.textContent.trim(), clipped: item.scrollWidth > item.clientWidth + 1,
      whiteSpace: getComputedStyle(item).whiteSpace, rowHeight: row.getBoundingClientRect().height,
      scrollWidth: tree.scrollWidth, clientWidth: tree.clientWidth };
  })()`);
  assert.equal(label.text, "ContextExpansionReviewFixtureWithAVeryLongName.kt", "The tree shows the whole file name");
  assert.equal(label.clipped, false, "The entire label is available in the scrollable tree");
  assert.equal(label.whiteSpace, "nowrap", "Long file names stay on one line");
  assert.ok(label.rowHeight <= 32, "A long name does not increase the row height");
  assert.ok(label.scrollWidth > label.clientWidth, "The tree provides horizontal scrolling");
  const treeBounds = await ev(`(() => {
    const r = document.querySelector('.sidebar-tree').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  })()`);
  await call("Input.dispatchMouseEvent", { type: "mouseWheel", ...treeBounds, deltaX: 200, deltaY: 0 });
  await until(`document.querySelector('.sidebar-tree').scrollLeft > 0`);
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "Horizontal scrolling stays inside the tree");
  await screenshot("tree-scroll", width);
  await ev(`document.querySelector('.sidebar-tree').scrollLeft = 0`);
  assert.ok(await ev(`document.querySelector('${file} .file-path').textContent.trim() === ${JSON.stringify(CONTEXT_FILE)}`), "The file header shows the full path");

  // Top, before and after the discussion window, and the end of the file.
  assert.equal(await ev(`document.querySelectorAll('${file} .diff-gap').length`), 4, "Hidden lines are marked above, between and after the hunks");
  assert.ok(!(await ev(shown(20))) && !(await ev(shown(90))), "Only changed lines and their context are shown at first");
  assert.ok(await ev(`[...document.querySelectorAll('${file} [data-thread]')].some(t => t.innerText.includes('Context discussion'))`)
    && await ev(shown(40)), "A discussion on an unchanged line opens beside its code");
  await screenshot("initial", width);

  // Expanders reveal lines toward the change they extend.
  await click(`${file} .diff-gap[data-gap-index="1"] button[aria-label="Show 20 more lines below"]`);
  await until(`${shown(28)} && !${shown(29)}`);
  await click(`${file} button[aria-label="Show 1 hidden line"]`);
  await until(shown(1));
  await click(`${file} .diff-gap[data-gap-index="3"] button[aria-label="Show 20 more lines below"]`);
  await until(`[...document.querySelectorAll('${file} .diff-line')].some(l => l.textContent.includes('line 82'))`);
  await screenshot("expanded", width);

  // Collapsed files stay collapsed when another action re-renders the diff.
  await click(`${file} .diff-file-toggle`);
  await until(`document.querySelector('${file}').hasAttribute('data-collapsed') && document.querySelector('${file} .diff-file-body').hidden`);
  await click(`${file} button[aria-label="Show all lines"]`);
  await until(`!document.querySelector('${file} button[aria-label="Show all lines"]')`);
  assert.ok(await ev(`document.querySelector('${file}').hasAttribute('data-collapsed') && document.querySelector('${file} .diff-file-body').hidden
    && document.querySelector('${file} .diff-file-toggle').getAttribute('aria-expanded') === 'false'`), "A collapsed file stays collapsed after an update");
  await click('.diff-sidebar button[aria-label="Expand all files"]');
  await until(`!document.querySelector('${file}').hasAttribute('data-collapsed')`);
  assert.equal(await ev(`document.querySelectorAll('${file} .diff-gap').length`), 0, "Show all lines leaves nothing hidden");
  assert.ok(await ev(`(() => {
    const texts = new Set([...document.querySelectorAll('${file} .diff-line code')].map(c => c.textContent));
    return Array.from({ length: 100 }, (_, i) => i + 1).every(n => n === 5 || texts.has('line ' + n));
  })()`), "Every line of the file is shown");

  // The file header stays visible while its lines scroll past.
  const sticky = await ev(`(() => {
    const main = ${scroller};
    const section = document.querySelector('${file}');
    main.scrollTop += section.getBoundingClientRect().top - main.getBoundingClientRect().top + 900;
    return document.querySelector('${file} .diff-file-header').getBoundingClientRect().top - main.getBoundingClientRect().top;
  })()`);
  assert.ok(Math.abs(sticky) <= 1, `The file header sticks to the top of the scroll area (${sticky})`);
  await until(`document.querySelector('.diff-sidebar [role="treeitem"][data-path="${CONTEXT_FILE}"]').getAttribute('aria-selected') === 'true'`);
  await screenshot("sticky", width);

  // Back to top returns from deep in the diff.
  await until(`!document.querySelector('.back-to-top').hidden`);
  await click(".back-to-top");
  await until(`${scroller}.scrollTop === 0 && document.querySelector('.back-to-top').hidden`);

  // Hiding expansions restores the compact diff while keeping discussion context.
  await click(`${file} button[aria-label="Hide unchanged lines"]`);
  await until(`!${shown(20)} && ${shown(40)}`);
  assert.equal(await ev(`document.querySelectorAll('${file} .diff-gap').length`), 4, "The discussion window keeps hidden lines on both sides");

  if (width > 980) {
    const before = await ev(`document.querySelector('.diff-sidebar').getBoundingClientRect().width`);
    const grip = await ev(`(() => {
      const handle = document.querySelector('.sidebar-resize');
      handle.scrollIntoView({ block: 'center' });
      const r = handle.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      return { x, y, width: r.width, target: document.elementFromPoint(x, y) === handle,
        visible: getComputedStyle(handle, '::after').backgroundColor !== 'rgba(0, 0, 0, 0)' };
    })()`);
    assert.ok(grip.width >= 12 && grip.target && grip.visible, "The divider has a visible, reachable drag grip");
    await call("Input.dispatchMouseEvent", { type: "mouseMoved", x: grip.x, y: grip.y });
    await call("Input.dispatchMouseEvent", { type: "mousePressed", x: grip.x, y: grip.y, button: "left", clickCount: 1 });
    await call("Input.dispatchMouseEvent", { type: "mouseMoved", x: grip.x + 100, y: grip.y, button: "left", buttons: 1 });
    await call("Input.dispatchMouseEvent", { type: "mouseReleased", x: grip.x + 100, y: grip.y, button: "left", clickCount: 1 });
    await until(`Math.round(document.querySelector('.diff-sidebar').getBoundingClientRect().width) === ${Math.round(before + 100)}`);
    await ev(`document.querySelector('.sidebar-resize').focus()`);
    await pressKey("ArrowRight");
    await pressKey("ArrowRight");
    const after = await ev(`document.querySelector('.diff-sidebar').getBoundingClientRect().width`);
    assert.equal(Math.round(after - before), 132, "The file tree resizes by dragging and with the keyboard");
    await click(`${file} button[aria-label="Show all lines"]`);
    await until(`!document.querySelector('${file} button[aria-label="Show all lines"]')`);
    assert.equal(Math.round(await ev(`document.querySelector('.diff-sidebar').getBoundingClientRect().width`)), Math.round(after), "The resized width survives a server update");
    assert.equal(await ev(`localStorage.getItem('git-ui:diff-sidebar-width')`), String(Math.round(after)), "The tree width is remembered");
    await nav("/pulls/pull-context?repo=repo&number=12&view=files");
    assert.equal(Math.round(await ev(`document.querySelector('.diff-sidebar').getBoundingClientRect().width`)), Math.round(after), "The remembered width applies on load");
    await ev(`document.querySelector('.sidebar-resize').focus()`);
    await pressKey("End");
    await call("Emulation.setDeviceMetricsOverride", { width: 1024, height: 900, deviceScaleFactor: 1, mobile: false });
    await until(`Number(document.querySelector('.sidebar-resize').getAttribute('aria-valuenow')) <= Math.round(document.querySelector('.diff-workspace').clientWidth * 0.6)`);
    await call("Emulation.setDeviceMetricsOverride", { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await until(`document.querySelector('.sidebar-resize').getAttribute('aria-valuenow') === '640'`);
    await screenshot("tree-resized", width);
    const reset = await ev(`(() => { const r = document.querySelector('.sidebar-resize').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
    for (const clickCount of [1, 2]) {
      await call("Input.dispatchMouseEvent", { type: "mousePressed", ...reset, button: "left", clickCount });
      await call("Input.dispatchMouseEvent", { type: "mouseReleased", ...reset, button: "left", clickCount });
    }
    await until(`document.querySelector('.sidebar-resize').getAttribute('aria-valuenow') === '300'`);
    assert.equal(await ev(`localStorage.getItem('git-ui:diff-sidebar-width')`), null, "Double-click resets the saved width");
  }

  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "The review page has no horizontal overflow");

  await nav("/compare?repo=repo&base=main&head=feature%2Fone");
  assert.ok(await ev(`document.querySelector('.diff-sidebar [role="treeitem"][data-path="a.txt"]') && document.querySelector('.diff-file[data-path="a.txt"] .diff-file-header')`), "Compare uses the same review workspace");
  await screenshot("compare", width);
}
assert.deepEqual(errors, [], "No browser exceptions on review pages");
console.log("Review browser checks passed at 1440 and 390px.");
ws.close();
