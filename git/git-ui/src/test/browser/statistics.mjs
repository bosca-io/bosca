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
for (const width of [1920, 1024, 390]) {
  await call("Emulation.setDeviceMetricsOverride", {
    width,
    height: width === 390 ? 844 : 900,
    deviceScaleFactor: 1,
    mobile: width === 390,
  });
  await holdRequests("/_bml/deferred/*git-repository-stats*");
  try {
    await nav("/repositories/repo");
    await waitForHeldRequest();
    assert.ok(
      await ev("document.querySelector('.stats-loading').checkVisibility() && !document.querySelector('.stats-retry').checkVisibility()"),
      "Statistics show loading while the rest of the page is usable",
    );
    for (const request of pausedRequests.splice(0)) {
      await call("Fetch.fulfillRequest", {
        requestId: request.requestId,
        responseCode: 500,
        body: btoa("Statistics temporarily unavailable"),
      });
    }
    await until("document.querySelector('.stats-error').checkVisibility() && document.querySelector('.stats-retry').checkVisibility()");
    assert.ok(
      await ev("!document.querySelector('.stats-loading').checkVisibility()"),
      "Failed statistics stop showing the loading label",
    );
  } finally {
    await releaseRequests();
  }
  await ev("document.querySelector('.stats-retry').click()");
  await until("document.querySelector('.repository-summary [data-bml-deferred]')?.dataset.bmlDeferredState==='loaded'");
  assert.ok(
    await ev("document.querySelector('.repository-summary').innerText.includes('1 contributor') && !document.querySelector('.stats-error')"),
    "Retry replaces the statistics error with the loaded summary",
  );

  const geometry = await ev(`(() => {
    const header = document.querySelector('.repository-heading').getBoundingClientRect();
    const summary = document.querySelector('.repository-summary').getBoundingClientRect();
    return {viewport:innerWidth,scroll:document.documentElement.scrollWidth,
      left:summary.left,right:summary.right,headerRight:header.right};
  })()`);
  assert.ok(geometry.left >= 0 && geometry.right <= width + 1 && geometry.scroll <= width + 1,
    `Statistics fit the viewport: ${JSON.stringify(geometry)}`);
  if (width >= 1024) assert.ok(Math.abs(geometry.right - geometry.headerRight) <= 1);
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-statistics-${width}.png`,
    Buffer.from((await call("Page.captureScreenshot", {format:"png",captureBeyondViewport:false})).data,"base64"),
  );
  console.log(`Statistics loading, failure, retry and layout passed at ${width}px`);
}
assert.deepEqual(errors, [], "Statistics raised browser exceptions");
ws.close();
