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
  errors = [],
  pausedRequests = [],
  graphqlRequests = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Network.requestWillBeSent" && m.params.request.url === base + "/graphql") graphqlRequests.push(m.params.request.postData);
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
async function nav(path) {
  await call("Page.navigate", { url: base + path });
  await until(
    `location.href === ${JSON.stringify(base + path)} && document.readyState==='complete' && document.querySelector('main') && document.querySelector('[data-bml-ref=signout]') && !document.querySelector('[data-bml-ref=signout]').disabled`,
  );
  await ev("document.fonts.ready");
  await new Promise((r) => setTimeout(r, 180));
}
await call("Network.enable");
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
async function statGeometry() {
  return ev(`(() => {
    const bounds = element => {
      const {x, y, width, height} = element.getBoundingClientRect();
      return {x, y, width, height};
    };
    return {
      summary: bounds(document.querySelector('.repository-summary')),
      path: bounds(document.querySelector('.path-bar')),
      tiles: [...document.querySelectorAll('.repository-summary .stat-tile')].map(bounds),
      values: [...document.querySelectorAll('.repository-summary .stat-value')].map(bounds),
    };
  })()`);
}
async function screenshot(state, width) {
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-statistics-${state}-${width}.png`,
    Buffer.from((await call("Page.captureScreenshot", {format:"png",captureBeyondViewport:false})).data,"base64"),
  );
}
for (const width of [1920, 1024, 768, 390]) {
  await call("Emulation.setDeviceMetricsOverride", {
    width,
    height: width === 390 ? 844 : 900,
    deviceScaleFactor: 1,
    mobile: width === 390,
  });
  let loadingGeometry;
  await holdRequests("/_bml/deferred/*git-repository-stats*");
  try {
    await nav("/repositories/repo");
    await waitForHeldRequest();
    assert.ok(
      await ev("document.querySelector('.stats-loading').checkVisibility() && !document.querySelector('.stats-retry').checkVisibility() && document.querySelector('.repository-summary [data-bml-deferred]').getAttribute('aria-busy') === 'true'"),
      "Statistics show loading while the rest of the page is usable",
    );
    assert.deepEqual(
      await ev("[...document.querySelectorAll('.repository-summary .stat-label')].map(label => label.textContent.trim())"),
      ["Commits", "Branches", "Tags", "Contributors", "Size"],
      "Every statistic has its labeled tile before the numbers arrive",
    );
    assert.equal(
      await ev("[...document.querySelectorAll('.stat-skeleton')].filter(skeleton => skeleton.checkVisibility() && skeleton.getAttribute('role') === 'status' && skeleton.getAttribute('aria-label')?.startsWith('Loading ')).length"),
      5,
      "Every value has a visible, accessible loading placeholder",
    );
    loadingGeometry = await statGeometry();
    await screenshot("loading", width);
    await call("Emulation.setEmulatedMedia", {features: [{name: "prefers-reduced-motion", value: "reduce"}]});
    assert.ok(
      await ev("[...document.querySelectorAll('.stat-skeleton')].every(skeleton => getComputedStyle(skeleton).animationName === 'none')"),
      "Loading placeholders respect reduced motion",
    );
    await call("Emulation.setEmulatedMedia", {features: []});
    for (const request of pausedRequests.splice(0)) {
      await call("Fetch.fulfillRequest", {
        requestId: request.requestId,
        responseCode: 500,
        body: btoa("Statistics temporarily unavailable"),
      });
    }
    await until("document.querySelector('.stats-state .stats-error').checkVisibility() && document.querySelector('.stats-retry').checkVisibility()");
    assert.ok(
      await ev("!document.querySelector('.stats-loading').checkVisibility()"),
      "Failed statistics stop showing the loading label",
    );
    assert.deepEqual(await statGeometry(), loadingGeometry, "Failed statistics keep the tiles and following content in place");
    const errorLayout = await ev(`(() => {
      const error = document.querySelector('.stats-state').getBoundingClientRect();
      const path = document.querySelector('.path-bar').getBoundingClientRect();
      return {left: error.left, right: error.right, bottom: error.bottom, pathTop: path.top};
    })()`);
    assert.ok(errorLayout.left >= 0 && errorLayout.right <= width && errorLayout.bottom <= errorLayout.pathTop,
      `The error and retry fit between the tiles and repository path: ${JSON.stringify(errorLayout)}`);
    await screenshot("error", width);
  } finally {
    await releaseRequests();
  }
  await holdRequests("/_bml/deferred/*git-repository-stats*");
  try {
    await ev("document.querySelector('.stats-retry').click()");
    await waitForHeldRequest();
    assert.ok(await ev("document.querySelector('.stats-loading').checkVisibility() && !document.querySelector('.stats-retry').checkVisibility()"));
    assert.deepEqual(await statGeometry(), loadingGeometry, "Retry restores placeholders without moving the page");
  } finally {
    await releaseRequests();
  }
  await until("document.querySelector('.repository-summary [data-bml-deferred]')?.dataset.bmlDeferredState==='loaded'");
  assert.deepEqual(
    await ev("[...document.querySelectorAll('.repository-summary .stat-value')].map(value => value.textContent.trim())"),
    ["2", "2", "1", "1", "1.0 KB"],
    "Retry replaces the statistics error with the loaded summary",
  );
  assert.deepEqual(await statGeometry(), loadingGeometry, "Loaded numbers keep every tile, value slot and following content at its original size and position");
  assert.ok(await ev("!document.querySelector('.stats-error') && !document.querySelector('.stat-skeleton') && !document.querySelector('.repository-summary [data-bml-deferred]').hasAttribute('aria-busy')"));

  const geometry = await ev(`(() => {
    const header = document.querySelector('.repository-heading').getBoundingClientRect();
    const summary = document.querySelector('.repository-summary').getBoundingClientRect();
    return {viewport:innerWidth,scroll:document.documentElement.scrollWidth,
      left:summary.left,right:summary.right,headerRight:header.right};
  })()`);
  assert.ok(geometry.left >= 0 && geometry.right <= width + 1 && geometry.scroll <= width + 1,
    `Statistics fit the viewport: ${JSON.stringify(geometry)}`);
  if (width >= 1024) assert.ok(Math.abs(geometry.right - geometry.headerRight) <= 1);
  await screenshot("loaded", width);
  console.log(`Statistics loading, failure, retry and layout passed at ${width}px`);
}
assert.deepEqual(graphqlRequests, [], "Server-rendered data pages do not refetch browser identity");
assert.deepEqual(errors, [], "Statistics raised browser exceptions");
ws.close();
