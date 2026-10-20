import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const port = new URL(base).port;
const runId = Date.now().toString(36);
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
  errors = [],
  pausedRequests = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Fetch.requestPaused") pausedRequests.push(m.params);
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
  const diagnostic = await ev(`JSON.stringify({
    url: location.href,
    active: document.activeElement?.outerHTML,
    modal: document.querySelector('dialog[open]')?.outerHTML,
    pending: [...document.querySelectorAll('[data-bml-action-pending]')].map(e => e.outerHTML),
  })`);
  console.error("Screen timeout state:", diagnostic);
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
const foundationTags = ["button", "input", "field", "card", "switch", "alert", "badge", "spinner", "loading-state", "icon", "confirm-dialog", "section-heading", "table-scroll", "disclosure-heading", "empty-state", "time", "bosca-mark", "page-shell", "page-heading"];
const foundationPrefix = `bml-${Buffer.from("io.bosca:bml-design").toString("hex")}-`;
const seenFoundations = new Set();
const clientControls = ["icon-button", "icon-link", "tooltip", "copy", "copy-field", "popover", "dialog", "select", "search-input", "segmented-control", "toast", "app-header", "tabs", "view-tabs"];
const seenClientControls = new Set();
async function checkFoundationComponents() {
  const mounts = await ev(`[...document.querySelectorAll('[data-bml-component]')].map(e=>e.dataset.bmlComponent)`);
  for (const tag of clientControls) {
    assert.ok(!mounts.includes(`ui-${tag}`), `Git must mount the library ${tag}`);
    if (mounts.includes(foundationPrefix + tag)) seenClientControls.add(tag);
  }
  assert.ok(
    await ev(`(() => {
      const roots = [...document.querySelectorAll('[data-bml-design-dialog]')];
      return roots.every(root => {
        const panel = root.querySelector(':scope > dialog');
        const trigger = root.querySelector(':scope > [data-bml-ref=trigger]');
        return panel?.id && panel.hasAttribute('data-bml-design-dialog-panel') &&
          (!trigger || (trigger.getAttribute('aria-controls') === panel.id && trigger.getAttribute('aria-haspopup') === 'dialog'));
      }) && new Set(roots.map(root => root.querySelector(':scope > dialog').id)).size === roots.length;
    })()`),
    "Library dialogs own unique native panels and expose their relationship on each trigger",
  );
  const ids = await ev(`[...document.querySelectorAll('[data-bml-c]')].map(e=>e.getAttribute('data-bml-c'))`);
  for (const tag of foundationTags) {
    assert.ok(!ids.includes(`ui-${tag}`), `Git must render the library ${tag}`);
    if (ids.includes(foundationPrefix + tag)) seenFoundations.add(tag);
  }
}
async function checkFoundationPalette() {
  const changed = await ev(`(() => {
    const input = document.querySelector('dialog[open] input[name=title]');
    const button = document.querySelector('dialog[open] button.primary[type=submit]');
    const field = input.closest('.field');
    const controls = [input, button, field];
    const dimensions = () => controls.map(e=>{const r=e.getBoundingClientRect();return [r.width,r.height]});
    const before = dimensions();
    const style = document.createElement('style');
    style.textContent = '.git-document, .git-document dialog { --control-bg: #eaf2f5; --accent: #225566; --fg-0: #225566; --fg-2: #304858; --bg-1: #edf3f5; --bg-2: #d9e8ed; --fg-1: #16323c; --line-2: #557b88; }';
    document.head.append(style);
    try {
      return {before, after:dimensions(), background:getComputedStyle(input).backgroundColor,
        primary:getComputedStyle(button).color, label:getComputedStyle(field).color,
        panel:getComputedStyle(input.closest('dialog')).backgroundColor,
        foreground:getComputedStyle(input.closest('dialog')).color,
        border:getComputedStyle(input.closest('dialog')).borderColor,
        footer:getComputedStyle(input.closest('dialog').querySelector('.modal-footer')).backgroundColor};
    } finally { style.remove(); }
  })()`);
  assert.equal(changed.background, "rgb(234, 242, 245)");
  assert.equal(changed.primary, "rgb(34, 85, 102)");
  assert.equal(changed.label, "rgb(48, 72, 88)");
  assert.equal(changed.panel, "rgb(237, 243, 245)");
  assert.equal(changed.foreground, "rgb(22, 50, 60)");
  assert.equal(changed.border, "rgb(85, 123, 136)");
  assert.equal(changed.footer, "rgb(217, 232, 237)");
  assert.deepEqual(changed.after, changed.before, "Consumer CSS changes colors without changing control geometry");
}
async function checkSelectPalette(selector) {
  const theme = await ev(`(() => {
    const widget = document.querySelector(${JSON.stringify(selector)});
    const trigger = widget.querySelector('.select-trigger'), popup = widget.querySelector('.select-popup');
    const controls = [widget, trigger, popup, ...popup.querySelectorAll('[role=option]')];
    const dimensions = () => controls.map(e=>{const r=e.getBoundingClientRect();return [r.x,r.y,r.width,r.height]});
    const before = dimensions(), style = document.createElement('style');
    style.textContent = '.git-document [data-bml-design-select], .git-document [data-bml-design-select] [popover] { --select-active-border:#347a91; --fg-4:#347a91; --bg-2:#f0f5f7; --fg-1:#1f4554; --fg-0:#183745; --accent:#347a91; } [data-bml-design-select] [role=combobox] { transition:none; }';
    document.head.append(style);
    try {
      return {before,after:dimensions(),owned:widget.contains(popup),border:getComputedStyle(trigger).borderColor,
        background:getComputedStyle(popup).backgroundColor,color:getComputedStyle(popup).color};
    } finally { style.remove(); }
  })()`);
  assert.ok(theme.owned, "The top-layer select popup stays in its owning theme subtree");
  assert.equal(theme.border, "rgb(52, 122, 145)");
  assert.equal(theme.background, "rgb(240, 245, 247)");
  assert.equal(theme.color, "rgb(31, 69, 84)");
  assert.deepEqual(theme.after, theme.before, "Consumer CSS recolors select surfaces without moving controls/options");
}
async function checkIconTooltip() {
  const appearance = await ev(`(() => {
    const trigger = document.querySelector('.file-toolbar [aria-label="Download file"]');
    const tooltip = document.querySelector('.bosca-tooltip:popover-open');
    const style = document.createElement('style');
    const before = trigger.getBoundingClientRect();
    const border = getComputedStyle(tooltip).borderTopColor;
    style.textContent = '.file-toolbar { --tooltip-bg: #edf6f8; --tooltip-fg: #214858; --tooltip-border: #527987; }';
    document.head.append(style);
    try {
      const theme = getComputedStyle(tooltip), bounds = tooltip.getBoundingClientRect();
      const after = trigger.getBoundingClientRect();
      return {owned:trigger.contains(tooltip), described:trigger.getAttribute('aria-describedby').split(/\\s+/).includes(tooltip.id),
        border, background:theme.backgroundColor, color:theme.color, themedBorder:theme.borderTopColor,
        before:[before.width,before.height], after:[after.width,after.height],
        fits:bounds.left>=8 && bounds.right<=document.documentElement.clientWidth-8 && bounds.top>=8 && bounds.bottom<=innerHeight-8};
    } finally { style.remove(); }
  })()`);
  assert.equal(appearance.owned, true, "The library tooltip stays inside its action and inherits consumer CSS");
  assert.equal(appearance.described, true);
  assert.equal(appearance.border, "rgb(46, 52, 71)", "Git keeps its existing tooltip border color");
  assert.equal(appearance.background, "rgb(237, 246, 248)");
  assert.equal(appearance.color, "rgb(33, 72, 88)");
  assert.equal(appearance.themedBorder, "rgb(82, 121, 135)");
  assert.deepEqual(appearance.before, [30, 30], "Icon links keep their 30px geometry");
  assert.deepEqual(appearance.after, appearance.before);
  assert.equal(appearance.fits, true);
  const point = await ev(`(() => {const box=document.querySelector('.bosca-tooltip:popover-open').getBoundingClientRect();return {x:box.left+box.width/2,y:box.top+box.height/2}})()`);
  await call("Input.dispatchMouseEvent", { type:"mouseMoved", ...point });
  await new Promise(resolve=>setTimeout(resolve, 300));
  assert.ok(await ev(`Boolean(document.querySelector('.bosca-tooltip:popover-open'))`), "Tooltip text remains available while hovered");
}
// Check rendered dimensions, including inherited typography and component wrappers.
async function checkControlLayout(scope = "main") {
  await checkFoundationComponents();
  const dimensions = await ev(`(() => {
    const root = document.querySelector(${JSON.stringify(scope)});
    return [...root.querySelectorAll('.input, .select-trigger, .button, .segmented-control [role=radio]')]
      .filter(e => e.getBoundingClientRect().height > 0)
      .map(e => ({
        label: e.getAttribute('aria-label') || e.getAttribute('name') || e.textContent.trim(),
        height: e.getBoundingClientRect().height,
        expected: e.classList.contains('size-xs') || e.closest('.segmented-control') ? 24
          : e.classList.contains('size-sm') || e.closest('.select-widget.size-sm') ? 30 : 32,
      }));
  })()`);
  for (const control of dimensions) {
    assert.ok(
      Math.abs(control.height - control.expected) < 1,
      `${scope} ${control.label}: expected ${control.expected}px, measured ${control.height}px`,
    );
  }
  const contents = await ev(`(() => {
    const root = document.querySelector(${JSON.stringify(scope)});
    const centerY = bounds => bounds.top + bounds.height / 2;
    return [...root.querySelectorAll('.button, .button-link, .select-trigger, .popover > summary, .segmented-control [role=radio]')]
      .filter(control => control.checkVisibility())
      .map(control => {
        const bounds = control.getBoundingClientRect();
        const walker = document.createTreeWalker(control, NodeFilter.SHOW_TEXT);
        const labels = [];
        const contentBounds = [];
        while (walker.nextNode()) {
          const node = walker.currentNode;
          if (!node.textContent.trim() || !node.parentElement.checkVisibility({opacityProperty:true,visibilityProperty:true})) continue;
          const range = document.createRange();
          range.selectNodeContents(node);
          if (range.getBoundingClientRect().height > 0) {
            const textBounds = range.getBoundingClientRect();
            labels.push(centerY(textBounds) - centerY(bounds));
            contentBounds.push(textBounds);
          }
        }
        const icons = [...control.querySelectorAll('svg, .spinner')]
          .filter(icon => icon.checkVisibility({opacityProperty:true,visibilityProperty:true}))
          .map(icon => icon.getBoundingClientRect());
        contentBounds.push(...icons);
        return {
          label: control.getAttribute('aria-label') || control.textContent.trim(),
          labels,
          icons: icons.map(icon => centerY(icon) - centerY(bounds)),
          horizontalOffset: getComputedStyle(control).justifyContent === 'center' && contentBounds.length
            ? (Math.min(...contentBounds.map(r => r.left)) + Math.max(...contentBounds.map(r => r.right))) / 2
                - (bounds.left + bounds.width / 2)
            : null,
        };
      });
  })()`);
  for (const control of contents) {
    for (const offset of [...control.labels, ...control.icons]) {
      assert.ok(
        Math.abs(offset) <= 0.5,
        `${scope} ${control.label}: text and icons must be vertically centered, measured offset ${offset}px`,
      );
    }
    if (control.horizontalOffset !== null) {
      assert.ok(
        Math.abs(control.horizontalOffset) <= 0.5,
        `${scope} ${control.label}: icon and label must be centered together, measured offset ${control.horizontalOffset}px`,
      );
    }
  }
  const rows = await ev(`(() => {
    const root = document.querySelector(${JSON.stringify(scope)});
    const rowSelector = '.toolbar, .ref-toolbar, .repository-heading-actions, .repository-controls, .search-options, .copy-field-row';
    const controlSelector = '.input, .select-trigger, .button, .popover > summary, .segmented-control';
    return [...root.querySelectorAll(rowSelector)]
      .map(row => [...row.querySelectorAll(controlSelector)]
        .filter(e => e.getBoundingClientRect().height > 0
          && !e.closest('.popover-content, dialog, .select-popup'))
        .map(e => {
          const bounds = e.getBoundingClientRect();
          const parent = e.parentElement;
          const parentBounds = parent.getBoundingClientRect();
          const style = getComputedStyle(parent);
          return {
            label: e.textContent.trim() || e.name,
            top: bounds.top,
            bottom: bounds.bottom,
            height: bounds.height,
            parent: parent.className,
            parentHeight: parentBounds.height,
            parentTop: parentBounds.top,
            display: style.display,
            align: style.alignItems,
            padding: style.padding,
            margin: style.margin,
          };
        }))
      .filter(row => row.length > 1);
  })()`);
  for (const row of rows) {
    // Wrapped mobile rows may have different tops; controls sharing a row must align.
    for (let i = 0; i < row.length; i++)
      for (const other of row.slice(i + 1)) {
        const control = row[i];
        if (
          Math.abs(control.top - other.top) <
          Math.min(control.height, other.height) / 2
        ) {
          assert.ok(
            Math.abs(control.top - other.top) < 1 &&
              Math.abs(control.bottom - other.bottom) < 1,
            `Controls sharing a row must align: ${JSON.stringify(row)}`,
          );
        }
      }
  }
  return contents;
}
const paths = [
  "/repositories",
  "/pulls",
  "/pulls?status=CLOSED",
  "/repositories/repo?file=README.md",
  "/repositories/repo?file=docs%2Fa.txt",
  "/repositories/repo?new=true",
  "/repositories/repo?tab=commits",
  "/repositories/repo?tab=branches",
  "/repositories/repo?tab=tags",
  "/search?repo=repo&q=image",
  "/search",
  "/search?repo=repo&q=paths&mode=paths&ref=refs%2Ftags%2Fv1",
  "/search?repo=repo&q=snippet",
  "/search?repo=repo&q=nomatches",
  "/search?repo=repo&q=fail",
  "/search?repo=empty&q=snippet",
  "/compare?repo=repo&base=main&head=feature%2Fone",
  "/statuses?repo=repo&sha=abc",
  "/pulls?repo=repo&new=true",
  "/pulls/pull?repo=repo&number=1",
  "/pulls/pull?repo=repo&number=1&view=files",
  "/pulls/pull?repo=repo&number=1&view=reviews",
  "/pipelines",
  "/pulls?new=true",
  "/repositories/repo?tab=pulls",
  "/repositories/repo?tab=pipelines",
  "/repositories/repo?tab=statuses&sha=abc",
  "/repositories/repo?tab=secrets",
  "/repositories/repo?tab=settings",
  "/repositories/repo?tab=settings&panel=protection",
  "/repositories/repo?tab=pipelines&pipeline=pipeline",
  "/repositories/empty?tab=pipelines",
  "/pipelines?repo=repo&pipeline=pipeline&ref=refs%2Fheads%2Fmain",
  "/pipelines/run",
  "/repositories/repo/settings",
  "/repositories/repo/settings?panel=webhooks",
  "/repositories/repo/settings?panel=protection",
  "/repositories/repo/settings?panel=permissions",
  "/repositories/repo/settings?panel=utilities",
  "/repositories/repo/secrets",
  "/ci-agents",
  "/ci-agents/agent",
  "/ci-agents/orchestrator",
  "/repositories/repo/github",
  "/repositories/repo/github?view=pulls",
  "/repositories/repo/github?view=deliveries",
  "/settings/github",
  "/repositories/empty",
  "/repositories/empty?tab=branches",
  "/repositories/empty?tab=tags",
  "/repositories/empty/settings?panel=webhooks",
  "/repositories/empty/settings?panel=protection",
  "/repositories/empty/secrets",
  "/pipelines?repo=empty",
  "/ci-agents?status=OFFLINE",
];
const results = [];
const nativeSelectFallbacks = [];
const nativePopoverFallbacks = [];
const loadingStates = [];
async function holdRequests(path) {
  pausedRequests.length = 0;
  await call("Fetch.enable", {
    patterns: [{ urlPattern: base + path, requestStage: "Request" }],
  });
}
async function waitForHeldRequest() {
  for (let i = 0; i < 100; i++) {
    if (pausedRequests.length) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw Error("The expected loading request did not start");
}
async function releaseRequests() {
  for (const request of pausedRequests.splice(0))
    await call("Fetch.continueRequest", { requestId: request.requestId });
  await call("Fetch.disable");
}
async function captureLoading(name, width, selector) {
  const geometry = await ev(`(() => {
    const root=document.querySelector(${JSON.stringify(selector)});
    const spinner=root.querySelector('.spinner');
    const icon=spinner.getBoundingClientRect(), bounds=root.getBoundingClientRect();
    const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);
    let label;
    while(walker.nextNode()) if(walker.currentNode.textContent.trim() && walker.currentNode.parentElement.checkVisibility({opacityProperty:true})) {
      const range=document.createRange();range.selectNodeContents(walker.currentNode);label=range.getBoundingClientRect();break;
    }
    return {name:${JSON.stringify(name)},viewport:${width},width:bounds.width,
      spinnerVisible:spinner.checkVisibility({opacityProperty:true}),spinnerSize:getComputedStyle(spinner).width,
      centered:Math.abs((icon.top+icon.height/2)-(bounds.top+bounds.height/2)),
      textOffset:label?Math.abs((icon.top+icon.height/2)-(label.top+label.height/2)):null,
      animation:getComputedStyle(spinner).animationDuration};
  })()`);
  assert.equal(
    geometry.spinnerVisible,
    true,
    "A pending request has visible progress",
  );
  assert.equal(geometry.spinnerSize, "14px");
  assert.ok(
    geometry.centered <= 0.5,
    "The spinner is centered in its loading row",
  );
  if (geometry.textOffset !== null)
    assert.ok(geometry.textOffset <= 0.5, "Loading text and spinner align");
  assert.equal(
    geometry.animation,
    "0.8s",
    "Every loading context uses the shared spinner",
  );
  loadingStates.push(geometry);
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-loading-${name}-${width}.png`,
    Buffer.from(
      (
        await call("Page.captureScreenshot", {
          format: "png",
          captureBeyondViewport: false,
        })
      ).data,
      "base64",
    ),
  );
}
async function inspectLoadingStates(width) {
  await holdRequests("/_bml/deferred/*");
  try {
    await nav("/repositories/repo");
    await waitForHeldRequest();
    await until(
      "document.querySelector('[data-bml-deferred][aria-busy=true] .loading-state')",
    );
    assert.ok(
      await ev(
        "!document.querySelector('[data-bml-deferred-retry]').checkVisibility()",
      ),
      "Retry is shown only after a failed load",
    );
    await captureLoading("statistics", width, ".stats-state .loading-state");
    await call("Emulation.setEmulatedMedia", {
      features: [{ name: "prefers-reduced-motion", value: "reduce" }],
    });
    assert.equal(
      await ev(
        "getComputedStyle(document.querySelector('.stats-state .spinner')).animationName",
      ),
      "none",
    );
    await call("Emulation.setEmulatedMedia", { features: [] });
  } finally {
    await releaseRequests();
  }
  await until(
    "document.querySelector('.repository-summary [data-bml-deferred]')?.dataset.bmlDeferredState==='loaded'",
  );
  assert.equal(
    await ev("Boolean(document.querySelector('.stats-state'))"),
    false,
    "Loaded statistics replace their progress state",
  );

  await nav("/repositories");
  await holdRequests("/_bml/contract/*");
  try {
    await ev(`(() => {
      const widget=document.querySelector('.owner-toolbar .select-widget');
      widget.querySelector('.select-trigger').click();
      const input=widget.querySelector('.select-query'); input.value='Alice';
      input.dispatchEvent(new Event('input',{bubbles:true}));
    })()`);
    await waitForHeldRequest();
    await captureLoading("owner", width, ".owner-toolbar .select-status-row");
    await pressKey("Escape");
    assert.ok(
      await ev(
        "document.querySelector('.owner-toolbar .select-popup').hidden && !document.querySelector('.owner-toolbar .select-popup').hasAttribute('aria-busy')",
      ),
      "Dismissing the search clears busy state",
    );
  } finally {
    await releaseRequests();
  }

  await nav("/search?repo=repo&q=snippet");
  await holdRequests("/_bml/action");
  try {
    await ev(`(() => {
      const input=document.querySelector('.search-workspace [name=q]'); input.value='image';
      input.dispatchEvent(new Event('input',{bubbles:true})); input.form.requestSubmit();
    })()`);
    await waitForHeldRequest();
    await captureLoading("search", width, "[data-bml-ref=loading]");
  } finally {
    await releaseRequests();
  }
  await until(
    "document.querySelector('[data-search-query]')?.dataset.searchQuery==='image' && document.querySelector('[data-bml-ref=loading]').hidden",
  );

  await nav("/repositories/repo?tab=branches");
  await ev(
    "[...document.querySelectorAll('main [data-bml-ref=trigger]')].find(button=>button.textContent.trim()==='New Branch').click()",
  );
  await until("document.querySelector('dialog[open]')?.matches(':modal')");
  await ev(
    "document.querySelector('dialog[open] input[name=name]').value='loading-check'",
  );
  const button = "dialog[open] button[type=submit]";
  const before = await ev(
    `(() => {const r=document.querySelector('${button}').getBoundingClientRect();return {width:r.width,height:r.height};})()`,
  );
  await holdRequests("/_bml/action");
  try {
    await ev("document.querySelector('dialog[open] form').requestSubmit()");
    await waitForHeldRequest();
    await captureLoading("action", width, button);
    const busy = await ev(
      `(() => {const r=document.querySelector('${button}').getBoundingClientRect();return {width:r.width,height:r.height};})()`,
    );
    assert.deepEqual(
      busy,
      before,
      "Loading preserves the action button's dimensions",
    );
    const target = await call("Runtime.evaluate", {
      expression: `document.querySelector('${button}')`,
      returnByValue: false,
    });
    const tree = await call("Accessibility.getPartialAXTree", {
      objectId: target.result.objectId,
      fetchRelatives: false,
    });
    assert.equal(
      tree.nodes[0].name.value,
      "Create branch",
      "Progress preserves the accessible action name",
    );
    await call("Fetch.failRequest", {
      requestId: pausedRequests.shift().requestId,
      errorReason: "Aborted",
    });
    await until(
      "!document.querySelector('dialog[open] form').hasAttribute('data-bml-action-pending')",
    );
    assert.ok(
      await ev(
        `getComputedStyle(document.querySelector('${button} .button-content')).opacity==='1' && !document.querySelector('${button} .button-progress').checkVisibility()`,
      ),
      "Rejected transport restores the action label",
    );
  } finally {
    await releaseRequests();
  }
  await pressKey("Escape");
}
async function inspectDialog(name, width) {
  await until(`document.querySelector('dialog[open]')?.matches(':modal')`);
  await checkControlLayout("dialog[open]");
  assert.ok(
    await ev(`(() => {
      const dialog = document.querySelector('dialog[open]');
      const bounds = dialog.getBoundingClientRect();
      const footer = dialog.querySelector('.modal-footer').getBoundingClientRect();
      return bounds.left >= 0 && bounds.right <= innerWidth + 1
        && bounds.top >= 0 && bounds.bottom <= innerHeight + 1
        && footer.bottom <= bounds.bottom;
    })()`),
    `${name}: modal and action footer fit the viewport`,
  );
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-${name}-${width}.png`,
    Buffer.from(
      (
        await call("Page.captureScreenshot", {
          format: "png",
          captureBeyondViewport: false,
        })
      ).data,
      "base64",
    ),
  );
  await ev(
    `document.querySelector('dialog[open] [data-bml-ref=cancel]').click()`,
  );
}
for (const width of [1920, 1440, 1024, 390]) {
  await call("Emulation.setDeviceMetricsOverride", {
    width,
    height: width === 390 ? 844 : 900,
    deviceScaleFactor: 1,
    mobile: width === 390,
  });
  await inspectLoadingStates(width);
  await call("Emulation.setScriptExecutionDisabled", { value: true });
  await call("Page.navigate", { url: base + "/repositories" });
  await until(
    "document.readyState === 'complete' && document.querySelector('.owner-toolbar select')",
  );
  await ev("document.fonts.ready");
  const nativeChevron = await ev(`(() => {
    const select = document.querySelector('.owner-toolbar select');
    const caret = select.nextElementSibling;
    const control = select.getBoundingClientRect();
    const icon = caret.querySelector('svg').getBoundingClientRect();
    return {appearance:getComputedStyle(select).appearance, enhanced:select.classList.contains('enhanced'),
      visible:caret.checkVisibility(), width:icon.width, height:icon.height,
      centerOffset:(icon.top+icon.height/2)-(control.top+control.height/2),
      rightInset:control.right-icon.right};
  })()`);
  assert.equal(
    nativeChevron.appearance,
    "none",
    "The native fallback replaces the browser arrow",
  );
  assert.equal(nativeChevron.enhanced, false);
  assert.equal(nativeChevron.visible, true);
  assert.equal(nativeChevron.width, 13);
  assert.equal(nativeChevron.height, 13);
  assert.ok(
    Math.abs(nativeChevron.centerOffset) <= 0.5,
    "The fallback chevron is centered",
  );
  assert.equal(nativeChevron.rightInset, 11);
  nativeSelectFallbacks.push({ viewport: width, ...nativeChevron });
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-native-select-${width}.png`,
    Buffer.from(
      (
        await call("Page.captureScreenshot", {
          format: "png",
          captureBeyondViewport: false,
        })
      ).data,
      "base64",
    ),
  );
  await call("Page.navigate", { url: base + "/repositories/repo" });
  await until(`location.pathname==='/repositories/repo' && document.readyState==='complete' && Boolean(document.querySelector('.repository-controls .popover summary'))`);
  await ev("document.fonts.ready");
  const fallbackPoint = await ev(`(() => {const r=document.querySelector('.repository-controls .popover summary').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()`);
  for (const type of ["mousePressed", "mouseReleased"])
    await call("Input.dispatchMouseEvent", {type, ...fallbackPoint, button:"left", clickCount:1});
  await until(`document.querySelector('.repository-controls .popover').open && document.querySelector('.popover-content').checkVisibility()`);
  const fallback = await ev(`(() => {
    const content=document.querySelector('.popover-content'), input=content.querySelector('input'), r=content.getBoundingClientRect();
    return {enhanced:content.hasAttribute('popover'), value:input.value, readonly:input.readOnly,
      left:r.left,right:r.right,viewport:document.documentElement.clientWidth};
  })()`);
  assert.equal(fallback.enhanced, false);
  assert.equal(fallback.readonly, true);
  assert.ok(fallback.value.endsWith('/repo.git'));
  assert.ok(fallback.left>=0 && fallback.right<=fallback.viewport+1, `Native clone disclosure fits: ${JSON.stringify(fallback)}`);
  nativePopoverFallbacks.push({width,...fallback});
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-native-clone-${width}.png`,
    Buffer.from((await call("Page.captureScreenshot",{format:"png",captureBeyondViewport:false})).data,"base64"));
  await call("Emulation.setScriptExecutionDisabled", { value: false });
  for (const path of paths) {
    await nav(path);
    if (path === "/pulls?repo=repo&new=true") await checkFoundationPalette();
    if (await ev("Boolean(document.querySelector('.repository-summary [data-bml-deferred]'))")) {
      await until("document.querySelector('.repository-summary [data-bml-deferred]')?.dataset.bmlDeferredState==='loaded'");
    }
    if (path === "/pulls") {
      const title = await ev(`(() => {
        const row = document.querySelector('.pull-row strong');
        const number = row.querySelector('code').getBoundingClientRect();
        const title = row.querySelector('span').getBoundingClientRect();
        return {gap:title.left-number.right, count:document.querySelector('.heading-subtitle').textContent.trim()};
      })()`);
      assert.equal(
        title.gap,
        6,
        "Pull numbers and titles use Studio's 6px gap",
      );
      assert.match(title.count, /^1 open across \d+ repositories$/);
    }
    await writeFile(
      `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-initial-${path.replace(/[^a-zA-Z0-9]+/g, "-")}-${width}.png`,
      Buffer.from(
        (
          await call("Page.captureScreenshot", {
            format: "png",
            captureBeyondViewport: false,
          })
        ).data,
        "base64",
      ),
    );
    if (await ev("Boolean(document.querySelector('dialog[open]'))")) {
      await inspectDialog(
        "initial-" + path.replace(/[^a-zA-Z0-9]+/g, "-"),
        width,
      );
    }
    const g = await ev(
      `({width:innerWidth,scroll:document.documentElement.scrollWidth,heading:document.querySelector('h1')?.textContent,overflow:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth+2&&!e.closest('.cm-scroller,.diff-lines,.table-scroll,.log-output')&&getComputedStyle(e).display!=='none').slice(0,7).map(e=>e.tagName+'.'+e.className)})`,
    );
    results.push({ viewport: width, path, ...g });
    if (await ev("Boolean(document.querySelector('.repository-heading'))")) {
      const header = await ev(`(() => {
        const bounds = document.querySelector('.repository-heading').getBoundingClientRect();
        const copy = document.querySelector('.repository-heading .heading-copy').getBoundingClientRect();
        const controls = document.querySelector('.repository-controls').getBoundingClientRect();
        const tabs = document.querySelector('nav[aria-label="Repository section"]').getBoundingClientRect();
        const stats = [...document.querySelectorAll('.repository-summary .stat-tile')].map(tile => tile.textContent.replace(/\\s+/g, ' ').trim());
        return {
          height: bounds.height,
          controlsCenter: controls.top + controls.height / 2,
          copyCenter: copy.top + copy.height / 2,
          tabGap: tabs.top - bounds.bottom,
          stats,
        };
      })()`);
      results.at(-1).repositoryHeader = header;
      assert.ok(Math.abs(header.tabGap) <= 1, "Repository tabs sit directly below Studio's page header");
      if (width >= 1024) {
        assert.ok(header.height <= 90, "Desktop repository headers stay compact");
        assert.ok(
          Math.abs(header.controlsCenter - header.copyCenter) <= 1,
          "Header controls center on the breadcrumb and title block, as in Studio",
        );
      }
      if (header.stats.length) {
        assert.ok(header.stats.some(tile => tile.includes('Tags') && tile.endsWith('1')), JSON.stringify(header.stats));
        assert.ok(header.stats.some(tile => tile.includes('Contributors') && tile.endsWith('1')), JSON.stringify(header.stats));
      }
    }
    const panelWidths =
      await ev(`(() => [...document.querySelectorAll('.settings-stack')]
      .filter(panel=>panel.checkVisibility()).map(panel=>{
        const parent=panel.parentElement, outer=parent.getBoundingClientRect(), style=getComputedStyle(parent);
        const inner=panel.getBoundingClientRect();
        return {left:inner.left,right:inner.right,
          expectedLeft:outer.left+parseFloat(style.borderLeftWidth)+parseFloat(style.paddingLeft),
          expectedRight:outer.left+parseFloat(style.borderLeftWidth)+parent.clientWidth-parseFloat(style.paddingRight)};
      }))()`);
    results.at(-1).panelWidths = panelWidths;
    for (const panel of panelWidths) {
      assert.ok(
        Math.abs(panel.left - panel.expectedLeft) < 1 &&
          Math.abs(panel.right - panel.expectedRight) < 1,
        `Panels fill their available width: ${JSON.stringify({ path, ...panel })}`,
      );
    }
    if (path.startsWith("/search")) {
      assert.ok(
        await ev(`(() => {
          const nav = document.querySelector('.git-navigation').getBoundingClientRect();
          const active = document.querySelector('.git-navigation a[aria-current=page]').getBoundingClientRect();
          return active.left >= nav.left && active.right <= nav.right;
        })()`),
        "The active Search navigation item stays visible at each viewport",
      );
    }
    if (g.scroll > width + 1)
      console.log("OVERFLOW", JSON.stringify(results.at(-1)));
    if (!g.heading) console.log("MISSING_HEADING", path);
    assert.ok(
      await ev(
        '[...document.querySelectorAll(".table-scroll th, .table-scroll button, .table-scroll code")].every(e => (() => { const range = document.createRange(); range.selectNodeContents(e); return range.getBoundingClientRect().height <= parseFloat(getComputedStyle(e).fontSize) * 2.5; })())',
      ),
      "Table headings, actions and identifiers remain readable rather than wrapping into vertical letters",
    );
    assert.ok(
      await ev(
        `[...document.querySelectorAll('main summary')].filter(e => e.checkVisibility()).every(e => getComputedStyle(e).listStyleType === 'none')`,
      ),
      "Expandable controls use styled headings rather than native triangles",
    );
    for (const tabLabel of await ev(
      '[...document.querySelectorAll("nav.tabs")].map(e => e.getAttribute("aria-label"))',
    )) {
      const selector = `nav.tabs[aria-label="${tabLabel}"]`;
      const tabBounds = await ev(`(() => {
        const tabs = document.querySelector('${selector}');
        const rect = tabs.getBoundingClientRect();
        const parent = tabs.parentElement;
        const bounds = parent.getBoundingClientRect();
        const style = getComputedStyle(parent);
        // Studio page-header tabs span the full page column; other tab rows fill the content box.
        const header = tabs.classList.contains('header-tabs');
        const page = tabs.closest('main').getBoundingClientRect();
        return {label:tabs.getAttribute('aria-label'), left:rect.left, right:rect.right,
          expectedLeft:header ? page.left : bounds.left+parseFloat(style.paddingLeft)+parseFloat(style.borderLeftWidth),
          expectedRight:header ? page.left + tabs.closest('main').clientWidth : bounds.left+parseFloat(style.borderLeftWidth)+parent.clientWidth-parseFloat(style.paddingRight),
          parentWidth:bounds.width, clientWidth:parent.clientWidth,
          border:getComputedStyle(tabs).borderBottomWidth};
      })()`);
      (results.at(-1).tabDividers ??= []).push(tabBounds);
      assert.ok(
        Math.abs(tabBounds.left - tabBounds.expectedLeft) <= 1 &&
          Math.abs(tabBounds.right - tabBounds.expectedRight) <= 1,
        `Tab dividers fill their content width: ${JSON.stringify(tabBounds)}`,
      );
      assert.equal(tabBounds.border, "1px");
      if (
        ["Repository state", "Pull request state"].includes(tabLabel) &&
        !path.startsWith("/repositories/repo")
      ) {
        const headingGap = await ev(`(() => {
          const heading = document.querySelector('main > .page-heading').getBoundingClientRect();
          return document.querySelector('${selector}').getBoundingClientRect().top-heading.bottom;
        })()`);
        results.at(-1).tabHeadingGap = headingGap;
        assert.ok(
          Math.abs(headingGap) <= 1,
          "Repository and PR state tabs follow the page header directly, as in Studio",
        );
      }
      assert.ok(
        await ev(
          `(() => { const tab = document.querySelector('${selector} .tab-row > [aria-current=page]'); return !tab || (!tab.hidden && tab.getBoundingClientRect().right <= innerWidth); })()`,
        ),
        "The active tab stays visible",
      );
      if (
        await ev(
          `!document.querySelector('${selector} [data-bml-ref=more]').hidden`,
        )
      ) {
        await ev(
          `document.querySelector('${selector} [data-bml-ref=more]').click()`,
        );
        assert.ok(
          await ev(
            `document.querySelector('${selector} .tabs-menu a') && !document.querySelector('${selector} .tabs-menu').hidden`,
          ),
          "Overflow tabs are reachable through More",
        );
        await until(
          `getComputedStyle(document.querySelector('${selector} .tab-chevron')).transform === 'matrix(-1, 0, 0, -1, 0, 0)'`,
        );
        await ev(
          `document.querySelector('${selector}').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`,
        );
        assert.ok(
          await ev(`document.querySelector('${selector} .tabs-menu').hidden`),
          "Escape closes overflow navigation",
        );
      }
    }
    const chevrons = await ev(`(() => {
      return [...document.querySelectorAll('.select-trigger')].filter(e=>e.checkVisibility()).map(trigger=>{
        const caret = trigger.querySelector('.select-chevron');
        const icon = caret.querySelector('svg').getBoundingClientRect();
        const control = trigger.getBoundingClientRect();
        const probe = document.createElement('span');
        probe.style.color='var(--fg-4)';
        trigger.appendChild(probe);
        const expectedColor=getComputedStyle(probe).color;
        probe.remove();
        return {label:trigger.getAttribute('aria-label'), width:icon.width, height:icon.height,
          color:getComputedStyle(caret).color, expectedColor,
          centerOffset:(icon.top+icon.height/2)-(control.top+control.height/2),
          rightInset:control.right-icon.right,
          fallbackVisible:trigger.closest('.select-widget').querySelector('.select-native-chevron').checkVisibility()};
      });
    })()`);
    results.at(-1).selectChevrons = chevrons;
    for (const caret of chevrons) {
      assert.equal(caret.width, 13);
      assert.equal(caret.height, 13);
      assert.equal(
        caret.color,
        caret.expectedColor,
        "Select chevrons use the Nuxt muted token",
      );
      assert.ok(
        Math.abs(caret.centerOffset) <= 0.5,
        `Select chevrons are centered: ${JSON.stringify(caret)}`,
      );
      assert.equal(caret.rightInset, 11);
      assert.equal(
        caret.fallbackVisible,
        false,
        "Only one arrow is visible after enhancement",
      );
    }
    results.at(-1).controlAlignment = await checkControlLayout();
    if (path.startsWith("/repositories/repo?")) {
      assert.ok(
        await ev(
          `document.querySelector('nav[aria-label="Repository section"]').getBoundingClientRect().height >= 30`,
        ),
        "Repository tabs must remain visible at every viewport",
      );
      const clone = `[data-bml-component="${foundationPrefix}popover"]`;
      await ev(`document.querySelector('${clone} summary').click()`);
      await until(
        `document.querySelector('${clone} .popover-content').matches(':popover-open')`,
      );
      await checkControlLayout(`${clone} .popover-content`);
      const cloneBounds = await ev(`(() => {
        const rect = document.querySelector('${clone} .popover-content').getBoundingClientRect();
        return {left:rect.left, right:rect.right, bottom:rect.bottom};
      })()`);
      assert.ok(
        cloneBounds.left >= 0 &&
          cloneBounds.right <= width + 1 &&
          cloneBounds.bottom <= (width === 390 ? 844 : 900),
        `Clone controls must fit: ${JSON.stringify(cloneBounds)}`,
      );
      const cloneRow = await ev(`(() => {
        const panel = document.querySelector('${clone} .popover-content').getBoundingClientRect();
        const row = document.querySelector('${clone} .copy-field-row').getBoundingClientRect();
        const input = document.querySelector('${clone} input[readonly]').getBoundingClientRect();
        const copy = document.querySelector('${clone} button').getBoundingClientRect();
        return {height: panel.height, rowHeight: row.height, input: input.height, copy: copy.height, aligned: Math.abs(input.top-copy.top)<1};
      })()`);
      assert.ok(cloneRow.height < 100, "The clone panel stays compact");
      assert.equal(cloneRow.rowHeight, 30);
      assert.ok(
        cloneRow.aligned,
        "Clone input and copy icon share one aligned row",
      );
      assert.equal(cloneRow.input, 30);
      assert.equal(cloneRow.copy, 30);
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-clone-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await ev(
        'document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))',
      );
      assert.ok(
        await ev(`!document.querySelector('${clone}').open`),
        "Escape closes clone controls",
      );
      assert.ok(
        await ev(
          `!document.querySelector('${clone} .popover-content').matches(':popover-open')`,
        ),
        "Escape removes clone content from the top layer",
      );
      assert.ok(
        await ev(
          `document.activeElement === document.querySelector('${clone} summary')`,
        ),
        "Clone dismissal restores focus",
      );
    }
    if (path === "/pulls/pull?repo=repo&number=1") {
      assert.equal(
        await ev('document.querySelectorAll(".diff-lines").length'),
        0,
        "Overview shows a summary instead of the full diff",
      );
      assert.ok(
        await ev(
          'document.querySelector(".pull-main .ui-card").getBoundingClientRect().top < innerHeight',
        ),
        "Overview cards appear in the first viewport",
      );
      const pullActions = '[data-bml-component="git-pull-actions"]';
      await ev(
        `[...document.querySelectorAll('${pullActions} [data-bml-ref=trigger]')].find(e => e.textContent.trim() === 'Close').click()`,
      );
      await inspectDialog("pull-close-dialog", width);
      const prepareMerge = () => ev(`(() => {
        const select = document.querySelector('${pullActions} .merge-form select[name=strategy]');
        select.value = 'SQUASH'; select.dispatchEvent(new Event('change',{bubbles:true}));
        document.querySelector('${pullActions} .merge-form button[type=submit]').click();
      })()`);
      await prepareMerge();
      await until(
        `document.querySelector('${pullActions} dialog[open] .merge-plan') && document.querySelector('${pullActions} dialog[open]').matches(':modal')`,
      );
      assert.ok(
        await ev(
          `document.querySelector('${pullActions} dialog[open]').innerText.toLowerCase().includes('squash')`,
        ),
        "Merge plan shows the reviewed strategy",
      );
      await ev(`document.querySelector('${pullActions} dialog[open] [data-bml-ref=cancel]').click()`);
      await until(`!document.querySelector('${pullActions} dialog[open]')`);
      await prepareMerge();
      await until(
        `document.querySelector('${pullActions} dialog[open] .merge-plan') && document.querySelector('${pullActions} dialog[open]').matches(':modal')`,
      );
      await inspectDialog("pull-merge-plan", width);
      await nav(path);
    }
    if (path === "/repositories/empty") {
      assert.ok(
        await ev('document.body.innerText.includes("Quick setup")'),
        "An empty repository shows Studio's quick setup",
      );
      if (width === 390)
        assert.ok(
          await ev(
            'document.querySelector(".clone-url-input").getBoundingClientRect().width > 240',
          ),
          "The mobile clone URL uses the available card width",
        );
    }
    if (path.endsWith("&view=files")) {
      assert.ok(
        await ev(
          'document.querySelector(".diff-lines") && document.body.innerText.includes("Existing line comment")',
        ),
        "Files Changed retains inline review threads",
      );
    }
    if (path.endsWith("&view=reviews")) {
      const composer =
        '[data-bml-component="git-pull-review"] [data-bml-design-dialog]';
      assert.ok(
        await ev(`!document.querySelector('${composer} dialog').open`),
        "Review dialog starts closed",
      );
      await ev(`document.querySelector('.page-heading a[href*="review=true"]').click()`);
      await until(`document.querySelector('${composer} dialog')?.matches(':modal')`);
      assert.ok(
        await ev(`!document.querySelector('${composer} > [data-bml-ref=trigger]')`),
        "The programmatic review dialog opens through the page action without adopting its select trigger",
      );
      await ev(
        `document.querySelector('${composer} textarea').value = 'Rejected'; document.querySelector('${composer} button[type=submit]').click()`,
      );
      await until(
        `document.querySelector('${composer} dialog').open && document.querySelector('${composer} [role=alert]')?.textContent.includes('Review rejected')`,
      );
      assert.equal(
        await ev(`document.querySelector('${composer} textarea').value`),
        "Rejected",
        "A rejected review retains its body",
      );
      await ev(
        `document.querySelector('${composer} [data-bml-ref=cancel]').click()`,
      );
      assert.ok(
        await ev(`!document.querySelector('${composer} dialog').open`),
        "Review cancellation closes the dialog",
      );
      await nav(path);
    }
    if (path.startsWith("/repositories/repo/github")) {
      assert.ok(
        await ev('!document.querySelector(".github-configuration").open'),
        "Existing pairing configuration starts collapsed",
      );
      assert.ok(
        await ev(
          `document.querySelector('nav[aria-label="Synchronization history"]').getBoundingClientRect().top < innerHeight`,
        ),
        "Synchronization tabs appear in the first viewport",
      );
      await ev(
        'document.querySelector(".github-configuration summary").click()',
      );
      assert.ok(
        await ev(
          'document.querySelector("[data-bml-component=git-github-pair] form").getBoundingClientRect().height > 100',
        ),
        "Pairing configuration remains available",
      );
      await ev(
        `document.querySelector('[data-bml-island="component:git-github-secret:git-github-secret"] [data-bml-design-dialog] [data-bml-ref=trigger]').click()`,
      );
      await inspectDialog("pairing-credential-dialog", width);
      await ev(
        'document.querySelector(".github-configuration summary").click()',
      );
      if (!path.includes("deliveries")) {
        await ev(
          `document.querySelector('[data-bml-island="component:git-github-history:git-github-history"] [data-bml-design-dialog] [data-bml-ref=trigger]').click()`,
        );
        await inspectDialog("synchronization-dialog", width);
      }
    }
    if (path === "/repositories/repo?file=docs%2Fa.txt") {
      const actions = '[data-bml-component="git-file-actions"]';
      await ev(
        `document.querySelector('${actions} [data-bml-ref=trigger]').click()`,
      );
      assert.ok(
        await ev(
          `document.querySelector('${actions} dialog').matches(':modal')`,
        ),
        "File deletion uses a modal confirmation",
      );
      await ev(
        `document.querySelector('${actions} [data-bml-ref=cancel]').click()`,
      );
      assert.ok(
        await ev(
          `!document.querySelector('${actions} dialog').open && document.activeElement === document.querySelector('${actions} [data-bml-ref=trigger]')`,
        ),
        "File cancellation restores trigger focus",
      );
    }
    if (path === "/repositories/repo/settings?panel=webhooks") {
      const hooks = '[data-bml-component="git-repository-webhooks"]';
      await ev(
        `document.querySelector('${hooks} [data-bml-ref=trigger]').click(); [...document.querySelector('${hooks} select').options].forEach(option=>option.selected=false); document.querySelector('${hooks} select').dispatchEvent(new Event('change',{bubbles:true})); document.querySelector('${hooks} .select-trigger').click()`,
      );
      await ev(
        `document.querySelectorAll('${hooks} .select-options li')[0].dispatchEvent(new PointerEvent('pointerdown',{bubbles:true})); document.querySelectorAll('${hooks} .select-options li')[1].dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))`,
      );
      assert.equal(
        await ev(
          `document.querySelector('${hooks} select').selectedOptions.length`,
        ),
        2,
        "Multi-select retains both native form values",
      );
      assert.equal(
        await ev(`document.querySelectorAll('${hooks} .select-tag').length`),
        2,
        "Selected events appear as removable tags",
      );
      await ev(`document.querySelector('${hooks} .select-tag button').click()`);
      assert.equal(
        await ev(
          `document.querySelector('${hooks} select').selectedOptions.length`,
        ),
        1,
        "Tag removal updates the submitted values",
      );
      await ev(
        `document.querySelector('${hooks} .select-trigger').dispatchEvent(new KeyboardEvent('keydown',{key:'Backspace',bubbles:true}))`,
      );
      assert.equal(
        await ev(
          `document.querySelector('${hooks} select').selectedOptions.length`,
        ),
        0,
        "Backspace removes the last selected event",
      );
      await ev(
        `document.querySelector('${hooks} .select-trigger').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); document.querySelector('${hooks} [data-bml-ref=cancel]').click()`,
      );
    }
    if (
      path === "/repositories/repo/settings?panel=webhooks" &&
      width === 1440
    ) {
      const hooks = '[data-bml-component="git-repository-webhooks"]';
      await ev(
        `document.querySelector('${hooks} [data-bml-ref=trigger]').click(); document.querySelector('${hooks} input[name=url]').value='https://example.test/browser-hook'; document.querySelector('${hooks} input[name=secret]').value='isolated-browser-secret'; [...document.querySelector('${hooks} select').options].forEach(o=>o.selected=['PUSH','TAG_CREATED'].includes(o.value)); document.querySelector('${hooks} select').dispatchEvent(new Event('change',{bubbles:true})); document.querySelector('${hooks} dialog button[type=submit]').click()`,
      );
      await until(
        `document.querySelector('${hooks} input[name=url]')?.value === '' && !document.querySelector('${hooks} dialog').open`,
      );
      assert.ok(
        await ev(
          `!document.body.innerText.includes('isolated-browser-secret')`,
        ),
        "Webhook creation submits native event selections and never echoes the signing secret",
      );
    }
    if (path === "/ci-agents") {
      assert.ok(
        await ev(
          '[...document.querySelectorAll(".heartbeat time")].some(e => /^\\d+[smh] ago$/.test(e.textContent.trim()) && e.dateTime === "2026-10-09T00:00:00Z")',
        ),
        "Heartbeat displays Studio's relative age and retains its full timestamp",
      );
    }
    if (path === "/repositories") {
      const fonts = await ev(
        '[...document.fonts].filter(font => font.status === "loaded").map(font => font.family)',
      );
      for (const family of ["Geist", "Geist Mono"]) {
        assert.ok(
          fonts.includes(family),
          `${family} must load from the packaged font assets`,
        );
      }
      const row = '[data-bml-component="git-repository-row"]';
      await ev(`document.querySelector('${row} summary').focus()`);
      await until(`[...document.querySelectorAll('.bosca-tooltip:popover-open')].some(t=>t.textContent==='Repository actions')`);
      assert.ok(await ev(`document.querySelector('${row} summary').closest('[data-bml-component="${foundationPrefix}tooltip"]').contains(document.querySelector('.bosca-tooltip:popover-open'))`));
      await pressKey("Escape");
      await ev(`document.querySelector('${row} summary').click()`);
      assert.ok(
        await ev(
          `getComputedStyle(document.querySelector('${row} .repository-menu')).backgroundColor === 'rgb(22, 26, 37)'`,
        ),
        "Repository menu uses an opaque surface",
      );
      const bounds = await ev(
        `(() => { const r = document.querySelector('${row} .repository-menu').getBoundingClientRect(); return {left:r.left,right:r.right}; })()`,
      );
      assert.ok(
        bounds.left >= 0 && bounds.right <= width,
        "Repository menu fits the viewport",
      );
      await checkControlLayout(`${row} .repository-menu`);
      const menuItems = await ev(`(() => {
        const menu = document.querySelector('${row} .repository-menu');
        const items = [...menu.querySelectorAll('[role="menuitem"]')];
        return items.map(item => ({
          label: item.textContent.trim(),
          iconLeft: item.querySelector('svg').getBoundingClientRect().left,
          fontSize: getComputedStyle(item).fontSize,
          border: getComputedStyle(item).borderLeftWidth,
        }));
      })()`);
      for (const item of menuItems) {
        assert.ok(
          Math.abs(item.iconLeft - menuItems[0].iconLeft) < 0.5 &&
            item.fontSize === menuItems[0].fontSize &&
            item.border === "0px",
          `Repository menu actions must share left alignment and typography: ${JSON.stringify(menuItems)}`,
        );
      }
      assert.equal(
        await ev(
          `getComputedStyle(document.querySelector('${row} .repository-menu .danger')).color`,
        ),
        "rgb(255, 84, 112)",
        "Destructive menu actions retain their danger treatment through scoped button styles",
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-repository-menu-${width}.png`,
        Buffer.from(
          (await call("Page.captureScreenshot", { format: "png" })).data,
          "base64",
        ),
      );
      await ev(
        `document.querySelector('${row} .repository-menu button').click()`,
      );
      await until(`document.querySelector('${row} dialog')?.matches(':modal')`);
      await ev(
        `document.querySelector('${row} [data-bml-ref=cancel]').click()`,
      );
      assert.ok(
        await ev(
          `!document.querySelector('${row} dialog').open && document.activeElement === document.querySelector('${row} summary')`,
        ),
        "Confirmation cancellation restores menu trigger focus",
      );
      const owner = ".owner-toolbar .select-widget";
      const ownerWidth = await ev(
        `document.querySelector('${owner}').getBoundingClientRect().width`,
      );
      await ev(`
        const widget = document.querySelector('${owner}');
        widget.querySelector('.select-trigger').click();
        const query = widget.querySelector('.select-query');
        query.value = 'Alice';
        query.dispatchEvent(new Event('input', {bubbles:true}));
      `);
      await until(
        `document.querySelectorAll('${owner} .select-options li').length >= 2`,
      );
      await until(
        `getComputedStyle(document.querySelector('${owner} .select-chevron')).transform === 'matrix(-1, 0, 0, -1, 0, 0)'`,
      );
      await checkSelectPalette(owner);
      const menu = await ev(`(() => {
        const widget = document.querySelector('${owner}');
        const bounds = widget.querySelector('.select-popup').getBoundingClientRect();
        return {
          left: bounds.left,
          right: bounds.right,
          top: bounds.top,
          bottom: bounds.bottom,
          width: bounds.width,
          triggerWidth: widget.getBoundingClientRect().width,
          queryHeight: widget.querySelector('.select-query').getBoundingClientRect().height,
          optionHeights: [...widget.querySelectorAll('.select-options li')]
            .map(option => option.getBoundingClientRect().height),
        };
      })()`);
      assert.equal(
        menu.triggerWidth,
        ownerWidth,
        "Opening a select preserves its trigger width",
      );
      assert.ok(
        menu.width >= ownerWidth - 1 && menu.width <= Math.max(ownerWidth, 220) + 1,
        "The owner menu is at least as wide as its compact trigger",
      );
      assert.equal(
        menu.queryHeight,
        30,
        "Dropdown search uses the compact control height",
      );
      assert.ok(
        menu.optionHeights.every((height) => height <= 36),
        "Single-line dropdown choices stay compact",
      );
      assert.ok(
        menu.left >= 0 &&
          menu.right <= width &&
          menu.top >= 0 &&
          menu.bottom <= (width === 390 ? 844 : 900),
        `Owner choices fit the viewport: ${JSON.stringify(menu)}`,
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-owner-menu-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await ev(`document.querySelector('${owner} .select-query')
        .dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}))`);
      await until(
        `getComputedStyle(document.querySelector('${owner} .select-chevron')).transform === 'none' || getComputedStyle(document.querySelector('${owner} .select-chevron')).transform === 'matrix(1, 0, 0, 1, 0, 0)'`,
      );
      for (const kind of ["import", "create"]) {
        const component = `[data-bml-component="git-repository-${kind}"]`;
        await ev(
          `document.querySelector('${component} [data-bml-ref=trigger]').click()`,
        );
        await checkControlLayout(`${component} dialog`);
        const popup = await ev(`(() => {
          const dialog = document.querySelector('${component} dialog');
          const rect = dialog.getBoundingClientRect();
          const footer = dialog.querySelector('.modal-footer').getBoundingClientRect();
          return {left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom,width:rect.width,height:rect.height,footerBottom:footer.bottom,modal:dialog.matches(':modal')};
        })()`);
        assert.ok(
          await ev(
            `[...document.querySelectorAll('${component} .modal-fields .field')].filter(field=>field.getBoundingClientRect().height>0).every(field=> {const label=field.querySelector('span').getBoundingClientRect();const input=field.querySelector('.input,.select-widget').getBoundingClientRect();return Math.abs(label.left-input.left)<2 && input.width > ${popup.width}-45;})`,
          ),
          "Modal labels align left and controls fill the form width",
        );
        assert.ok(
          popup.modal,
          "Repository creation contains focus and makes the background inert",
        );
        assert.ok(
          popup.width > 250 && popup.left >= 0 && popup.right <= width + 1,
          `Repository ${kind} modal fits: ${JSON.stringify(popup)}`,
        );
        assert.ok(
          popup.top >= 0 &&
            popup.bottom <= (width === 390 ? 844 : 900) &&
            popup.footerBottom <= popup.bottom,
          `Repository ${kind} footer stays visible: ${JSON.stringify(popup)}`,
        );
        if (kind === "create") {
          const visibility = `${component} select[name=visibility]`;
          await ev(
            `document.querySelector('${visibility}').closest('.select-widget').querySelector('.select-trigger').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})); document.querySelector('${visibility}').closest('.select-widget').querySelector('.select-trigger').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`,
          );
          assert.equal(
            await ev(`document.querySelector('${visibility}').value`),
            "INTERNAL",
            "Select keyboard choices update native form values",
          );
          await ev(
            `document.querySelector('${visibility}').value = 'PRIVATE'; document.querySelector('${visibility}').dispatchEvent(new Event('change',{bubbles:true}))`,
          );
          const license = `${component} select[name=license]`;
          await ev(
            `document.querySelector('${component} .modal-options').open = true; document.querySelector('${license}').closest('.select-widget').querySelector('.select-trigger').scrollIntoView({block:'nearest'}); document.querySelector('${license}').closest('.select-widget').querySelector('.select-trigger').click()`,
          );
          const menuBounds = await ev(
            `(() => { const rect = document.querySelector('${license}').closest('.select-widget').querySelector('.select-popup').getBoundingClientRect(); return {left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom}; })()`,
          );
          assert.ok(
            menuBounds.left >= 0 &&
              menuBounds.right <= width &&
              menuBounds.top >= 0 &&
              menuBounds.bottom <= (width === 390 ? 844 : 900),
            `Select menu fits above the footer: ${JSON.stringify(menuBounds)}`,
          );
          await ev(
            `document.querySelector('${license}').closest('.select-widget').querySelector('.select-trigger').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); document.querySelector('${component} .modal-options').open = false; document.querySelector('${component} .modal-fields').scrollTop = 0`,
          );
        }
        if (kind === "create") {
          await writeFile(
            `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-create-${width}.png`,
            Buffer.from(
              (
                await call("Page.captureScreenshot", {
                  format: "png",
                  captureBeyondViewport: false,
                })
              ).data,
              "base64",
            ),
          );
        }
        await ev(
          `document.querySelector('${component} button[type=submit]').focus()`,
        );
        await call("Input.dispatchKeyEvent", {
          type: "keyDown",
          key: "Tab",
          code: "Tab",
          windowsVirtualKeyCode: 9,
        });
        await call("Input.dispatchKeyEvent", {
          type: "keyUp",
          key: "Tab",
          code: "Tab",
          windowsVirtualKeyCode: 9,
        });
        assert.ok(
          await ev(
            `document.querySelector('${component} dialog').contains(document.activeElement)`,
          ),
          "Tab stays in the modal",
        );
        await ev(`document.querySelector('${component} [data-bml-ref=close]').focus()`);
        for (const type of ["keyDown", "keyUp"])
          await call("Input.dispatchKeyEvent", {
            type, key: "Tab", code: "Tab", windowsVirtualKeyCode: 9, modifiers: 8,
          });
        assert.ok(
          await ev(`document.activeElement === document.querySelector('${component} button[type=submit]')`),
          "Shift+Tab wraps from the close action to the final submit control",
        );
        await ev(
          `document.querySelector('${component} [data-bml-ref=cancel]').click()`,
        );
        assert.ok(
          await ev(
            `!document.querySelector('${component} dialog').open && document.activeElement === document.querySelector('${component} [data-bml-ref=trigger]')`,
          ),
          "Cancel restores trigger focus",
        );
        await ev(
          `document.querySelector('${component} [data-bml-ref=trigger]').click()`,
        );
        await call("Input.dispatchKeyEvent", {
          type: "keyDown",
          key: "Escape",
          code: "Escape",
          windowsVirtualKeyCode: 27,
        });
        await call("Input.dispatchKeyEvent", {
          type: "keyUp",
          key: "Escape",
          code: "Escape",
          windowsVirtualKeyCode: 27,
        });
        assert.ok(
          await ev(`!document.querySelector('${component} dialog').open`),
          "Escape dismisses the modal",
        );
        await ev(
          `document.querySelector('${component} [data-bml-ref=trigger]').click()`,
        );
        await call("Input.dispatchMouseEvent", {
          type: "mousePressed",
          x: 2,
          y: 2,
          button: "left",
          clickCount: 1,
        });
        await call("Input.dispatchMouseEvent", {
          type: "mouseReleased",
          x: 2,
          y: 2,
          button: "left",
          clickCount: 1,
        });
        assert.ok(
          await ev(`!document.querySelector('${component} dialog').open`),
          "Backdrop dismisses the modal",
        );
      }
    }

    if (path.includes("/settings?panel=protection")) {
      const control = '[data-bml-component="git-repository-protection"]';
      await ev(`document.querySelector('${control} button').click()`);
      await until(
        `document.querySelector('${control} dialog[open]')?.matches(':modal')`,
      );
      await checkControlLayout(`${control} dialog[open]`);
      assert.ok(
        await ev(
          `document.activeElement === document.querySelector('${control} dialog[open] input[name=pattern]')`,
        ),
        "Rule editing starts with focus on its first field",
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-protection-dialog-${path.includes("/empty/") ? "empty-" : ""}${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await ev(
        `document.querySelector('${control} dialog[open] [data-bml-ref=cancel]').click()`,
      );
    }
    if (path === "/pipelines/run") {
      await ev(
        `document.querySelector('.run-controls [data-bml-design-dialog] [data-bml-ref=trigger]').click()`,
      );
      await inspectDialog("run-cancel-dialog", width);
    }
    if (path === "/ci-agents/agent") {
      assert.ok(
        await ev(
          `document.querySelector('.command-code')?.textContent.includes('echo build') && document.querySelector('.command-code').getBoundingClientRect().right <= innerWidth`,
        ),
        "The current command shows within the viewport",
      );
      assert.equal(
        await ev(`document.querySelector('.page-heading button[form=agent-form]')?.form?.id`),
        "agent-form",
        "The header Save button submits the agent card's form",
      );
    }
    if (
      path === "/repositories/repo/settings" ||
      path.includes("/settings?panel=webhooks") ||
      path.endsWith("/secrets") ||
      path === "/ci-agents" ||
      path.includes("/settings?panel=permissions") ||
      path.includes("/settings?panel=utilities")
    ) {
      const dialog = await ev(
        `(() => { const button=[...document.querySelectorAll('[data-bml-design-dialog] [data-bml-ref=trigger]')].find(e => e.getClientRects().length); if (!button) return false; button.click(); return true; })()`,
      );
      if (dialog) {
        assert.ok(
          await ev(`document.querySelector('dialog[open]').matches(':modal')`),
          "Settings actions open modal dialogs",
        );
        await checkControlLayout("dialog[open]");
        const fit = await ev(
          `(() => {const d=document.querySelector('dialog[open]'),r=d.getBoundingClientRect(),f=d.querySelector('.modal-footer').getBoundingClientRect(); return r.left>=0 && r.right<=innerWidth+1 && r.top>=0 && r.bottom<=innerHeight+1 && f.bottom<=r.bottom;})()`,
        );
        assert.ok(fit, "Dialog and footer fit the viewport");
        await writeFile(
          `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-dialog-${path.replace(/[^a-zA-Z0-9]+/g, "-")}-${width}.png`,
          Buffer.from(
            (
              await call("Page.captureScreenshot", {
                format: "png",
                captureBeyondViewport: false,
              })
            ).data,
            "base64",
          ),
        );
        await ev(
          `document.querySelector('dialog[open] [data-bml-ref=cancel]').click()`,
        );
      }
    }
    if (path === "/settings/github") {
      const mappings =
        '[data-bml-island="component:git-github-users:git-github-users"]';
      assert.ok(
        await ev(`document.querySelector('${mappings} .empty-state') !== null`),
        "Mapping empty state is visible",
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-mappings-empty-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await ev(
        `document.querySelector('${mappings} input[name=username]').value='alice'; document.querySelector('${mappings} select[name=principal]').value='principal'; document.querySelector('${mappings} select[name=principal]').dispatchEvent(new Event('change',{bubbles:true})); document.querySelector('${mappings} .mapping-form button[type=submit]').click()`,
      );
      await until(
        `document.querySelector('${mappings} .mapping-table')?.innerText.includes('@alice')`,
      );
      assert.ok(
        await ev(
          `document.querySelector('${mappings} .mapping-table').innerText.includes('alice@example.test')`,
        ),
        "Mapping rows show the identity name and identifier",
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-mappings-populated-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await ev(
        `document.querySelector('${mappings} .mapping-table [data-bml-design-dialog] [data-bml-ref=trigger]').click()`,
      );
      assert.ok(
        await ev(
          `document.querySelector('${mappings} dialog[open]').matches(':modal')`,
        ),
        "Mapping removal requires a modal confirmation",
      );
      await ev(
        `document.querySelector('${mappings} dialog[open] .button.danger').click()`,
      );
      await until(
        `document.querySelector('${mappings} .empty-state') && document.querySelector('${mappings} .status-message[role=status]')?.textContent.includes('Mapping removed')`,
      );
    }
    if (path === "/repositories/repo?file=README.md") {
      const markdown = await ev(`(() => {
        const toolbar=document.querySelector('.markdown-toolbar');
        const tab=toolbar.querySelector('[role=tab]');
        const title=document.querySelector('.markdown h1');
        const text=document.createRange();text.selectNodeContents(tab);
        const tabText=text.getBoundingClientRect();
        const header=toolbar.getBoundingClientRect(), content=document.querySelector('.markdown').getBoundingClientRect();
        const card=document.querySelector('.file-viewer').getBoundingClientRect();
        const item=tab.getBoundingClientRect(), indicator=getComputedStyle(tab,'::after');
        return {titleLeft:title.getBoundingClientRect().left,tabTextLeft:tabText.left,
          contentWidth:content.width,toolbarWidth:header.width,cardWidth:card.width,
          indicatorBottom:item.bottom-parseFloat(indicator.bottom),dividerBottom:header.bottom};
      })()`);
      results.at(-1).markdownAlignment = markdown;
      assert.ok(
        Math.abs(markdown.titleLeft - markdown.tabTextLeft) < 1,
        "Markdown content and view labels share a left inset",
      );
      assert.ok(
        Math.abs(markdown.contentWidth - markdown.toolbarWidth) < 1 && markdown.toolbarWidth <= markdown.cardWidth,
        "Markdown fills its file card",
      );
      assert.ok(
        Math.abs(markdown.indicatorBottom - markdown.dividerBottom) < 1,
        "The active view underline meets the toolbar divider",
      );
      assert.equal(
        await ev(`document.querySelectorAll('.file-toolbar').length`),
        1,
        "The file card has one action group",
      );
      assert.ok(
        await ev(`(() => {
          const labels = [...document.querySelectorAll('.file-toolbar a.button-link, .file-toolbar button')].map(e => e.textContent.trim()).filter(Boolean);
          return ['Rename', 'Delete', 'Edit'].every(label => labels.includes(label));
        })()`),
        "File actions use Studio's labelled Rename, Delete and Edit buttons",
      );
      const downloadBounds = await ev(
        `(() => { const box = document.querySelector('.file-toolbar [aria-label="Download file"]').getBoundingClientRect(); return {x:box.left+box.width/2,y:box.top+box.height/2}; })()`,
      );
      await call("Input.dispatchMouseEvent", {
        type: "mouseMoved",
        ...downloadBounds,
      });
      await until(
        `[...document.querySelectorAll('[role=tooltip]')].some(t => t.matches(':popover-open') && t.textContent === 'Download file')`,
      );
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-file-action-tooltip-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
      await checkIconTooltip();
      await pressKey("Escape");
      assert.ok(
        await ev(
          `![...document.querySelectorAll('[role=tooltip]')].some(t => t.matches(':popover-open'))`,
        ),
        "Escape dismisses tooltips",
      );
      await call("Input.dispatchMouseEvent", { type: "mouseMoved", x: 4, y: 4 });
      await ev(
        `document.querySelector('.file-toolbar [aria-label="Download file"]').focus()`,
      );
      await until(
        `[...document.querySelectorAll('[role=tooltip]')].some(t => t.matches(':popover-open') && t.textContent === 'Download file')`,
      );
      await pressKey("Escape");
      await ev(
        `document.querySelector('[role=tab][data-tab="Source"]').click()`,
      );
      await until(
        `!document.querySelector('[data-bml-ref=sourceSurface]').hidden && document.querySelector('[data-bml-ref=sourceSurface] .cm-editor')`,
      );
      await ev(
        `document.querySelector('[role=tab][data-tab="Source"]').focus()`,
      );
      await pressKey("ArrowLeft");
      assert.ok(
        await ev(
          `document.querySelector('[role=tab][data-tab="Preview"]').getAttribute('aria-selected') === 'true' && !document.querySelector('[data-bml-ref=previewSurface]').hidden`,
        ),
        "Markdown tabs support keyboard switching",
      );
    }
    const captures = new Map(
      paths.map((path) => [
        path,
        path.replace(/^\//, "").replace(/[^a-zA-Z0-9]+/g, "-"),
      ]),
    );
    if (captures.has(path)) {
      await writeFile(
        `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-${captures.get(path)}-${width}.png`,
        Buffer.from(
          (
            await call("Page.captureScreenshot", {
              format: "png",
              captureBeyondViewport: false,
            })
          ).data,
          "base64",
        ),
      );
    }
  }
}
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
await nav("/repositories/repo");
assert.ok(
  await ev('!document.querySelector(".nav-settings").open'),
  "App settings stay collapsed during repository browsing",
);
await ev(
  `[...document.querySelectorAll('nav[aria-label="Repository section"] a')].find(a => a.textContent.trim() === 'Pipelines').click()`,
);
await until(
  `location.pathname === '/repositories/repo' && new URLSearchParams(location.search).get('tab') === 'pipelines' && document.querySelector('.pipeline-card')`,
);
assert.ok(
  await ev(
    `document.querySelector('nav[aria-label="Repository section"] [aria-current=page]').textContent.trim() === 'Pipelines'`,
  ),
  "The pipeline tab stays selected in the repository",
);
const repositoryPipelinesPath = await ev("location.pathname + location.search");
await ev(`document.querySelector('.pipeline-card a.run-row').click()`);
await until(
  `location.pathname === '/pipelines/run' && document.querySelector('h1')?.textContent.includes('Run #3')`,
);
await nav(repositoryPipelinesPath);
await ev(
  `[...document.querySelectorAll('.pipeline-card a.button-link')].find(a => a.textContent.trim() === 'Run').click()`,
);
await until(
  `location.pathname === '/repositories/repo' && document.querySelector('.pipeline-trigger dialog')?.matches(':modal')`,
);
await ev(
  `document.querySelector('.pipeline-trigger [data-bml-ref=cancel]').click()`,
);
await nav("/pulls?new=true");
await until(
  `document.querySelector('[data-bml-component="git-pull-create"] dialog')?.matches(':modal')`,
);
assert.ok(
  await ev(
    `document.activeElement === document.querySelector('[data-bml-component="git-pull-create"] .select-trigger[aria-label="Target repository"]')`,
  ),
  "New PR focuses the target picker",
);
await ev(`(() => {
  const root = document.querySelector('[data-bml-component="git-pull-create"]');
  root.querySelector('input[name=title]').value = 'Retained title';
  root.querySelector('textarea[name=description]').value = 'Retained description';
  const target = root.querySelector('select[name=repo]'); target.value = 'repo'; target.dispatchEvent(new Event('change',{bubbles:true}));
})()`);
await until(
  `document.querySelector('[data-bml-component="git-pull-create"] select[name=source] option[value="feature/one"]') && !document.querySelector('[data-bml-action-pending]')`,
);
assert.equal(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] input[name=title]').value`,
  ),
  "Retained title",
);
assert.equal(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] textarea[name=description]').value`,
  ),
  "Retained description",
);
await ev(
  `(() => { const toggle = document.querySelector('[data-bml-component="git-pull-create"] input[role=switch]'); toggle.focus(); })()`,
);
await call("Input.dispatchKeyEvent", {
  type: "keyDown",
  key: " ",
  code: "Space",
  windowsVirtualKeyCode: 32,
});
await call("Input.dispatchKeyEvent", {
  type: "keyUp",
  key: " ",
  code: "Space",
  windowsVirtualKeyCode: 32,
});
assert.equal(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] input[role=switch]').checked`,
  ),
  true,
  "Draft switch works with the keyboard",
);
await ev(
  `(() => { const source = document.querySelector('[data-bml-component="git-pull-create"] select[name=sourceRepo]'); source.value = 'empty'; source.dispatchEvent(new Event('change',{bubbles:true})); })()`,
);
await until(
  `document.querySelector('[data-bml-component="git-pull-create"] select[name=source]').disabled && !document.querySelector('[data-bml-action-pending]')`,
);
assert.equal(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] input[role=switch]').checked`,
  ),
  true,
  "Changing source retains the draft switch",
);
assert.equal(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] textarea[name=description]').value`,
  ),
  "Retained description",
);
assert.ok(
  await ev(
    `document.querySelector('[data-bml-component="git-pull-create"] dialog').matches(':modal')`,
  ),
  "Picker updates retain the modal",
);
await nav(
  "/pipelines?owner=owner&repo=repo&pipeline=pipeline&ref=refs%2Fheads%2Fmain",
);
await until(
  'document.querySelector("[data-bml-component=git-pipeline-trigger]")',
);
await until(
  'document.querySelector(".pipeline-trigger dialog")?.matches(":modal")',
);
assert.equal(
  await ev(
    'document.querySelector("[data-bml-component=git-pipeline-owner] select[name=owner]").value',
  ),
  "owner",
  "Manual run preserves the owner filter",
);
await until(
  `document.querySelector('.pipeline-trigger select[name=ref]')?.classList.contains('enhanced')`,
);
assert.ok(
  await ev(
    `document.activeElement === document.querySelector('.pipeline-trigger select[name=ref]').closest('.select-widget').querySelector('.select-trigger')`,
  ),
  "Run dialog focuses the ref picker",
);
await ev(`
  document.querySelector("[data-pipeline-input=version]").value = "keep version";
  const control = document.querySelector(".pipeline-trigger select[name=ref]");
  control.value = "refs/heads/feature/one";
  control.dispatchEvent(new Event("change", {bubbles:true}));
`);
await until(
  `document.querySelector(".pipeline-trigger select[name=ref]")?.value === "refs/heads/feature/one" && document.querySelector("[data-pipeline-input=version]")?.value === "keep version" && !document.querySelector('[data-bml-action-pending]')`,
);
assert.ok(
  await ev(
    'document.querySelector(".pipeline-trigger dialog").matches(":modal")',
  ),
  "Changing ref preserves the run dialog",
);
await ev(`
  document.querySelector("[data-pipeline-input=version]").value = "v1";
  document.querySelector("form[data-pipeline-trigger] button[type=submit]").click();
`);
await until(
  'location.pathname === "/pipelines/new-run" && document.body.innerText.includes("Run #3")',
);
await nav("/pipelines/run");
await until('document.querySelector(".step-row[data-step=step]")');
assert.equal(await ev('document.querySelector("[data-log-step]")'), null, "Logs stay collapsed until selected");
await ev('document.querySelector(".step-row[data-step=step]").click()');
await until(
  'document.querySelector(`[data-bml-ref="logStatus"]`)?.dataset.state === "streaming" && document.querySelector(`.log-line[data-line="30"]`)',
);
assert.equal(
  await ev(
    'document.querySelector(`.log-line[data-line="9"] code`).textContent',
  ),
  "Live line 9 <script>literal output</script>",
);
assert.equal(
  await ev('document.querySelectorAll(`.log-line[data-line="9"]`).length'),
  1,
);
assert.equal(await ev("document.querySelector(`.log-line script`)"), null);
await ev("document.querySelector(`[data-bml-ref=pause]`).click()");
await until(
  'document.querySelector(`[data-bml-ref=pause]`).getAttribute("aria-pressed")==="true"',
);
const paused = await ev("document.querySelectorAll(`.log-line`).length");
await new Promise((r) => setTimeout(r, 2500));
assert.equal(await ev("document.querySelectorAll(`.log-line`).length"), paused);
await ev("document.querySelector(`[data-bml-ref=pause]`).click()");
await until('document.querySelector(`[data-bml-ref="logStatus"]`)?.dataset.state === "streaming"');
await until('document.querySelector(`.log-line[data-line="130"]`)');
assert.equal(
  await ev("getComputedStyle(document.querySelector(`.log-line`)).display"),
  "flex",
);
assert.equal(
  await ev(
    "getComputedStyle(document.querySelector(`.app-header nav`)).flexDirection",
  ),
  "column",
  "Desktop navigation must use Studio's sidebar layout",
);
assert.equal(
  await ev(
    "Math.round(document.querySelector(`.app-header`).getBoundingClientRect().width)",
  ),
  224,
);
assert.equal(
  await ev(
    "document.querySelector(`.app-header a[aria-current=page]`)?.getAttribute('href')",
  ),
  "/pipelines",
);
assert.equal(
  await ev(
    'document.querySelector(`.log-line[data-line="9"] code`) ? getComputedStyle(document.querySelector(`.log-line[data-line="9"] code`)).color : null',
  ),
  await ev("(() => { const el = document.createElement('span'); el.style.color = 'var(--err)'; document.body.append(el); const color = getComputedStyle(el).color; el.remove(); return color; })()"),
);
assert.equal(
  await ev('document.querySelector(".log-line time")'),
  null,
  "Log timestamps belong in the gutter tooltip instead of a content column",
);
assert.equal(
  await ev('getComputedStyle(document.querySelector(".log-line .line-number")).width'),
  "40px",
);
assert.equal(
  await ev('getComputedStyle(document.querySelector(".log-output")).maxHeight'),
  "420px",
);
assert.equal(
  await ev('Math.round(document.querySelector(".run-controls button.button.size-sm:not(.icon-only)").getBoundingClientRect().height)'),
  30,
  "Pipeline header actions match Studio's small buttons",
);
const lines = await ev(
  "[...document.querySelectorAll(`.log-line`)].map(e=>Number(e.dataset.line))",
);
assert.deepEqual(
  lines,
  [...new Set(lines)].sort((a, b) => a - b),
);
await ev(
  'document.querySelector(`.log-output`).scrollTop=100;document.querySelector(`.log-output`).dispatchEvent(new Event("scroll"))',
);
assert.equal(
  await ev(
    'document.querySelector(`[data-bml-ref=follow]`).getAttribute("aria-pressed")',
  ),
  "false",
);
await ev("document.querySelector(`[data-bml-ref=follow]`).click()");
assert.ok(await ev("document.querySelector(`.log-output`).scrollTop>100"));
await ev(
  `document.querySelector('[data-bml-component="git-pipeline-job-actions"] .dialog-control:has(dialog[aria-label="Build anyway?"]) > [data-bml-ref=trigger]').click()`,
);
await until(
  `document.querySelector('dialog[aria-label="Build anyway?"]').matches(':modal')`,
);
await ev('document.querySelector(`[name=reason]`).value="Keep this reason"');
await new Promise((r) => setTimeout(r, 2400));
assert.equal(
  await ev("document.querySelector(`[name=reason]`).value"),
  "Keep this reason",
);
assert.ok(
  await ev(
    `document.querySelector('dialog[aria-label="Build anyway?"]').matches(':modal')`,
  ),
  "Run polling preserves the active job dialog",
);
assert.ok(
  await ev(
    `document.activeElement === document.querySelector('[name=reason]')`,
  ),
  "Run polling keeps focus on the reviewed reason",
);
await ev(
  `document.querySelector('dialog[open] [data-bml-ref=cancel]').click()`,
);
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-run.png`,
  Buffer.from(
    (
      await call("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: false,
      })
    ).data,
    "base64",
  ),
);
await nav("/ci-agents/orchestrator");
await until("document.querySelectorAll(`.jse-main`).length===2");
await ev('document.querySelector(`[name=name]`).value="Keep agent draft"');
await new Promise((r) => setTimeout(r, 5600));
assert.equal(
  await ev("document.querySelector(`[name=name]`).value"),
  "Keep agent draft",
);
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-agent.png`,
  Buffer.from(
    (
      await call("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: false,
      })
    ).data,
    "base64",
  ),
);
assert.equal(
  await ev(
    "[...document.querySelectorAll(`.field small`)].filter(e=>e.textContent.trim()===String.fromCharCode(34,34)).length",
  ),
  0,
);
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-screen-matrix.json`,
  JSON.stringify(
    {
      results,
      nativeSelectFallbacks,
      nativePopoverFallbacks,
      loadingStates,
      errors,
      checks: [
        "full-width route and state tab dividers",
        "consistent repository and PR header spacing",
        "Nuxt select chevron size, color and alignment",
        "select and More chevron open-state rotation",
        "native select fallback without JavaScript",
        "log subscription same origin",
        "duplicate/out-of-order lines",
        "safe log text",
        "pause/resume",
        "follow/scroll",
        "poll preserves bypass reason",
        "agent JSON editors mount",
        "agent polling preserves draft",
      ],
    },
    null,
    2,
  ),
);
assert.deepEqual(
  results.filter((r) => r.scroll > r.viewport + 1),
  [],
  "Screen matrix has horizontal overflow",
);
assert.deepEqual(
  results.filter((r) => !r.heading),
  [],
  "Screen matrix has missing page headings",
);
assert.deepEqual(errors, [], "Screen matrix raised browser exceptions");
assert.deepEqual([...seenFoundations].sort(), [...foundationTags].sort());
assert.deepEqual([...seenClientControls].sort(), [...clientControls].sort());
console.log(
  JSON.stringify({
    screens: results.length,
    libraryFoundations: [...seenFoundations].sort(),
    foundationPalette: "passed",
    nativeCloneFallbacks: nativePopoverFallbacks.length,
    libraryClientControls: [...seenClientControls].sort(),
    iconTooltipPaletteAndHover: "passed",
    overflow: results.filter((r) => r.scroll > r.viewport + 1),
    errors,
    liveLogs: "passed",
    agentDraft: "passed",
    pipelineTrigger: "passed",
  }),
);
ws.close();
