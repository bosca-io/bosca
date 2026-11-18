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

const profiles='document.querySelector("[data-bml-json-editor][data-name=profiles]")';
const sinks='document.querySelector("[data-bml-json-editor][data-name=sinks]")';
const field=`${profiles}.querySelector('textarea')`;
const code=`${profiles}.querySelector('.cm-content')`;
const form='document.querySelector("#orchestrator-form")';
async function typeJson(value, wait=true) {
  await ev(`${code}.focus()`);
  const modifiers=await ev('/Mac|iPhone|iPad/.test(navigator.platform)?4:2');
  for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:'a',code:'KeyA',windowsVirtualKeyCode:65,modifiers});
  await call('Input.insertText',{text:value});
  if(wait) await until(`${field}.value===${JSON.stringify(value)}`);
}
try {
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await nav('/ci-agents/orchestrator');
  await until(`Boolean(${profiles}?.hasAttribute('data-json-ready')&&${sinks}?.hasAttribute('data-json-ready'))`);
  const canonical=`bml-${Buffer.from('io.bosca:bml-design').toString('hex')}-json-editor`;
  assert.equal(await ev(`${profiles}.dataset.bmlComponent`),canonical);
  assert.equal(await ev(`${sinks}.dataset.bmlComponent`),canonical);
  assert.deepEqual(await ev(`(() => {const r=${profiles};return [r.getAttribute('aria-label'),getComputedStyle(r.querySelector('.jse-main')).backgroundColor,
    getComputedStyle(r.querySelector('.jse-key')).color,${field}.name,${field}.hidden];})()`),
    ['Runner profiles','rgb(22, 26, 37)','rgb(156, 220, 254)','profiles',true]);
  await ev(`window.gitJsonRoot=${profiles};window.gitJsonEngine=${profiles}.querySelector('.jse-main');window.gitSinks=${sinks};window.gitSinksValue=${sinks}.querySelector('textarea').value;
    ${profiles}.querySelector('button[title^="Switch to text"]').click()`);
  await until(`Boolean(${code})`);
  assert.equal(await ev(`${code}.getAttribute('aria-readonly')`),'false');
  const invalid='{ "runner": ';
  await typeJson(invalid);
  await ev(`window.gitJsonCode=${code};document.querySelector('[name=scope]').value='retained-scope';${code}.focus();${form}.requestSubmit()`);
  await until(`Boolean(document.querySelector('.order-alert'))&&!${form}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await ev(`${profiles}===window.gitJsonRoot&&${profiles}.querySelector('.jse-main')===window.gitJsonEngine&&${code}===window.gitJsonCode&&
    ${field}.value===${JSON.stringify(invalid)}&&${code}.textContent===${JSON.stringify(invalid)}&&document.activeElement===${code}&&
    ${sinks}===window.gitSinks&&${sinks}.querySelector('textarea').value===window.gitSinksValue&&document.querySelector('[name=scope]').value==='retained-scope'`),
    'Failed validation retains both editors, exact invalid draft, text mode, focus and other submitted fields');
  const valid='{ "linux": { "size": "large", "count": 3, "enabled": true } }';
  await typeJson(valid);
  await ev(`document.querySelector('[name=provider]').value='rejected-provider';${form}.requestSubmit()`);
  await until(`document.querySelector('.order-alert')?.textContent.includes('Configuration rejected')&&!${form}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await ev(`${field}.value===${JSON.stringify(valid)}&&${code}===window.gitJsonCode&&${sinks}.querySelector('textarea').value===window.gitSinksValue`),
    'An upstream rejection preserves both drafts and the local editor mode');
  await ev(`document.querySelector('[name=provider]').value='digitalocean';window.gitOriginalFetch=fetch.bind(window);window.fetch=async (...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=window.gitOriginalFetch;await new Promise(resolve=>{window.releaseGitJsonSave=resolve;});}
    return window.gitOriginalFetch(...args);};`);
  const final='{ "linux": { "size": "large", "count": 7, "enabled": false } }';
  await typeJson(final,false);
  await ev(`${form}.requestSubmit()`);
  await until(`${field}.readOnly&&${code}?.getAttribute('aria-readonly')==='true'`);
  assert.ok(await ev(`${code}===window.gitJsonCode`),'Pending state does not switch the local editor mode');
  await ev('window.releaseGitJsonSave()');
  await until(`Boolean(${profiles}?.hasAttribute('data-json-ready'))&&document.querySelector('[name=scope]')?.value==='retained-scope'&&!document.querySelector('.order-alert')&&
    JSON.parse(${field}.value).linux.count===7`);
  // The application navigates after save; the independent readback comes from the fixture API state.
  await nav('/ci-agents/orchestrator');
  await until(`Boolean(${profiles}?.hasAttribute('data-json-ready'))&&JSON.parse(${field}.value).linux.count===7`);
  assert.equal(await ev(`JSON.parse(${field}.value).linux.enabled`),false);
  for(const width of [1920,1440,1024,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await ev(`${profiles}.scrollIntoView({block:'center'})`);
    await until(`document.documentElement.scrollWidth<=innerWidth`);
    assert.ok(await ev(`(() => {const p=${profiles}.getBoundingClientRect(),s=${sinks}.getBoundingClientRect();return p.bottom<=s.top&&p.width>200&&s.width>200;})()`),'Both editors fit independently at '+width);
    await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/git-json-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  }
  await ev(`${profiles}.classList.add('json-palette-check')`);
  // The override is ordinary consumer CSS installed for this isolated browser check.
  await call('DOM.enable');await call('CSS.enable');
  const frameId=(await call('Page.getFrameTree')).frameTree.frame.id;
  const sheet=await call('CSS.createStyleSheet',{frameId});
  await call('CSS.setStyleSheetText',{styleSheetId:sheet.styleSheetId,text:'.json-palette-check { --json-bg: #eff6ff; --json-key: #1d4ed8; --json-selection: #93c5fd; }'});
  assert.deepEqual(await ev(`[getComputedStyle(${profiles}.querySelector('.jse-main')).backgroundColor,getComputedStyle(${profiles}.querySelector('.jse-key')).color]`),
    ['rgb(239, 246, 255)','rgb(29, 78, 216)']);
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await call('Page.navigate',{url:base+'/ci-agents/orchestrator'});
  await until(`document.readyState==='complete'&&Boolean(${field})`);
  assert.ok(await ev(`!${field}.hidden&&JSON.parse(new FormData(${form}).get('profiles')).linux.count===7&&!document.querySelector('.jse-main')`),'No-script configuration retains native structured form values');
  assert.deepEqual(errors,[]);
  console.log('Git compiled JSON editor checks passed: validation failure, upstream rejection, immediate success/readback, pending state, independent values, native fallback, palettes and four widths.');
} finally {
  ws.close();
}
