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
  console.error("PR browser state:", await ev(`JSON.stringify({ url: location.href, active: document.activeElement?.outerHTML.slice(0, 250), trigger: document.querySelector('.line-action')?.outerHTML.slice(0, 250) })`));
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
const editRoot = '[data-bml-component="git-pull-edit"]';
const editDialog = `${editRoot} dialog[open]`;
async function openEdit() {
  await click(`${editRoot} button[aria-label="Edit pull request"]`);
  await until(`document.querySelector(${JSON.stringify(editDialog)}) && document.querySelector(${JSON.stringify(editDialog + ' input[name="title"]')})`);
  assert.equal(await ev(`document.activeElement?.name`), "title", "Edit dialog focuses the title");
}
async function chooseEditBranch(name) {
  await click(`${editDialog} [data-bml-component="ui-select"]:has(select[name="${name}"]) [data-bml-ref="trigger"]`);
  await pressKey("End");
  await pressKey("Enter");
}
async function fillComment(text) {
  await ev(`(() => {
    const field = document.querySelector('.comment-form textarea');
    field.value = ${JSON.stringify(text)};
    field.dispatchEvent(new Event('input', { bubbles: true }));
  })()`);
}
async function submitCommentShortcut() {
  await ev(`document.querySelector('.comment-form textarea').focus()`);
  for (const type of ["keyDown", "keyUp"]) {
    await call("Input.dispatchKeyEvent", {
      type, key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, modifiers: 2,
    });
  }
}
const headerLayouts = [];
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
  assert.equal(await ev(`getComputedStyle(document.querySelector('.page-heading .badge')).color`), "rgb(52, 217, 154)", "Open PRs use Studio's green status badge");
  assert.ok(await ev(`document.querySelector('.heading-subtitle').textContent.includes('#1 · feature/one → main')`), "The subtitle names the number and branches");
  assert.ok(await ev(`(() => {
    const sidebar = document.querySelector('.pull-sidebar');
    return sidebar.innerText.includes('Mergeable') && sidebar.querySelector('.merge-form')
      && document.querySelector('.pull-main .pull-checks') && !sidebar.querySelector('.pull-checks');
  })()`), "Details and actions use Studio's sidebar while checks stay in the main column");
  assert.ok(await ev(`document.querySelector('.review-summary-row')?.innerText.includes('comment only')`), "Overview shows recent review outcomes");
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "PR overview fits the viewport");
  const overview = await ev(`(() => {
    const main = document.querySelector('.pull-main').getBoundingClientRect();
    const side = document.querySelector('.pull-sidebar').getBoundingClientRect();
    return { mainRight: main.right, sideLeft: side.left, sideWidth: side.width, mainBottom: main.bottom, sideTop: side.top };
  })()`);
  headerLayouts.push({ width, ...overview });
  if (width >= 1024) {
    assert.ok(overview.sideLeft > overview.mainRight && Math.abs(overview.sideWidth - 320) < 1, "Desktop overview uses Studio's 320px sidebar");
  } else {
    assert.ok(overview.sideTop >= overview.mainBottom, "Phone overview stacks the sidebar below the main column");
  }
  await screenshot("overview", width);

  await ev(`(() => {
    const select = document.querySelector('${actionRoot} .merge-form select[name=strategy]');
    select.value = 'SQUASH'; select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await click(`${actionRoot} .merge-form button[type=submit]`);
  await until(`document.querySelector('${actionRoot} dialog[open]')?.matches(':modal') && document.querySelector('${actionRoot} .merge-plan')`);
  assert.ok(await ev(`document.querySelector('${actionRoot} dialog[open]').innerText.includes('Squash and merge')`), "Merge confirmation names the chosen method");
  await screenshot("merge-confirmation", width);
  await pressKey("Escape");

  await ev(`(() => {
    document.querySelector('.page-heading h1').textContent =
      'Improve pull request reviews, dependency merge plans, and repository status checks';
    document.querySelector('.heading-subtitle').textContent =
      '#1 · feature/improve-pull-request-review-controls-and-status-layout → main';
    window.scrollTo(0, 0);
    document.querySelector('main').scrollTop = 0;
  })()`);
  assert.ok(
    await ev(`document.documentElement.scrollWidth <= innerWidth
      && document.querySelector('.page-heading').scrollWidth <= document.querySelector('.page-heading').clientWidth`),
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
    assert.equal(Math.round(workspace.treeWidth), 300, "Changed-file navigation starts at its default resizable width");
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
  assert.ok(await ev(`document.activeElement === document.querySelector('.comment-form textarea')`), "Opening a line comment focuses its field");
  assert.equal(await ev(`document.querySelector('.comment-form textarea').getAttribute('aria-label')`), "Comment on a.txt:1", "The field identifies its selected file and line");
  assert.ok(await ev(`document.querySelector('.comment-form button[type="submit"]').disabled
    && !document.querySelector('.comment-form select')
    && !document.querySelector('.comment-form [data-bml-component="ui-card"]')`), "Empty comments are disabled without a review picker or nested card");
  const composer = await ev(`(() => {
    const f = document.querySelector('.comment-form').getBoundingClientRect();
    const input = document.querySelector('.comment-form textarea').getBoundingClientRect();
    const file = document.querySelector('.diff-file').getBoundingClientRect();
    const code = document.querySelector('.diff-line code').getBoundingClientRect();
    return { height: f.height, width: f.width, fileWidth: file.width,
      fieldLeft: input.left, codeLeft: code.left, fieldRight: input.right, fileRight: file.right };
  })()`);
  assert.ok(composer.height <= 160, "The empty composer uses a compact inline layout");
  assert.ok(Math.abs(composer.width - composer.fileWidth) <= 2, "The composer fills the diff width");
  assert.ok(Math.abs(composer.fieldRight - (composer.fileRight - 13)) <= 1, "Field and footer use the same right inset");
  if (width >= 1024) assert.ok(Math.abs(composer.fieldLeft - composer.codeLeft) < 1, "The editor aligns with the code after the line gutters");
  await screenshot("inline-comment", width);
  await fillComment("   ");
  assert.ok(await ev(`document.querySelector('.comment-form button[type="submit"]').disabled`), "Whitespace cannot be submitted");
  await fillComment("Draft on line one");
  await click('.diff-line button[aria-label="Comment on line 2"]');
  await until(`document.querySelector('.comment-form textarea')?.getAttribute('aria-label') === 'Comment on a.txt:2'`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "", "A different line starts with its own draft");
  await fillComment("Draft on line two");
  await click(comment);
  await until(`document.querySelector('.comment-form textarea')?.getAttribute('aria-label') === 'Comment on a.txt:1'`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Draft on line one", "Returning to a line restores its draft");
  await click('.inline-thread button[type="submit"]');
  await until(`!document.querySelector('[data-bml-action-pending]')
    && document.querySelector('[data-bml-component="ui-toast"] [data-bml-ref="message"]').textContent === 'Thread resolved.'`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Draft on line one", "Unrelated diff updates retain an unsent comment");
  await fillComment(Array.from({ length: 25 }, (_, i) => `Comment line ${i + 1}`).join("\n"));
  assert.equal(await ev(`document.querySelector('.comment-form textarea').getBoundingClientRect().height`), 320, "Long drafts grow within a bounded field");
  await click('.comment-form button[aria-label="Cancel line comment"]');
  await until(`!document.querySelector('.comment-form') && document.activeElement?.getAttribute('aria-label') === 'Comment on line 1'`);
  await click(comment);
  await until(`document.querySelector('.comment-form textarea')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "", "Cancelling discards that line's draft");
  await click('.comment-form button[aria-label="Cancel line comment"]');
  await until(`!document.querySelector('.comment-form')`);

  const toast = '[data-bml-component="ui-toast"]';
  const toastMessage = `${toast} [data-bml-ref="message"]`;
  const toastDismiss = `${toast} button[aria-label="Dismiss notification"]`;
  const resolve = '.inline-thread button[type="submit"]';
  const diffOffset = () => ev(`document.querySelector('.diff-file').getBoundingClientRect().top
    - document.querySelector('.review-toolbar').getBoundingClientRect().bottom`);
  const beforeNotice = await diffOffset();
  await click(resolve);
  await until(`document.querySelector('${toast}')?.matches(':popover-open')
    && document.querySelector('${toastMessage}').textContent === 'Thread resolved.'
    && !document.querySelector('[data-bml-action-pending]')`);
  assert.equal(await diffOffset(), beforeNotice, "Success feedback does not move the diff");
  assert.ok(await ev(`!document.querySelector('.review-workspace > [role="alert"]')`), "Successful thread actions have no inline banner");
  assert.ok(await ev(`!document.querySelector('${toast}').contains(document.activeElement)`), "Toasts do not take focus");
  assert.equal(await ev(`document.querySelector('${toastMessage}').getAttribute('aria-live')`), "polite", "Success is announced politely");
  await ev(`new Promise(r => setTimeout(r, 280))`);
  const toastBounds = await ev(`(() => {
    const r = document.querySelector('${toast}').getBoundingClientRect();
    return { left: r.left, right: r.right, top: r.top, width: r.width };
  })()`);
  assert.ok(toastBounds.left >= 16 && toastBounds.right <= width - 16, "Toast fits the viewport");
  assert.ok(Math.abs(toastBounds.top - 750) < 1, "Toast follows Studio's floating position");
  await screenshot("thread-toast", width);

  await ev(`document.querySelector('${toastDismiss}').focus()`);
  await until(`[...document.querySelectorAll('[role=tooltip]')].some(t => t.matches(':popover-open') && t.textContent === 'Dismiss notification')`);
  if (width === 1440) {
    await ev(`new Promise(r => setTimeout(r, 4200))`);
    assert.ok(await ev(`document.querySelector('${toast}').matches(':popover-open')`), "Focused notifications do not expire");
  }
  await pressKey("Escape");
  await until(`!document.querySelector('${toast}').matches(':popover-open')`);
  assert.ok(await ev(`document.activeElement !== document.body
    && !document.querySelector('${toast}').contains(document.activeElement)`), "Keyboard dismissal returns focus to the review workspace");

  await click(comment);
  await until(`document.querySelector('.comment-form textarea') && !document.querySelector('[data-bml-action-pending]')`);
  assert.ok(await ev(`!document.querySelector('${toast}').matches(':popover-open')`), "Opening a comment does not replay the previous notice");
  await fillComment("Reject comment");
  await click('.comment-form button[type="submit"]');
  await until(`document.querySelector('.comment-form [role="alert"]')?.innerText.includes('Comment rejected')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Reject comment", "Inline errors retain the draft");
  assert.ok(await ev(`document.activeElement === document.querySelector('.comment-form textarea')
    && document.querySelector('.comment-form textarea').getAttribute('aria-invalid') === 'true'
    && !document.querySelector('.review-workspace > [role="alert"]')`), "Comment failures focus the field and stay inside the composer");
  assert.equal(await ev(`document.querySelector('.comment-form [role="alert"]').getBoundingClientRect().top
    - document.querySelector('.comment-form textarea').getBoundingClientRect().bottom`), 8, "Comment errors use the compact field spacing");
  await screenshot("inline-comment-error", width);
  assert.ok(await ev(`!document.querySelector('${toast}').matches(':popover-open')`), "Failed actions do not produce success toasts");
  await fillComment("Saved comment");
  let submitWidth;
  if (width === 1440) {
    submitWidth = await ev(`document.querySelector('.comment-form button[type="submit"]').getBoundingClientRect().width`);
    await call("Fetch.enable", { patterns: [{ urlPattern: `${base}/_bml/action`, requestStage: "Request" }] });
    holdNextAction = true;
  }
  await submitCommentShortcut();
  if (width === 1440) {
    await until(`document.querySelector('.comment-form textarea').readOnly
      && document.querySelector('.comment-form button[type="submit"]').disabled`);
    assert.ok(heldAction, "The comment request is held to inspect its pending state");
    assert.ok(await ev(`[...document.querySelectorAll('.comment-form button')].every(button => button.disabled)`), "Saving disables actions until the comment finishes");
    assert.equal(await ev(`document.querySelector('.comment-form button[type="submit"]').getBoundingClientRect().width`), submitWidth, "Saving preserves the button width");
    assert.equal(await ev(`getComputedStyle(document.querySelector('.comment-form button[type="submit"] .button-progress')).display`), "flex", "Saving shows the shared spinner");
    assert.ok(await ev(`document.querySelector('.comment-form [role="alert"]').hidden`), "Retry progress hides the previous rejection");
    await screenshot("inline-comment-saving", width);
    await call("Fetch.continueRequest", { requestId: heldAction });
    heldAction = null;
    await call("Fetch.disable");
  }
  await until(`!document.querySelector('.comment-form')
    && document.querySelector('${toast}').matches(':popover-open')
    && document.querySelector('${toastMessage}').textContent === 'Comment added.'`);
  await ev(`window.savedToast = document.querySelector('${toast}')`);
  await click(resolve);
  await until(`document.querySelector('${toastMessage}').textContent === 'Thread resolved.'
    && !document.querySelector('[data-bml-action-pending]')`);
  assert.ok(await ev(`window.savedToast === document.querySelector('${toast}')`), "The toast survives island replacement");
  await click(toastDismiss);
  await until(`!document.querySelector('${toast}').matches(':popover-open')`);
  await click(resolve);
  await until(`document.querySelector('${toast}').matches(':popover-open')`);
  assert.equal(await ev(`document.querySelector('${toastMessage}').textContent`), "Thread resolved.", "A repeated successful action displays the same message again");
  if (width === 1440) {
    const bounds = await ev(`(() => { const r = document.querySelector('${toast}').getBoundingClientRect(); return { x: r.left + 30, y: r.top + 25 }; })()`);
    await call("Input.dispatchMouseEvent", { type: "mouseMoved", ...bounds });
    await ev(`new Promise(r => setTimeout(r, 4200))`);
    assert.ok(await ev(`document.querySelector('${toast}').matches(':popover-open')`), "Hovered notifications do not expire");
    await call("Input.dispatchMouseEvent", { type: "mouseMoved", x: 4, y: 4 });
    await until(`!document.querySelector('${toast}').matches(':popover-open')`);
    await call("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] });
    await click(resolve);
    await until(`document.querySelector('${toast}').matches(':popover-open')`);
    assert.equal(await ev(`getComputedStyle(document.querySelector('${toast} .toast')).animationName`), "none", "Reduced motion disables the toast entrance");
    await call("Emulation.setEmulatedMedia", { features: [] });
  }

  await nav("/pulls/pull?repo=repo&number=1&view=reviews");
  const reviewDialog = '[data-bml-component="git-pull-review"] dialog[open]';
  await click('.page-heading a[href*="review=true"]');
  await until(`document.querySelector('${reviewDialog}')?.matches(':modal')`);
  await ev(`document.querySelector('${reviewDialog} textarea[name="body"]').value = 'Review accepted'`);
  await click(`${reviewDialog} button[type="submit"]`);
  await until(`!document.querySelector('${reviewDialog}') && document.querySelector('${toast}').matches(':popover-open')
    && document.querySelector('${toastMessage}').textContent === 'Review submitted.'`);
  assert.ok(await ev(`!document.querySelector('${toast}').contains(document.activeElement)`), "A completed review closes its dialog without focusing the toast");
  await screenshot("review-toast", width);

  await ev(`fetch('/_fixture/replies/reset', { method: 'POST' }).then(r => r.text())`);
  await nav("/pulls/pull-replies?repo=repo&number=10&view=files");
  const discussion = '[data-thread="comment"]';
  const replyAction = 'button[data-comment-anchor="reply:comment"]';
  const oldReplyAction = 'button[data-comment-anchor="reply:old-comment"]';
  assert.ok(await ev(`document.querySelector('${discussion}').innerText.includes('Resolved')`), "Resolved discussions can be answered");
  await click(replyAction);
  await until(`document.activeElement === document.querySelector('.comment-form textarea')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').placeholder`), "Write a reply…");
  assert.ok(await ev(`document.querySelectorAll('.comment-form').length === 1
    && document.querySelector('.comment-form').closest('${discussion}')
    && !document.querySelector('.comment-form').innerText.includes('Submit a review')`), "Replies open inside their discussion without a separate review prerequisite");
  assert.ok(await ev(`!document.querySelector('${discussion} .thread-actions')`), "The open editor has one reply action");
  await screenshot("reply-editor", width);
  await fillComment("Draft reply");
  await click('.comment-form button[aria-label="Cancel reply"]');
  await until(`!document.querySelector('.comment-form') && document.activeElement?.dataset.commentAnchor === 'reply:comment'`);
  await click(replyAction);
  await until(`document.querySelector('.comment-form textarea')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "", "Cancelling discards the reply draft");
  await fillComment("Reject reply");
  await click('.comment-form button[type="submit"]');
  await until(`document.querySelector('.comment-form [role="alert"]')?.textContent.includes('Reply rejected')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Reject reply");
  await screenshot("reply-rejected", width);
  await fillComment(`Reply at ${width}px <script>literal text</script>`);
  await submitCommentShortcut();
  await until(`!document.querySelector('.comment-form')
    && document.querySelector('${discussion}').textContent.includes('Reply at ${width}px')
    && document.querySelector('${toastMessage}').textContent === 'Reply added.'`);
  assert.ok(await ev(`document.querySelector('${discussion}').textContent.includes('Bob · @bob')
    && document.querySelector('${discussion}').textContent.includes('Alice · @alice')
    && document.querySelector('${discussion}').textContent.includes('<script>literal text</script>')
    && !document.querySelector('${discussion} script')`), "Both authors and safe reply content appear in one discussion");
  assert.equal(await ev(`document.querySelectorAll('${discussion} [data-comment-id]').length`), 2, "Replies append in the original discussion");
  assert.ok(await ev(`!document.querySelector('${discussion}').innerText.includes('Resolved')`), "A new reply reopens a resolved discussion");
  assert.equal(await ev(`document.activeElement?.dataset.commentAnchor`), "reply:comment", "Posting a reply returns focus to its action");
  await screenshot("reply-posted", width);
  await click(`${discussion} .thread-actions button[type="submit"]`);
  await until(`document.querySelector('${discussion}').innerText.includes('Resolved')
    && !document.querySelector('[data-bml-action-pending]')`);
  await click(replyAction);
  await until(`document.querySelector('.comment-form textarea')`);
  await fillComment(`Follow-up at ${width}px`);
  await submitCommentShortcut();
  await until(`document.querySelectorAll('${discussion} [data-comment-id]').length === 3 && !document.querySelector('.comment-form')`);
  assert.ok(await ev(`!document.querySelector('${discussion}').innerText.includes('Resolved')`), "Resolved replies reopen with another response");

  await nav("/pulls/pull-replies?repo=repo&number=10&view=reviews");
  assert.ok(await ev(`(() => {
    const review = document.querySelector('.review');
    const heading = review.querySelector('.review-heading');
    const thread = review.querySelector('.review-thread');
    const author = review.querySelector('.review-header strong').getBoundingClientRect();
    const time = review.querySelector('.review-time').getBoundingClientRect();
    return getComputedStyle(thread).borderRadius === '0px'
      && getComputedStyle(thread).backgroundColor === 'rgba(0, 0, 0, 0)'
      && thread.getBoundingClientRect().width === review.getBoundingClientRect().width
      && heading.getBoundingClientRect().height < 110
      && Math.abs(author.top + author.height / 2 - time.top - time.height / 2) < 2;
  })()`), "Reviews use full-width discussion rows with aligned metadata rather than nested cards");
  await screenshot("reviews-discussions", width);
  const locationLink = `${discussion} [data-bml-ref="locationLink"]`;
  await ev(`document.querySelector('${locationLink}').focus()`);
  await until(`document.querySelector('.ui-tooltip:popover-open')?.innerText === 'View discussion on a.txt:1 in Files Changed'`);
  await click(locationLink);
  await until(`new URL(location.href).searchParams.get('view') === 'files'
    && location.hash === '#discussion-comment' && document.activeElement?.id === 'discussion-comment'`);
  assert.ok(await ev(`(() => {
    const target = document.querySelector('#discussion-comment');
    const bounds = target.getBoundingClientRect();
    return !target.closest('.diff-file').hasAttribute('data-collapsed') && bounds.top >= 0 && bounds.bottom <= innerHeight
      && getComputedStyle(target).boxShadow !== 'none';
  })()`), "A review location opens, focuses and highlights the exact discussion in Files Changed");
  await screenshot("discussion-linked", width);
  const history = await call("Page.getNavigationHistory");
  await call("Page.navigateToHistoryEntry", { entryId: history.entries[history.currentIndex - 1].id });
  await until(`new URL(location.href).searchParams.get('view') === 'reviews' && document.readyState === 'complete'
    && document.querySelector('${locationLink}')`);
  await ev(`document.querySelector('${locationLink}').focus()`);
  await pressKey("Enter");
  await until(`location.hash === '#discussion-comment' && document.activeElement?.id === 'discussion-comment'`);
  await call("Page.reload", { ignoreCache: true });
  await until(`document.readyState === 'complete' && document.activeElement?.id === 'discussion-comment'`);
  await ev(`document.querySelector('.diff-file-toggle').click();
    history.replaceState(null, '', location.pathname + location.search);
    location.hash = 'discussion-comment'`);
  await until(`!document.querySelector('.diff-file').hasAttribute('data-collapsed') && document.activeElement?.id === 'discussion-comment'`);
  await click(replyAction);
  await until(`document.activeElement === document.querySelector('.comment-form textarea')`);
  await fillComment("A draft after following the discussion link");
  await click('.comment-form button[aria-label="Cancel reply"]');
  await until(`!document.querySelector('.comment-form') && document.activeElement?.dataset.commentAnchor === 'reply:comment'`);
  await nav("/pulls/pull-replies?repo=repo&number=10&view=reviews");
  await click(oldReplyAction);
  await until(`document.activeElement?.getAttribute('aria-label') === 'Reply to comment on old name.kt:3'`);
  await fillComment("Draft in old discussion");
  await click(replyAction);
  await until(`document.querySelector('.comment-form textarea')?.getAttribute('aria-label') === 'Reply to comment on a.txt:1'`);
  await fillComment("Draft in current discussion");
  await click(oldReplyAction);
  await until(`document.querySelector('.comment-form textarea')?.getAttribute('aria-label') === 'Reply to comment on old name.kt:3'`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Draft in old discussion", "Each discussion retains its own draft");
  await fillComment(`Outdated response at ${width}px`);
  await submitCommentShortcut();
  await until(`!document.querySelector('.comment-form') && document.querySelector('[data-thread="old-comment"]').textContent.includes('Outdated response at ${width}px')`);
  assert.ok(await ev(`(() => {
    const thread = document.querySelector('[data-thread="old-comment"]');
    const link = new URL(thread.querySelector('[data-thread-source]').href);
    return thread.innerText.includes('Outdated') && link.searchParams.get('ref') === 'old-sha'
      && link.searchParams.get('file') === 'old name.kt' && link.searchParams.get('line') === '3';
  })()`), "Outdated responses retain their pinned original location");
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "Review discussions fit the viewport");
  await click(toastDismiss);
  await until(`!document.querySelector('${toast}').matches(':popover-open')`);
  await screenshot("reply-outdated", width);
  await click('[data-thread="old-comment"] [data-bml-ref="locationLink"]');
  await until(`location.hash === '#discussion-old-comment' && document.activeElement?.id === 'discussion-old-comment'`);
  assert.ok(await ev(`document.querySelector('#discussion-old-comment').closest('.detached-discussions')
    && !document.querySelector('.diff-file').innerText.includes('Outdated response at ${width}px')`), "Outdated discussions are reachable separately without attaching them to current diff lines");
  await screenshot("discussion-outdated-linked", width);
  await click(oldReplyAction);
  await until(`document.activeElement?.getAttribute('aria-label') === 'Reply to comment on old name.kt:3'`);
  await click('.comment-form button[aria-label="Cancel reply"]');
  await until(`!document.querySelector('.comment-form') && document.activeElement?.dataset.commentAnchor === 'reply:old-comment'`);
  const fileThread = '[data-thread="file-comment"]';
  assert.ok(await ev(`document.querySelectorAll('${fileThread}').length === 1
    && document.querySelector('${fileThread}').closest('.detached-discussions')
    && !document.querySelector('.diff-file').innerText.includes('File-level discussion')`), "A file-level discussion appears once outside individual diff lines");
  await click(`${fileThread} [data-bml-ref="locationLink"]`);
  await until(`location.hash === '#discussion-file-comment' && document.activeElement?.id === 'discussion-file-comment'`);
  await click('button[data-comment-anchor="reply:file-comment"]');
  await until(`document.activeElement?.getAttribute('aria-label') === 'Reply to comment on a.txt'`);
  await click('.comment-form button[aria-label="Cancel reply"]');
  await until(`!document.querySelector('.comment-form') && document.activeElement?.dataset.commentAnchor === 'reply:file-comment'`);
  await nav("/pulls/pull-replies?repo=repo&number=10&view=files#discussion-not-loaded");
  assert.ok(await ev(`!document.activeElement?.hasAttribute('data-thread')`), "An unavailable discussion fragment does not focus another thread");
  await ev(`fetch('/_fixture/replies/reset', { method: 'POST' }).then(r => r.text())`);

  await nav("/pulls/pull-first-comment?repo=repo&number=9&view=files");
  await click(comment);
  await until(`document.querySelector('.comment-form textarea')`);
  assert.ok(await ev(`document.querySelector('.comment-form').innerText.includes('Submit a review to start commenting.')
    && !document.querySelector('.comment-form button[type="submit"]')`), "First-time reviewers have a clear prerequisite in the same compact editor");
  await fillComment("Keep this draft while reviewing");
  await click('.comment-form button:has(svg)');
  await until(`document.querySelector('${reviewDialog}')`);
  await ev(`document.querySelector('${reviewDialog} textarea[name="body"]').value = 'Rejected'`);
  await click(`${reviewDialog} button[type="submit"]`);
  await until(`document.querySelector('${reviewDialog}')?.innerText.includes('Review rejected')`);
  await pressKey("Escape");
  await until(`!document.querySelector('${reviewDialog}') && document.activeElement === document.querySelector('.comment-form textarea')`);
  assert.equal(await ev(`document.querySelector('.comment-form textarea').value`), "Keep this draft while reviewing", "Review-dialog actions do not discard the line draft");
  await screenshot("inline-comment-first-review", width);
  await click('.comment-form button[aria-label="Cancel line comment"]');
  await until(`!document.querySelector('.comment-form')`);

  await nav("/pulls/pull-draft?repo=repo&number=2");
  assert.ok(await ev(`document.body.innerText.includes('Mark Ready for Review') && !document.querySelector('.page-heading a[href*="review=true"]')`), "Drafts offer readiness rather than unavailable review actions");
  await screenshot("draft", width);
  await openEdit();
  assert.ok(await ev(`!document.querySelector(${JSON.stringify(editDialog + ' select[name="source"]')}).disabled`), "Draft branches can be edited");
  await screenshot("edit-draft", width);
  await pressKey("Escape");
  await nav("/pulls/pull-merged?repo=repo&number=3");
  await nav("/pulls/pull-merged?repo=repo&number=3&view=files");
  assert.ok(await ev(`document.querySelector('.diff-line') && document.querySelector('main').innerText.includes('Second diff line')
    && !document.querySelector('main').innerText.includes('Comparison unavailable')
    && !document.querySelector('main').innerText.includes('No files changed')`), "Merged files remain visible when both branches have been deleted");
  assert.ok(await ev(`document.querySelector('.detached-discussions').innerText.includes('Existing line comment')
    && document.querySelector('.detached-discussions').innerText.includes('Earlier revision')
    && !document.querySelector('.diff-file').innerText.includes('Existing line comment')`), "Original review comments stay with their source revision rather than moving to merged line positions");
  await screenshot("merged-files", width);
  await nav("/pulls/pull-merged-no-base?repo=repo&number=11&view=files");
  assert.ok(await ev(`document.querySelector('main').innerText.includes('The original target commit for this merge is not recorded.')
    && !document.querySelector('main').innerText.includes('No files changed')`), "An unavailable historical base is distinguished from an empty merge");
  await nav("/pulls/pull-merged?repo=repo&number=3");
  assert.equal(await ev(`getComputedStyle(document.querySelector('.page-heading .badge')).color`), "rgb(167, 139, 255)", "Merged PRs use Studio's purple status badge");
  assert.ok(await ev(`!document.body.innerText.includes('Close pull request')`), "Merged PRs have no close action");
  await screenshot("merged", width);
  await openEdit();
  assert.ok(await ev(`!document.querySelector(${JSON.stringify(editDialog + ' select[name="source"]')})
    && document.querySelector(${JSON.stringify(editDialog + ' input[name="source"]')}).type === 'hidden'`), "Merged requests keep metadata editing and fixed branches");
  await screenshot("edit-merged", width);
  await pressKey("Escape");
  await nav("/pulls/pull-closed?repo=repo&number=4");
  assert.ok(await ev(`[...document.querySelectorAll('.pull-sidebar button')].some(b => b.textContent.trim() === 'Reopen')`), "Closed PRs can be reopened");
  await screenshot("closed", width);
  await nav("/pulls/pull-conflicts?repo=repo&number=5");
  assert.ok(await ev(`document.querySelector('${actionRoot} .merge-form button[type=submit]').disabled`), "Known conflicts disable the merge trigger");
  assert.ok(await ev(`document.querySelector('[aria-label^="Remove assignee Alice"]') && document.querySelector('[aria-label="Remove dependency #2"]')`), "Metadata actions have specific labels");
  await screenshot("conflicts", width);

  await nav("/pulls/pull-empty?repo=repo&number=6&view=files");
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

  await nav("/pulls/pull-missing-branch?repo=repo&number=8&view=files");
  assert.ok(await ev(`document.querySelector('main').innerText.includes('Comparison unavailable')
    && !document.querySelector('main').innerText.includes('No files changed')`), "A deleted branch has a distinct comparison state");
  await openEdit();
  assert.ok(await ev(`document.querySelector(${JSON.stringify(editDialog + ' select[name="source"]')}).selectedOptions[0].textContent.includes('(unavailable)')`), "Unavailable branches remain visible in an editable PR");
  await chooseEditBranch("source");
  assert.equal(await ev(`document.querySelector(${JSON.stringify(editDialog + ' select[name="source"]')}).value`), "feature/one", "A live branch can replace the deleted branch");
  await screenshot("edit-missing-branch", width);
  await pressKey("Escape");

  const editPath = "/pulls/pull-edit?repo=repo&number=7&view=files";
  await nav(editPath);
  await openEdit();
  await screenshot("edit", width);
  const original = await ev(`(() => {
    const dialog = document.querySelector(${JSON.stringify(editDialog)});
    return { title: dialog.querySelector('input[name="title"]').value,
      description: dialog.querySelector('textarea[name="description"]').value };
  })()`);
  await pressKey("Escape");
  await until(`!document.querySelector(${JSON.stringify(editDialog)}) && document.activeElement?.getAttribute('aria-label') === 'Edit pull request'`);
  await openEdit();
  await ev(`document.querySelector(${JSON.stringify(editDialog + ' input[name="title"]')}).value = 'Reject edit';
    document.querySelector(${JSON.stringify(editDialog + ' textarea[name="description"]')}).value = 'Draft description';`);
  await chooseEditBranch("source");
  await chooseEditBranch("target");
  await click(`${editDialog} button[type="submit"]`);
  await until(`document.querySelector(${JSON.stringify(editDialog)})?.innerText.includes('Rejected pull request edit')`);
  assert.deepEqual(await ev(`(() => {
    const dialog = document.querySelector(${JSON.stringify(editDialog)});
    return { title: dialog.querySelector('input[name="title"]').value,
      description: dialog.querySelector('textarea[name="description"]').value,
      source: dialog.querySelector('select[name="source"]').value,
      target: dialog.querySelector('select[name="target"]').value };
  })()`), { title: "Reject edit", description: "Draft description", source: "main", target: "feature/one" }, "Rejected edits retain all four fields");
  assert.ok(await ev(`document.querySelector(${JSON.stringify(editDialog)}).scrollWidth <= document.querySelector(${JSON.stringify(editDialog)}).clientWidth`), "Edit dialog fits the viewport");
  await screenshot("edit-rejected", width);
  await ev(`document.querySelector(${JSON.stringify(editDialog + ' input[name="title"]')}).value = 'Edited PR at ${width}px';
    document.querySelector(${JSON.stringify(editDialog + ' textarea[name="description"]')}).value = '';`);
  await click(`${editDialog} button[type="submit"]`);
  await until(`location.href === ${JSON.stringify(base + editPath)} && document.querySelector('.page-heading h1')?.innerText.startsWith('Edited PR at ${width}px') && !document.querySelector(${JSON.stringify(editDialog)})`);
  assert.ok(await ev(`document.querySelector('.heading-subtitle').innerText.includes('main') && document.querySelector('.heading-subtitle').innerText.includes('feature/one')`), "Saved branch context refreshes");
  await nav(editPath.replace("view=files", "view=overview"));
  assert.ok(await ev(`document.querySelector('main').innerText.includes('No description provided.')`), "A description can be cleared");
  await nav(editPath);
  await openEdit();
  await ev(`document.querySelector(${JSON.stringify(editDialog + ' input[name="title"]')}).value = ${JSON.stringify(original.title)};
    document.querySelector(${JSON.stringify(editDialog + ' textarea[name="description"]')}).value = ${JSON.stringify(original.description)};`);
  await chooseEditBranch("source");
  await chooseEditBranch("target");
  await click(`${editDialog} button[type="submit"]`);
  await until(`document.querySelector('.page-heading h1')?.innerText.startsWith(${JSON.stringify(original.title)}) && !document.querySelector(${JSON.stringify(editDialog)})`);
}
for (const width of [1440, 390]) {
  await call("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: false });
  await nav("/pulls/pull?view=commits");
  assert.ok(await ev(`document.querySelector('main').innerText.includes('Commit 24')`));
  await click('a.commit-message');
  await until(`location.search.includes('commit=' + 'b'.repeat(40)) && document.querySelector('.commit-comparison')?.innerText.includes('Selected commit message')`);
  assert.ok(await ev(`document.querySelector('main').innerText.includes('Selected commit change')`));
  assert.equal(await ev(`document.querySelectorAll('[aria-label^="Comment on line"]').length`), 0);
  assert.ok(await ev(`document.documentElement.scrollWidth <= innerWidth`), "Commit comparison fits viewport");
  await screenshot("commit-files", width);
  await click('.commit-comparison nav a');
  await until(`!location.search.includes('commit=') && document.querySelector('[aria-label^="Comment on line"]')`);
  await nav("/pulls/pull?view=commits&commitOffset=25");
  assert.ok(await ev(`document.querySelector('main').innerText.includes('Commit 25')`));
  assert.ok(await ev(`!document.querySelector('main').innerText.includes('Load more commits')`));
  await screenshot("commit-page", width);
}
assert.deepEqual(errors, [], "No browser exceptions on PR pages");
await writeFile(
  `${artifacts}/pull-header-layout.json`,
  JSON.stringify(headerLayouts, null, 2),
);
console.log("PR browser checks passed at 1920, 1440, 1024 and 390px.");
ws.close();
