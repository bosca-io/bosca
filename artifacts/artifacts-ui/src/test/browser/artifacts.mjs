import { spawn } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createServer } from "node:net";
import assert from "node:assert/strict";

const base = process.env.ATLAS_ARTIFACTS_TEST_URL;
assert.ok(base && ["localhost", "127.0.0.1"].includes(new URL(base).hostname), "Use an isolated local Atlas test process.");
const token = process.env.ATLAS_ARTIFACTS_TEST_TOKEN;
assert.ok(token);
const password = process.env.ATLAS_ARTIFACTS_TEST_PASSWORD;
assert.ok(password, "Use the isolated fixture's login password.");
const repositoryId = process.env.ATLAS_ARTIFACTS_TEST_REPOSITORY;
const dockerId = process.env.ATLAS_ARTIFACTS_TEST_DOCKER;
const namespaceId = process.env.ATLAS_ARTIFACTS_TEST_NAMESPACE;
const reports = resolve("build/reports/browser");
await mkdir(reports, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), "atlas-artifacts-chrome-"));
const server = createServer();
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const port = server.address().port;
await new Promise(resolve => server.close(resolve));
const chrome = spawn(process.env.ATLAS_CHROME_BINARY ?? (process.platform === "darwin" ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" : "google-chrome"), ["--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "about:blank"], { stdio: "ignore" });
let launchError;
chrome.on("error", error => { launchError = error; });
let ws;
const errors = [];
const uploadAttempts = new Map();
const expectedNetworkFailures = new Set();
let failUploadOnce = true;
try {
  let targets;
  for (let i = 0; i < 100; i++) {
    if (launchError) throw launchError;
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); if (targets.some(item => item.type === "page")) break; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(targets?.some(item => item.type === "page"), "Chrome did not start.");
  ws = new WebSocket(targets.find(item => item.type === "page").webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener("open", resolve, { once: true }));
  let sequence = 0;
  const pending = new Map();
  ws.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails);
    if (message.method === "Log.entryAdded" && message.params.entry.level === "error") {
      const entry = message.params.entry;
      if (!(entry.source === "network" && expectedNetworkFailures.has(entry.url))) errors.push(entry);
    }
    if (message.method === "Fetch.requestPaused") {
      const { requestId, request } = message.params;
      uploadAttempts.set(request.url, (uploadAttempts.get(request.url) ?? 0) + 1);
      const fail = failUploadOnce && new URL(request.url).pathname.endsWith("/retry.zip");
      if (fail) { failUploadOnce = false; expectedNetworkFailures.add(request.url); }
      void call(fail ? "Fetch.fulfillRequest" : "Fetch.continueRequest", fail ? {
        requestId, responseCode: 503,
        responseHeaders: [{name:"Content-Type",value:"text/plain"}],
        body: Buffer.from("Temporarily unavailable").toString("base64"),
      } : { requestId }).catch(error => errors.push(String(error)));
    }
    if (message.id) { const item = pending.get(message.id); pending.delete(message.id); if (message.error) item?.reject(new Error(JSON.stringify(message.error))); else item?.resolve(message.result); }
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => {
    const result = await call("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const until = async (expression, label = expression) => {
    for (let i = 0; i < 150; i++) { if (await evaluate(expression)) return; await new Promise(resolve => setTimeout(resolve, 100)); }
    throw new Error(`Timed out: ${label}. Browser errors: ${JSON.stringify(errors)}. Page: ${await evaluate("document.body.innerText.slice(-4000)")}`);
  };
  const go = async path => { await call("Page.navigate", { url: base + path }); await until(`location.pathname === ${JSON.stringify(path.split("?")[0])} && document.readyState === "complete" && typeof atlasAuth !== "undefined"`); await until("!document.body.innerText.includes('Unable to load this page')"); await evaluate("document.fonts.ready.then(() => true)"); };
  const click = async selector => {
    const rect = await evaluate(`(() => { const item = document.querySelector(${JSON.stringify(selector)}); if (!item) throw new Error('Missing control: ' + ${JSON.stringify(selector)}); item.scrollIntoView({block:'center'}); const r = item.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()`);
    await call("Input.dispatchMouseEvent", { type: "mousePressed", ...rect, button: "left", clickCount: 1 });
    await call("Input.dispatchMouseEvent", { type: "mouseReleased", ...rect, button: "left", clickCount: 1 });
  };
  const clickText = async text => {
    const selector = await evaluate(`(() => { const item = [...document.querySelectorAll('button')].find(item => item.textContent.trim() === ${JSON.stringify(text)} && item.getBoundingClientRect().width); if (!item) throw new Error('Missing button: ' + ${JSON.stringify(text)}); item.setAttribute('data-browser-control','active'); return '[data-browser-control="active"]'; })()`);
    await click(selector);
    await evaluate("document.querySelector('[data-browser-control=active]')?.removeAttribute('data-browser-control')");
  };
  const fill = async (selector, value) => {
    const { root } = await call("DOM.getDocument");
    const { nodeId } = await call("DOM.querySelector", { nodeId: root.nodeId, selector });
    assert.ok(nodeId, `Missing input: ${selector}`);
    await call("DOM.focus", { nodeId });
    await call("Input.insertText", { text: value });
  };
  const key = async (value, code, windowsVirtualKeyCode) => {
    await call("Input.dispatchKeyEvent", { type: "keyDown", key: value, code, windowsVirtualKeyCode });
    await call("Input.dispatchKeyEvent", { type: "keyUp", key: value, code, windowsVirtualKeyCode });
  };
  const filterSubsystems = async value => {
    await evaluate("document.querySelector('dialog[open] input[aria-label=\"Filter subsystems\"]').select()");
    await call("Input.insertText", { text: value });
  };
  const screenshot = async name => { const shot = await call("Page.captureScreenshot", { format: "png" }); await writeFile(join(reports, name + ".png"), Buffer.from(shot.data, "base64")); };
  await call("Page.enable"); await call("Runtime.enable"); await call("Log.enable"); await call("Network.enable");
  await call("Browser.setPermission", { permission: { name: "clipboard-read" }, setting: "granted", origin: base });
  await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  await go("/login");
  await fill("input[name=identifier]", "admin");
  await fill("input[name=password]", password);
  await click("button[type=submit]");
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')", "fresh login lands at Atlas home");
  await screenshot("login-to-atlas-home");

  await go("/artifacts/namespaces");
  await clickText("New namespace");
  await until("document.querySelector('dialog[open] input[name=name]') !== null");
  await fill("dialog[open] input[name=name]", "browser-files");
  await click("dialog[open] button[type=submit]");
  await until("location.pathname.startsWith('/artifacts/namespaces/') && document.querySelector('h1')?.textContent === 'browser-files'", "namespace action and navigation");
  await screenshot("namespace-created");

  await go(`/artifacts/repositories/${repositoryId}`);
  const localFile = join(profile, "browser.zip");
  const retryFile = join(profile, "retry.zip");
  await writeFile(localFile, "browser-upload-bytes");
  await writeFile(retryFile, "retry-upload-bytes");
  let document = await call("DOM.getDocument");
  let fileInput = await call("DOM.querySelector", { nodeId: document.root.nodeId, selector: '.artifact-drop input[type=file]' });
  await call("DOM.setFileInputFiles", { nodeId: fileInput.nodeId, files: [localFile, retryFile] });
  await until("location.pathname === '/artifacts/repositories/upload' && document.querySelector('[data-bml-ref=files] input[name=filename]')?.value === 'browser.zip'", "staged file survives navigation");
  await fill("input[name=version]", "browser-1");
  await call("Fetch.enable", {patterns:[{urlPattern:base+"/_artifacts/raw/*",requestStage:"Request"}]});
  await click("button[data-bml-ref=upload]");
  await until("document.querySelector('[data-bml-ref=message]')?.textContent.includes('Some files failed.') && !document.querySelector('button[data-bml-ref=upload]').disabled", "partial upload exposes a retry");
  assert.deepEqual(await evaluate("[...document.querySelectorAll('[data-bml-ref=files] [data-file-status]')].map(e=>e.textContent)"), ["Uploaded","Temporarily unavailable"]);
  await screenshot("upload-partial-retry");
  await click("button[data-bml-ref=upload]");
  await until(`location.pathname === '/artifacts/repositories/${repositoryId}' && document.body.innerText.includes('browser-1')`, "browser upload completes and returns to repository");
  await call("Fetch.disable");
  assert.equal(uploadAttempts.get(`${base}/_artifacts/raw/atlas-ui-files/tool/browser-1/browser.zip`), 1, "Successful files are not uploaded again on retry");
  assert.equal(uploadAttempts.get(`${base}/_artifacts/raw/atlas-ui-files/tool/browser-1/retry.zip`), 2);
  await screenshot("uploaded-repository");

  await evaluate("[...document.querySelectorAll('details.artifact-version')].find(item => item.textContent.includes('browser-1')).open = true");
  const download = '.artifact-version[open] .artifact-download:has(input[aria-label="Download URL for browser.zip"])';
  await click(`${download} button`);
  await until(`document.querySelector(${JSON.stringify(download)})?.textContent.includes('Download URL copied.')`);
  const copied = await evaluate("navigator.clipboard.readText()");
  assert.equal(copied, `${base}/_artifacts/raw/atlas-ui-files/tool/browser-1/browser.zip`);
  const payload = await evaluate(`(async () => fetch(${JSON.stringify(copied)}, {headers: await atlasAuth.getAuthHeaders()}).then(response => response.text()))()`);
  assert.equal(payload, "browser-upload-bytes");
  assert.equal(await evaluate(`(async()=>fetch(${JSON.stringify(base+"/_artifacts/raw/atlas-ui-files/tool/browser-1/retry.zip")},{headers:await atlasAuth.getAuthHeaders()}).then(r=>r.text()))()`), "retry-upload-bytes");

  const geometry = [];
  for (const width of [1920, 1024, 768, 390]) {
    await call("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await go("/repositories");
    assert.equal(await evaluate("document.querySelector('.subsystem-trigger-chevron path')?.getAttribute('d')"), "m9 18 6-6-6-6");
    await click(".atlas-git-switcher [data-bml-ref=trigger]");
    await until("document.querySelector('[data-atlas-system-switcher] dialog[open]') !== null");
    assert.equal(await evaluate("document.activeElement?.getAttribute('aria-label')"), "Filter subsystems");
    const chooser = await evaluate("(() => { const d=document.querySelector('dialog[open]'); const b=getComputedStyle(d,'::backdrop'); return {blur:b.backdropFilter,background:b.backgroundColor,git:getComputedStyle(d.querySelector('[data-subsystem-id=git]')).getPropertyValue('--item-accent').trim(),artifacts:getComputedStyle(d.querySelector('[data-subsystem-id=artifacts]')).getPropertyValue('--item-accent').trim()}; })()");
    assert.deepEqual(chooser, { blur:"none", background:"rgba(0, 0, 0, 0)", git:"#64748b", artifacts:"#f764f6" });
    const chooserSpacing = await evaluate("(() => {const d=document.querySelector('dialog[open]');const b=getComputedStyle(d.querySelector('.subsystem-dialog-body'));const g=getComputedStyle(d.querySelector('[data-subsystem-category] .variant-menu'));return {padding:b.paddingLeft,gap:g.gap};})()");
    assert.deepEqual(chooserSpacing, {padding:"16px",gap:"8px"});
    assert.equal(await evaluate("document.querySelector('dialog[open] a[aria-current=page]')?.textContent.includes('Git')"), true);
    await screenshot(`system-switcher-git-${width}`);
    await filterSubsystems("no matching subsystem");
    await until("!document.querySelector('dialog[open] [data-bml-ref=empty]').hidden");
    await screenshot(`system-switcher-empty-${width}`);
    await key("Escape", "Escape", 27);
    await until("!document.querySelector('dialog[open]')");
    assert.equal(await evaluate("document.activeElement === document.querySelector('.atlas-git-switcher [data-bml-ref=trigger]')"), true);
    await click(".atlas-git-switcher [data-bml-ref=trigger]");
    assert.equal(await evaluate("document.querySelector('dialog[open] input').value"), "");
    await filterSubsystems("artifacts");
    assert.equal(await evaluate("[...document.querySelectorAll('dialog[open] [data-subsystem-id]')].filter(a=>!a.hidden&&!a.closest('[hidden]')).length"), 1);
    await key("ArrowDown", "ArrowDown", 40);
    await key("Enter", "Enter", 13);
    await until("location.pathname === '/artifacts/namespaces' && document.readyState === 'complete' && document.querySelector('[data-atlas-system-switcher]') !== null");
    await click("[data-atlas-system-switcher] [data-bml-ref=trigger]");
    await until("document.querySelector('dialog[open]') !== null");
    assert.equal(await evaluate("document.querySelector('dialog[open] a[aria-current=page]')?.textContent.includes('Artifacts')"), true);
    assert.equal(await evaluate("document.querySelector('dialog[open] [data-subsystem-recent-block]')?.textContent.includes('Git')"), true);
    assert.equal(await evaluate("[...document.querySelectorAll('dialog[open] [data-subsystem-recent-block] [data-subsystem-id]')].filter(a=>!a.hidden).map(a=>a.dataset.subsystemId).join(',')"), "git");
    assert.equal(await evaluate("getComputedStyle(document.body).getPropertyValue('--accent').trim()"), "#f764f6");
    const panel = await evaluate("(() => { const r = document.querySelector('dialog[open]').getBoundingClientRect(); return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:innerWidth,height:innerHeight}; })()");
    assert.ok(panel.left >= 0 && panel.right <= panel.width && panel.top >= 0 && panel.bottom <= panel.height, JSON.stringify(panel));
    await screenshot(`system-switcher-artifacts-${width}`);
    await click('dialog[open] .subsystem-dialog-brand');
    await until("location.pathname === '/' && document.querySelector('.atlas-home-main') !== null");
    await click('.atlas-home-main a[href="/repositories"]');
    await until("location.pathname === '/repositories' && document.readyState === 'complete'");
    for (const [name, path] of [["landing", "/"], ["namespaces", "/artifacts/namespaces"], ["repositories", "/artifacts/repositories"], ["repository", `/artifacts/repositories/${repositoryId}`], ["upload", `/artifacts/repositories/upload?repositoryId=${repositoryId}`], ["permissions", `/artifacts/settings/permissions?namespaceId=${namespaceId}`], ["docker-settings", `/artifacts/settings/repositories/${dockerId}`], ["raw-settings", `/artifacts/settings/repositories/${repositoryId}`]]) {
      await go(path);
      const size = await evaluate("({width:innerWidth,scroll:document.documentElement.scrollWidth})");
      geometry.push({ width, page: name, ...size });
      assert.ok(size.scroll <= size.width + 1, `${name} overflows at ${width}: ${JSON.stringify(size)}`);
      if (["namespaces", "repositories", "repository"].includes(name)) {
        const layout = await evaluate("(() => { const shell=document.querySelector('.shell-main').getBoundingClientRect();const title=document.querySelector('h1').getBoundingClientRect();const card=document.querySelector('.artifact-content .ui-card').getBoundingClientRect();return {heading:title.left-shell.left,card:card.left-shell.left};})()");
        const gutter = width <= 700 ? 16 : 22;
        assert.ok(Math.abs(layout.heading-gutter)<1 && Math.abs(layout.card-gutter)<1, `Studio page gutters at ${width}: ${JSON.stringify(layout)}`);
      }
      await screenshot(`${name}-${width}`);
      if (name === "upload") {
        const footer = await evaluate("(() => { const f=document.querySelector('form[data-bml-ref=uploadForm] > .form-actions');const back=f.querySelector('a.button-link');const upload=f.querySelector('button[type=submit]');const c=getComputedStyle(f);return {padding:c.paddingLeft,border:c.borderTopWidth,backIcon:!!back.querySelector('svg'),backBeforeUpload:!!(back.compareDocumentPosition(upload)&Node.DOCUMENT_POSITION_FOLLOWING),sameHeight:Math.abs(back.getBoundingClientRect().height-upload.getBoundingClientRect().height)<1};})()");
        assert.deepEqual(footer, {padding:"18px",border:"1px",backIcon:true,backBeforeUpload:true,sameHeight:true});
      }
      if (name === "repositories") {
        const deletionTrigger = 'tr:has(a[title="atlas-ui-files/tool"]) button[aria-label="Delete repository"]';
        await click(deletionTrigger);
        await until("document.querySelector('dialog[open] button[type=submit].danger') !== null");
        const deletion = await evaluate("(() => { const d=document.querySelector('dialog[open]');const b=d.querySelector('button[type=submit]');const c=getComputedStyle(b);const body=getComputedStyle(d.querySelector('.form-body'));const footer=getComputedStyle(d.querySelector('.form-actions'));return {danger:b.classList.contains('danger'),border:c.borderTopWidth,borderStyle:c.borderTopStyle,borderColor:c.borderTopColor,bodyPadding:body.paddingLeft,footerPadding:footer.paddingLeft,cancel:!!d.querySelector('[data-dialog-close]'),required:d.querySelector('input[name=confirmed]').required};})()");
        assert.equal(deletion.danger, true);
        assert.equal(deletion.border, "1px");
        assert.equal(deletion.borderStyle, "solid");
        assert.notEqual(deletion.borderColor, "rgba(0, 0, 0, 0)");
        assert.equal(deletion.bodyPadding, "18px");
        assert.equal(deletion.footerPadding, "18px");
        assert.equal(deletion.cancel && deletion.required, true);
        await screenshot(`delete-repository-${width}`);
        await click("dialog[open] [data-dialog-close]");
        await until("!document.querySelector('dialog[open]')");
        assert.equal(await evaluate(`document.activeElement.matches(${JSON.stringify(deletionTrigger)})`), true);
      }
      if (["raw-settings", "docker-settings"].includes(name)) {
        const card = name === "raw-settings" ? "Release sync status" : "Sync status";
        const status = await evaluate(`(() => {const c=[...document.querySelectorAll('.ui-card')].find(c=>c.querySelector('h2')?.textContent===${JSON.stringify(card)});const b=c.querySelector('.card-body');return {padding:getComputedStyle(b).paddingLeft,empty:!!b.querySelector('.empty-state'),pagination:!!b.querySelector('nav[aria-label$="history pages"]'),description:!!document.querySelector('.card-description'),back:!!document.querySelector('.page-heading a.button-link svg')};})()`);
        assert.deepEqual(status, {padding:"18px",empty:true,pagination:false,description:true,back:true});
        await evaluate(`[...document.querySelectorAll('.ui-card')].find(c=>c.querySelector('h2')?.textContent===${JSON.stringify(card)}).scrollIntoView({block:'center'})`);
        await screenshot(`${name}-status-${width}`);
        await click('.ui-card details > summary.disclosure-heading');
        await until("document.querySelector('details[open] input[name=secretName]') !== null");
        await screenshot(`${name}-secret-${width}`);
        await click('.ui-card details[open] > summary.disclosure-heading');
      }
    }
  }
  await writeFile(join(reports, "geometry.json"), JSON.stringify(geometry, null, 2));
  assert.deepEqual(errors, [], "Browser JavaScript or resource errors");
  console.log(`Atlas browser checks passed: library card/form/footer controls, deletion danger styling and cancellation, empty states, conditional pagination, navigation, disclosures, upload/download, and ${geometry.length} page/viewport checks.`);
} finally {
  ws?.close();
  chrome.kill("SIGTERM");
  if (chrome.exitCode === null) await Promise.race([new Promise(resolve => chrome.once("exit", resolve)), new Promise(resolve => setTimeout(resolve, 3000))]);
  await rm(profile, { recursive: true, force: true });
}
