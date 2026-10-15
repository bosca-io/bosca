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
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
});
// Recent runs render with the pipeline; the paginated history loads through its own action.
for (const width of [1440, 390]) {
  await call("Emulation.setDeviceMetricsOverride", { width, height: 900, deviceScaleFactor: 1, mobile: false });
  await nav("/repositories/repo?tab=pipelines");
  await until('document.querySelector("[data-bml-island*=git-pipeline-history] button")');
  assert.equal(await ev('document.querySelector(".run-history .history-rows")'), null);
  assert.ok(await ev('document.querySelectorAll(".recent-runs .run-row").length >= 1'), "Recent runs show without another request");
  await ev('document.querySelector("[data-bml-island*=git-pipeline-history] button").click()');
  await until('document.querySelectorAll(".run-history .history-rows .run-row").length === 50 && !document.querySelector("[data-bml-action-pending]")');
  await ev('[...document.querySelectorAll(".run-history button")].find(b => b.textContent.trim() === "Next").click()');
  await until('document.querySelector("[data-bml-island*=git-pipeline-history]").textContent.includes("No runs yet.") && !document.querySelector("[data-bml-action-pending]")');
  await ev('[...document.querySelectorAll(".run-history button")].find(b => b.textContent.trim() === "Previous").click()');
  await until('document.querySelectorAll(".run-history .history-rows .run-row").length === 50 && !document.querySelector("[data-bml-action-pending]")');
  assert.ok(await ev('document.documentElement.scrollWidth <= innerWidth + 1'), "Pipeline history overflows the page");
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/pipeline-history-${width}.png`, Buffer.from((await call("Page.captureScreenshot", { format: "png", captureBeyondViewport: false })).data, "base64"));
}
await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
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
assert.deepEqual(errors, [], "Pipeline flow raised browser exceptions");
console.log("Pipeline lazy histories, pagination, trigger, ref changes, live logs, reconnect and polling checks passed.");
ws.close();
