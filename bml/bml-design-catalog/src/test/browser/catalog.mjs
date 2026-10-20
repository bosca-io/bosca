import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { checkReferences } from './references.mjs';
import { checkPresentation } from './presentation.mjs';
import { checkPickers } from './picker.mjs';
import { checkSources } from './source.mjs';
import { checkToastNavigation } from './toast-navigation.mjs';
import { checkJsonEditors } from './json.mjs';
import { checkComments } from './comments.mjs';
import { checkOverlays } from './overlays.mjs';
import { checkLocalChoices } from './choices.mjs';

const base = process.argv[2];
const mode = process.argv[3] ?? 'fixture';
if (!base || !['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname)) {
  throw new Error('Pass the isolated design-library fixture URL on localhost.');
}
const artifacts = resolve('build/reports/browser');
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bosca-catalog-browser-'));
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const cdp = `http://127.0.0.1:${port}`;
const browser = spawn(process.env.BML_DESIGN_CHROME_BINARY ?? (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' });
let launchError;
browser.on('error', error => { launchError = error; });
let ws;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launchError) throw launchError;
    try { ready = (await fetch(`${cdp}/json/version`)).ok; } catch { /* Starting our own browser. */ }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(ready, 'The isolated Chrome instance did not start.');
  const targets = await (await fetch(`${cdp}/json`)).json();
  ws = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  let sequence = 0;
  const pending = new Map();
  const exceptions = [];
  const responses = [];
  const consoleErrors = [];
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      consoleErrors.push(message.params.args.map(arg => arg.value ?? arg.description));
    }
    if (message.method === 'Network.responseReceived' && message.params.response.url.startsWith(base)) {
      responses.push({ id: message.params.requestId, url: message.params.response.url, status: message.params.response.status });
      if (responses.length > 20) responses.shift();
    }
    if (!message.id) return;
    const item = pending.get(message.id);
    if (!item) return;
    pending.delete(message.id);
    clearTimeout(item.timeout);
    if (message.error) item.reject(new Error(JSON.stringify(message.error)));
    else item.resolve(message.result);
  });
  function call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}${params.expression ? `: ${params.expression}` : ""}`)); }, 10000);
      pending.set(id, { resolve, reject, timeout });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async function evaluate(expression) {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  }
  async function until(expression) {
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await evaluate(expression)) return;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    const context = await evaluate(`({ url: location.href, ready: document.readyState,
      active: document.activeElement?.outerHTML.slice(0, 1500),
      actions: Array.from(document.querySelectorAll('[data-bml-method]'), el => ({ text: el.textContent.trim(),
        method: el.dataset.bmlMethod, bound: el.dataset.bmlActionBound, pending: el.dataset.bmlActionPending })) })`);
    const failures = [];
    for (const response of responses.filter(response => response.status >= 400).slice(-4)) {
      const body = await call('Network.getResponseBody', { requestId: response.id })
        .catch(error => ({ body: `Response body unavailable: ${error.message}` }));
      failures.push({ ...response, body: body.body.slice(0, 2000) });
    }
    throw new Error(`Timed out: ${expression}\n${JSON.stringify({ context, responses, failures, consoleErrors })}`);
  }

  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');await call('Page.bringToFront');
  const key=async value=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,windowsVirtualKeyCode:{Enter:13,Tab:9,ArrowRight:39,ArrowLeft:37,ArrowDown:40,ArrowUp:38,Escape:27}[value],...(type==='keyDown'&&value==='Enter'?{text:'\r'}:{})});};
  const navigate=async path=>{await call('Page.navigate',{url:base+path});await until(`document.readyState==='complete'&&document.querySelector('main')&&location.pathname===${JSON.stringify(path)}`);};
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/');
  await evaluate(`document.fonts.ready`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`),'/');
  assert.equal(await evaluate(`document.querySelector('.code-sample code').textContent`),'<import library="io.bosca:bml-design" />');
  await evaluate(`document.querySelector('nav a[href="/foundation"]').focus()`);await key('Enter');
  await until(`location.pathname==='/foundation'&&document.querySelector('[data-catalog-form=foundation]')?.dataset.bmlActionBound==='true'`);
  assert.ok(await evaluate(`document.querySelector('[data-catalog-name=foundation]').labels.length===1&&document.querySelector('[data-catalog-name=foundation]').required`));
  await evaluate(`document.querySelector('[data-catalog-name=foundation]').value='x';document.querySelector('[data-catalog-form=foundation]').requestSubmit()`);
  await until(`!document.querySelector('[data-catalog-error=foundation]').hidden&&document.querySelector('[data-catalog-name=foundation]').getAttribute('aria-invalid')==='true'`);
  assert.equal(await evaluate(`document.querySelector('[data-catalog-name=foundation]').value`),'x');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('[data-catalog-error=foundation]')).color`),'rgb(255, 84, 112)');
  await evaluate(`(() => {const original=window.fetch.bind(window);window.fetch=async (...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseCatalogSave=resolve;});}
    return original(...args);};document.querySelector('[data-catalog-name=foundation]').value='Catalog example';document.querySelector('[data-catalog-form=foundation]').requestSubmit();})()`);
  await until(`document.querySelector('[data-catalog-form=foundation]').hasAttribute('data-bml-action-pending')&&typeof window.releaseCatalogSave==='function'`);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('[data-catalog-save=foundation] .button-content')).opacity`),'0');
  await evaluate('window.releaseCatalogSave()');
  await until(`document.querySelector('[data-catalog-save-toast=foundation]').matches(':popover-open')`);
  assert.equal(await evaluate(`document.querySelector('[data-catalog-save-toast=foundation] [role=status]').textContent.trim()`),'Saved Catalog example.');
  assert.ok(await evaluate(`!document.querySelector('[data-catalog-success], [data-catalog-saves]')`));
  await evaluate(`document.querySelector('[data-catalog-form=foundation]').requestSubmit()`);
  await until(`document.querySelector('[data-catalog-save-toast=foundation]').dataset.sequence==='2'`);
  await evaluate(`document.querySelector('[data-catalog-updates=foundation]').click()`);
  await until(`document.querySelector('[data-catalog-updates=foundation]').hasAttribute('checked')`);
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  assert.equal(await evaluate(`getComputedStyle(document.body).backgroundColor`),'rgb(248, 250, 252)');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('[data-catalog-name=foundation]')).backgroundColor`),'rgb(241, 245, 249)');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('[data-catalog-save-toast=foundation]')).color`),'rgb(22, 101, 52)');
  const desktop=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-desktop.png`),Buffer.from(desktop.data,'base64'));
  await navigate('/themes');
  await until(`document.querySelector('[data-catalog-form=light]')?.dataset.bmlActionBound==='true'`);
  assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-palette='+id+'] .ui-card')).backgroundColor)`),['rgb(16, 19, 28)','rgb(255, 255, 255)']);
  await evaluate(`document.querySelector('[data-catalog-name=light]').value='Light example';document.querySelector('[data-catalog-form=light]').requestSubmit()`);
  await until(`document.querySelector('[data-catalog-save-toast=light]').matches(':popover-open')`);
  assert.ok(await evaluate(`document.querySelector('[data-catalog-save-toast=dark]').dataset.sequence==='0'&&!document.querySelector('[data-catalog-save-toast=dark]').matches(':popover-open')`));
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-palette='+id+'] .ui-card')).backgroundColor)`),['rgb(16, 19, 28)','rgb(255, 255, 255)']);
  for(const width of [1440,390]){
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    for(const path of ['/','/foundation','/themes']){
      await navigate(path);await until('document.documentElement.scrollWidth<=innerWidth');
      assert.ok(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')===location.pathname`));
    }
  }
  const phone=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-phone.png`),Buffer.from(phone.data,'base64'));
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await navigate('/foundation');
  assert.ok(await evaluate(`(() => {const n=document.querySelector('[data-catalog-name=foundation]');return n instanceof HTMLInputElement&&n.name==='displayName'&&n.required&&n.labels.length===1;})()`));
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  assert.equal(await evaluate(`getComputedStyle(document.body).backgroundColor`),'rgb(248, 250, 252)');
  await evaluate(`document.querySelector('nav a[href="/themes"]').click()`);
  await until(`location.pathname==='/themes'&&document.readyState==='complete'`);
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await checkReferences({navigate,evaluate,until,key,call,artifacts,mode,expected:JSON.parse(process.argv[4])});
  await checkPresentation({navigate,evaluate,until,key,call,artifacts,mode});
  await checkPickers({navigate,evaluate,until,call,artifacts,mode});
  await checkSources({navigate,evaluate,until,key,call,artifacts,mode});
  await checkToastNavigation({navigate,evaluate,until,call});
  await checkJsonEditors({navigate,evaluate,until,call,artifacts,mode});
  await checkComments({navigate,evaluate,until,call,artifacts,mode});
  await checkOverlays({navigate,evaluate,until,call,artifacts,mode});
  await checkLocalChoices({navigate,evaluate,until,call,artifacts,mode});
  assert.deepEqual(exceptions,[]);
  console.log(`Catalog ${mode}: foundation, themes, 41 packaged export contracts, references, overlays, views, trees, comments, editors, aliases and native fallbacks passed`);
} finally {
  if(ws)ws.close();browser.kill('SIGTERM');
  await new Promise(resolve=>{if(browser.exitCode!==null)resolve();else{browser.once('exit',resolve);setTimeout(resolve,2000);}});
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:100});
}
