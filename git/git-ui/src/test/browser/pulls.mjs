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
const headerLayouts = [];
const timestampLayouts = [];
async function checkTimestampText(width, state) {
  const pairs = await ev(`(() => [...document.querySelectorAll('.pull-dates > span')].map(pair => {
    const label = pair.querySelector('span');
    const time = pair.querySelector('time');
    const textBounds = node => {
      const range = document.createRange();
      range.selectNodeContents(node);
      const rect = range.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom };
    };
    const a = getComputedStyle(label), b = getComputedStyle(time);
    return { label: label.innerText, value: time.innerText,
      labelFont: a.font, timeFont: b.font,
      labelText: textBounds(label), timeText: textBounds(time) };
  }))()`);
  for (const pair of pairs) {
    assert.equal(pair.labelFont, pair.timeFont, "Timestamp labels and values share typography");
    assert.ok(
      Math.abs(pair.labelText.top - pair.timeText.top) < 0.1
        && Math.abs(pair.labelText.bottom - pair.timeText.bottom) < 0.1,
      "Timestamp text aligns, including fresh 'just now' values",
    );
  }
  timestampLayouts.push({ width, state, pairs });
}
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
  await checkTimestampText(width, "open");
  const header = await ev(`(() => {
    const heading = document.querySelector('.pull-heading').getBoundingClientRect();
    const context = document.querySelector('.pull-meta').getBoundingClientRect();
    const dates = document.querySelector('.pull-dates').getBoundingClientRect();
    return {
      height: heading.height,
      contextCenter: context.top + context.height / 2,
      datesCenter: dates.top + dates.height / 2,
      contextBottom: context.bottom,
      datesTop: dates.top,
      datesRight: dates.right,
      right: heading.right,
    };
  })()`);
  headerLayouts.push({ width, ...header });
  if (width >= 1024) {
    assert.ok(header.height <= 90, "Desktop PR headers use two compact content rows");
    assert.ok(
      Math.abs(header.contextCenter - header.datesCenter) <= 1,
      "Author/branch context and timestamps share a desktop centerline",
    );
    assert.ok(
      Math.abs(header.datesRight - header.right) <= 1,
      "Timestamps align with the header's right edge",
    );
  } else {
    assert.ok(
      header.datesTop >= header.contextBottom && header.datesTop - header.contextBottom <= 8,
      "Phone metadata wraps with compact spacing",
    );
  }
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

  await ev(`(() => {
    document.querySelector('.pull-title h1').firstChild.textContent =
      'Improve pull request reviews, dependency merge plans, and repository status checks';
    document.querySelector('.pull-meta code').textContent =
      'feature/improve-pull-request-review-controls-and-status-layout';
    window.scrollTo(0, 0);
    document.querySelector('main').scrollTop = 0;
  })()`);
  assert.ok(
    await ev(`document.documentElement.scrollWidth <= innerWidth
      && document.querySelector('.pull-heading').scrollWidth <= document.querySelector('.pull-heading').clientWidth`),
    "Long titles and branch names stay within the header",
  );
  await screenshot("header-long-content", width);

  await nav("/pulls/pull?repo=repo&number=1&view=files");
  const workspace = await ev(`(() => {
    const tree = document.querySelector('.diff-sidebar').getBoundingClientRect();
    const panel = document.querySelector('.diff-file').getBoundingClientRect();
    return { treeWidth: tree.width, treeTop: tree.top, treeRight: tree.right,
      panelTop: panel.top, panelLeft: panel.left,
      toolbarHeight: document.querySelector('.review-toolbar').getBoundingClientRect().height };
  })()`);
  assert.ok(workspace.toolbarHeight <= 32, "Files use one compact review toolbar");
  if (width >= 1024) {
    assert.equal(workspace.treeWidth, 220, "Changed-file navigation has a bounded sidebar");
    assert.ok(Math.abs(workspace.treeTop - workspace.panelTop) < 1, "Tree and diff start together");
    assert.ok(workspace.panelLeft > workspace.treeRight, "Diff is beside the file navigation");
  } else {
    assert.ok(workspace.panelTop > workspace.treeTop, "Phone file navigation stacks above the diff");
  }
  await screenshot("files", width);
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

  await nav("/pulls/pull-empty?repo=repo&number=6&view=files");
  await checkTimestampText(width, "fresh-empty-file");
  assert.ok(
    await ev(`document.querySelector('.diff-notice').innerText === 'Empty file added.'
      && !document.querySelector('.diff-hunk-heading')
      && !document.querySelector('.diff-line')`),
    "An empty added file has a clear notice without a zero-line hunk",
  );
  await click('.diff-sidebar button[aria-label="Collapse Test"]');
  await until(`document.querySelector('.diff-sidebar [data-path="Test/.gitkeep"]').hidden`);
  await pressKey("ArrowRight");
  await until(`document.activeElement?.getAttribute('aria-label') === 'Test/.gitkeep'`);
  await pressKey("Enter");
  await until(`decodeURIComponent(location.hash) === '#diff-Test/.gitkeep'`);
  await ev(`window.scrollTo(0, 0); document.querySelector('main').scrollTop = 0`);
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`));
  await screenshot("empty-file", width);
}
assert.deepEqual(errors, [], "No browser exceptions on PR pages");
await writeFile(
  `${artifacts}/pull-header-layout.json`,
  JSON.stringify(headerLayouts, null, 2),
);
await writeFile(`${artifacts}/pull-timestamp-layout.json`, JSON.stringify(timestampLayouts, null, 2));
console.log("PR browser checks passed at 1920, 1440, 1024 and 390px.");
ws.close();
