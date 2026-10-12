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
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
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
  console.error("PR browser state:", await ev(`JSON.stringify({ active: document.activeElement?.outerHTML, trigger: document.querySelector('.line-action')?.outerHTML })`));
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
  await writeFile(`${artifacts}/pull-${name}-${width}.png`, Buffer.from(result.data, "base64"));
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
const actionRoot = '[data-bml-component="git-pull-actions"]';
for (const width of [1920, 1440, 1024, 390]) {
  await call("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: false });
  await nav("/pulls");
  assert.ok(
    await ev(`document.querySelector('.pull-meta').innerText.includes('Active project')
      && !document.body.innerText.includes('Deleted project')`),
    "PR repository labels exclude deleted records",
  );
  await screenshot("list", width);
  await nav("/pulls/pull?repo=repo&number=1");
  assert.equal(await ev(`getComputedStyle(document.querySelector('.pull-title .badge')).color`), "rgb(52, 217, 154)", "Open PRs use Studio's green status badge");
  assert.ok(await ev(`document.querySelector('.pull-number').getBoundingClientRect().left - (() => { const range=document.createRange(); range.selectNodeContents(document.querySelector('h1').firstChild); return range.getBoundingClientRect().right; })() >= 7`), "PR number has space after the title");
  assert.ok(
    await ev(`(() => {
      const label = document.querySelector('.pull-dates > span');
      const range = document.createRange();
      range.selectNodeContents(label.firstChild);
      return label.querySelector('time').getBoundingClientRect().left
        - range.getBoundingClientRect().right >= 3;
    })()`),
    "Date labels have space before their timestamps",
  );
  assert.ok(await ev(`document.querySelector('.merge-panel .pull-checks') && !document.querySelector('.pull-sidebar .merge-panel')`), "Checks and merge readiness are grouped in the main column");
  assert.ok(await ev(`document.querySelector('.review-summary-row')?.innerText.includes('Commented')`), "Overview shows recent review outcomes");
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "PR overview fits the viewport");
  await screenshot("overview", width);

  const trigger = `${actionRoot} [data-bml-component="ui-dialog"] [data-bml-ref="trigger"]`;
  await click(trigger);
  await until(`document.querySelector('${actionRoot} dialog[open]')?.matches(':modal')`);
  await ev(`(() => {
    const select = document.querySelector('${actionRoot} dialog[open] select[name=strategy]');
    select.value = 'SQUASH'; select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await click(`${actionRoot} dialog[open] button[type=submit]`);
  await until(`document.querySelector('${actionRoot} .merge-plan')`);
  assert.ok(await ev(`document.querySelector('${actionRoot} dialog[open]').innerText.includes('Squash and merge')`), "Merge confirmation names the chosen method");
  await screenshot("merge-confirmation", width);
  await pressKey("Escape");

  await nav("/pulls/pull?repo=repo&number=1&view=files");
  const comment = '.diff-line button[aria-label="Comment on line 1"]';
  await ev(`document.querySelector(${JSON.stringify(comment)}).focus()`);
  assert.equal(await ev(`document.querySelector(${JSON.stringify(comment)}).getBoundingClientRect().height`), 24, "Diff comments use a compact icon button");
  await until(`[...document.querySelectorAll('[role=tooltip]')].some(t => t.matches(':popover-open') && t.textContent === 'Comment on line 1')`);
  assert.ok(await ev(`document.querySelector('[role=tooltip]').innerText.includes('Comment on line 1')`), "Line action has a focus tooltip");
  await click(comment);
  await until(`document.querySelector('.comment-form textarea') && !document.querySelector('[data-bml-action-pending]')`);
  assert.ok(await ev(`document.querySelector('.comment-form').closest('.diff-file') !== null`), "Line editor appears inside the selected diff");
  await screenshot("inline-comment", width);
  await click('.comment-form button[aria-label="Cancel line comment"]');
  await until(`!document.querySelector('.comment-form')`);

  await nav("/pulls/pull-draft?repo=repo&number=2");
  assert.ok(await ev(`document.body.innerText.includes('Ready for review') && !document.body.innerText.includes('Review changes')`), "Drafts offer readiness rather than unavailable review actions");
  await screenshot("draft", width);
  await nav("/pulls/pull-merged?repo=repo&number=3");
  assert.equal(await ev(`getComputedStyle(document.querySelector('.pull-title .badge')).color`), "rgb(167, 139, 255)", "Merged PRs use Studio's purple status badge");
  assert.ok(await ev(`!document.body.innerText.includes('Close pull request')`), "Merged PRs have no close action");
  await screenshot("merged", width);
  await nav("/pulls/pull-closed?repo=repo&number=4");
  assert.ok(await ev(`document.body.innerText.includes('Reopen pull request')`), "Closed PRs can be reopened");
  await screenshot("closed", width);
  await nav("/pulls/pull-conflicts?repo=repo&number=5");
  assert.ok(await ev(`document.querySelector('${actionRoot} [data-bml-ref=trigger]').disabled`), "Known conflicts disable the merge trigger");
  assert.ok(await ev(`document.querySelector('[aria-label^="Remove assignee Alice"]') && document.querySelector('[aria-label="Remove dependency #2"]')`), "Metadata actions have specific labels");
  await screenshot("conflicts", width);
}
assert.deepEqual(errors, [], "No browser exceptions on PR pages");
console.log("PR browser checks passed at 1920, 1440, 1024 and 390px.");
ws.close();
