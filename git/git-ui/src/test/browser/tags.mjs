import assert from "node:assert/strict";
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const targets = await (
  await fetch(`${process.env.GIT_UI_CDP_URL}/json`)
).json();
const ws = new WebSocket(
  targets.find((t) => t.type === "page").webSocketDebuggerUrl,
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
    pending.delete(m.id);
    m.error ? p.reject(m.error) : p.resolve(m.result);
  }
});
const call = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++n;
    pending.set(id, { resolve, reject });
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
  console.error(await ev("JSON.stringify({url:location.href,ready:document.readyState,navigation:performance.getEntriesByType('navigation')[0]?.name,selects:[...document.querySelectorAll('select')].map(s=>({name:s.name,classes:s.className})),body:document.body.innerText.slice(0,2000)})"));
  console.error(JSON.stringify(errors));
  throw Error("Timeout " + s);
}
await call("Runtime.enable");
await call("Page.enable");
await call("Network.enable");
await call("Network.setCookie", { name: "_git_test", value: "tag-pages-token", url: base });
await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
async function navigate(path) {
  await call("Page.navigate", { url: base + path });
  await until(`performance.getEntriesByType('navigation')[0]?.name === ${JSON.stringify(base + path)} && document.readyState === 'complete' && document.querySelector('select[name=ref].enhanced, .ref-list')`);
}
await navigate("/repositories/repo?tab=tags");
assert.equal(await ev("document.querySelectorAll('.ref-list .ref-name').length"), 25);
assert.equal(await ev("document.querySelector('.ref-list .ref-name').textContent.trim()"), "v1000");
await ev("[...document.querySelectorAll('a')].find(a => a.textContent.trim() === 'Older tags').click()");
await until("location.search.includes('offset=25') && document.querySelector('.ref-list .ref-name')?.textContent.trim() === 'v0975'");
for (const path of ["/repositories/repo", "/search?repo=repo&mode=paths", "/pipelines?repo=repo&pipeline=pipeline"]) {
  await navigate(path);
  await ev("document.querySelector('select[name=ref]').closest('.select-widget').querySelector('[data-bml-ref=trigger]').click()");
  const popup = "document.querySelector('.select-popup:popover-open')";
  await until(`${popup}?.querySelector('[role=option]') && !${popup}.hasAttribute('aria-busy')`);
  assert.equal(await ev(`${popup}.querySelectorAll('[role=option]').length`), 27, path);
  assert.ok((await ev(`${popup}.textContent`)).includes("v1000"), path);
  for (const type of ["keyDown", "keyUp"]) await call("Input.dispatchKeyEvent", { type, key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 });
  assert.equal(await ev("document.activeElement?.getAttribute('data-bml-ref')"), "next", path);
  for (const type of ["keyDown", "keyUp"]) await call("Input.dispatchKeyEvent", { type, key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
  await until("!document.querySelector('.select-popup:popover-open')");
  assert.equal(await ev("document.activeElement?.getAttribute('data-bml-ref')"), "trigger", path);
  await ev("document.activeElement.click()");
  await until(`${popup}?.querySelector('[role=option]') && !${popup}.hasAttribute('aria-busy')`);
  await ev(`${popup}.querySelector('[data-bml-ref=next]').click()`);
  await until(`${popup}?.querySelector('[role=option]')?.textContent === 'v0975 (tag)' && !${popup}.hasAttribute('aria-busy')`);
  assert.equal(await ev(`${popup}.querySelectorAll('[role=option]').length`), 25, path);
  await ev(`${popup}.querySelector('[data-bml-ref=previous]').click()`);
  await until(`${popup}?.textContent.includes('v1000') && !${popup}.hasAttribute('aria-busy')`);
  await ev(`(() => { const input = ${popup}.querySelector('input'); input.value = 'v0001'; input.dispatchEvent(new Event('input', {bubbles:true})); })()`);
  await until(`${popup}?.querySelector('[role=option]')?.textContent === 'v0001 (tag)' && !${popup}.hasAttribute('aria-busy')`);
  for (const type of ["keyDown", "keyUp"]) await call("Input.dispatchKeyEvent", {type, key:"Enter", code:"Enter", windowsVirtualKeyCode:13});
  const value = path === "/repositories/repo" ? "v0001" : "refs/tags/v0001";
  await until(`document.querySelector('select[name=ref]')?.value === '${value}' && !document.querySelector('.select-popup:popover-open')`);
  if (path === "/repositories/repo") {
    await until("new URLSearchParams(location.search).get('ref') === 'v0001' && document.readyState === 'complete' && document.querySelector('select[name=ref].enhanced')");
  } else if (path.startsWith("/search")) {
    await until("document.querySelector('[data-search-ref]')?.dataset.searchRef === 'refs/tags/v0001' && document.querySelector('[data-bml-ref=loading]')?.hidden === true");
  } else {
    await until("[...document.querySelectorAll('select[name=ref] option[selected]')].some(o => o.value === 'refs/tags/v0001')");
  }
}
assert.deepEqual(errors, []);
console.log("Tag list and dropdown pagination, search, and off-page selection passed.");
ws.close();
