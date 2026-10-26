import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
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
import { checkSearchViews } from './views.mjs';
import { checkTrees } from './trees.mjs';
import { checkDeepTrees } from './deep-tree.mjs';
import { checkRouteNavigation } from './navigation.mjs';
import { checkFeedback } from './feedback.mjs';
import { checkCopyOverflow } from './copy-overflow.mjs';
import { checkControlBorders } from './control-borders.mjs';
import { checkSurfaces } from './surfaces.mjs';
import { checkAccessibility } from './accessibility.mjs';
import { checkTabFocus } from './tab-focus.mjs';
import { checkJsonTables } from './json-tables.mjs';
import { checkEmptyWidgets } from './empty-widgets.mjs';
import { checkSourceSearch } from './source-search.mjs';
import { checkSourcePanel } from './source-panel.mjs';
import { checkContrastVisibility } from './contrast-visibility.mjs';
import { checkAppearance } from './appearance.mjs';
import { checkDocuments } from './documents.mjs';
import { checkSourcePolish } from './source-polish.mjs';
import { checkWorkspaces } from './workspaces.mjs';
import { checkShell } from './shell-checks.mjs';
import { checkVisualizations } from './visualizations.mjs';
import { checkDashboards } from './dashboards.mjs';
import { driverDiagnostics } from './driver-diagnostics.mjs';

const base = process.argv[2];
const mode = process.argv[3] ?? 'fixture';
const started=performance.now();
const checkpoint=phase=>console.log(`Catalog ${mode} ${phase}: ${Math.round(performance.now()-started)}ms`);
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
let ws, diagnostics;
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
    diagnostics?.event(message);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      consoleErrors.push(message.params.args.map(arg => arg.value ?? arg.description));
    }
    if (message.method === 'Network.responseReceived' && message.params.response.url.startsWith(base)) {
      responses.push({ id: message.params.requestId, url: message.params.response.url, status: message.params.response.status });
      if (responses.length > 20) responses.shift();
    }
    if (!message.id) return;
    diagnostics?.received(message.id, message.error);
    const item = pending.get(message.id);
    if (!item) return;
    pending.delete(message.id);
    clearTimeout(item.timeout);
    if (message.error) item.reject(new Error(JSON.stringify(message.error)));
    else item.resolve(message.result);
  });
  function call(method, params = {}, deadline = 10000) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      diagnostics?.sent(id, method, params);
      const timeout = setTimeout(() => { pending.delete(id); diagnostics?.timedOut(id); reject(new Error(`CDP timeout: ${method}${params.expression ? `: ${params.expression}` : ""}`)); }, deadline);
      pending.set(id, { resolve, reject, timeout });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }
  diagnostics = driverDiagnostics({ browser, cdp, artifacts, mode, call });
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
  // Enable before a possible long-running evaluation so a failure probe can interrupt V8.
  await call('Debugger.enable');
  const key=async value=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,windowsVirtualKeyCode:{Enter:13,Tab:9,ArrowRight:39,ArrowLeft:37,ArrowDown:40,ArrowUp:38,Escape:27}[value],...(type==='keyDown'&&value==='Enter'?{text:'\r'}:{})});};
  const navigate=async path=>{await call('Page.navigate',{url:base+path});await until(`document.readyState==='complete'&&document.querySelector('main')&&location.pathname===${JSON.stringify(path)}`);};
  if(['--driver-promise-probe','--driver-busy-probe'].includes(process.argv[4])) {
    const busy = process.argv[4]==='--driver-busy-probe';
    const expression = busy ? '(function controlledBusyProbe(){while(true){}})()\n//# sourceURL=bml-driver-busy-probe.js' : 'new Promise(() => {})';
    let observed;
    try { await evaluate(expression); } catch (error) { observed = error; }
    assert.match(observed?.message ?? '', /CDP timeout: Runtime.evaluate/);
    const path = await diagnostics.capture(observed), report = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(report.error.message, observed.message);
    assert.equal(report.failedOperation.state, 'timeout');
    assert.ok(report.failedOperation.elapsed>=10000, 'The original ten-second request deadline is unchanged');
    assert.ok(report.probes.browser.product.startsWith('Chrome/'));
    assert.ok(report.processes.some(row=>row.pid===browser.pid));
    assert.ok(report.probes.targets.some(target=>target.type==='page'));
    if(busy) {
      assert.match(report.probes.rendererError.message, /CDP timeout/);
      assert.ok(report.probes.paused.frames.some(frame=>frame.functionName==='controlledBusyProbe'));
      assert.ok(report.probes.paused.frames.some(frame=>frame.url==='bml-driver-busy-probe.js'));
    } else assert.equal(report.probes.renderer.result.value.ready,'complete');
    console.log(`Driver ${busy?'busy renderer':'pending promise'} evidence verified: ${path}`);
  } else if(process.argv[4]==='--deep-tree') {
    await checkDeepTrees({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--tree-accessibility') {
    await checkAccessibility({navigate,evaluate,until,call,artifacts,mode,paths:['/trees'],reportName:'tree-accessibility'});
  } else if(process.argv[4]==='--surfaces') {
    await checkSurfaces({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--feedback') {
    await checkFeedback({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--dashboards') {
    await checkDashboards({navigate,evaluate,until,call,key,artifacts,mode});
  } else if(process.argv[4]==='--dashboard-accessibility') {
    await checkAccessibility({navigate,evaluate,until,call,artifacts,mode,paths:['/dashboards','/components/visualization'],reportName:'dashboard-accessibility'});
  } else if(process.argv[4]==='--visualizations') {
    await checkVisualizations({navigate,evaluate,until,call,key,artifacts,mode});
  } else if(process.argv[4]==='--visualization-accessibility') {
    await checkAccessibility({navigate,evaluate,until,call,artifacts,mode,paths:['/flows','/charts','/components/visualization'],reportName:'visualization-accessibility'});
  } else if(process.argv[4]==='--editor-accessibility') {
    await checkAccessibility({navigate,evaluate,until,call,artifacts,mode,paths:['/documents','/source/languages','/components/content','/components/controls']});
  } else if(process.argv[4]==='--documents') {
    await checkDocuments({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--shell') {
    await checkShell({call,evaluate,until:expression=>until(`Boolean(${expression})`),base,mode,artifacts});
  } else if(process.argv[4]==='--workspaces') {
    await checkWorkspaces({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--source-polish') {
    await checkSourcePolish({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--appearance') {
    await checkAppearance({navigate,evaluate,until,call});
  } else if(process.argv[4]==='--contrast-visibility') {
    await checkContrastVisibility({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--source-panel') {
    await checkSourcePanel({navigate,evaluate,until,call,key,artifacts,mode});
  } else if(process.argv[4]==='--source-search') {
    await checkSourceSearch({navigate,evaluate,until,call,key,artifacts,mode});
  } else if(process.argv[4]==='--empty-widgets') {
    await checkEmptyWidgets({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--json-tables') {
    await checkJsonTables({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--choices') {
    await checkLocalChoices({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--accessibility') {
    await checkAccessibility({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--tab-focus') {
    await checkTabFocus({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--copy-overflow') {
    await checkCopyOverflow({navigate,evaluate,until,call,artifacts,mode});
  } else if(process.argv[4]==='--control-borders') {
    await checkControlBorders({navigate,evaluate,until,call,artifacts,mode});
  } else {
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
  checkpoint('foundation complete');
  await checkReferences({navigate,evaluate,until,key,call,artifacts,mode,expected:JSON.parse(process.argv[4])});
  checkpoint('references complete');
  await checkPresentation({navigate,evaluate,until,key,call,artifacts,mode});
  await checkPickers({navigate,evaluate,until,call,artifacts,mode});
  checkpoint('pickers complete');
  await checkSources({navigate,evaluate,until,key,call,artifacts,mode});
  await checkToastNavigation({navigate,evaluate,until,call});
  await checkJsonEditors({navigate,evaluate,until,call,artifacts,mode});
  checkpoint('editors complete');
  await checkComments({navigate,evaluate,until,call,artifacts,mode});
  await checkOverlays({navigate,evaluate,until,call,artifacts,mode});
  await checkLocalChoices({navigate,evaluate,until,call,artifacts,mode});
  checkpoint('choices complete');
  await checkSearchViews({navigate,evaluate,until,call,artifacts,mode});
  await checkTrees({navigate,evaluate,until,call,artifacts,mode});
  checkpoint('trees complete');
  await checkRouteNavigation({navigate,evaluate,until,call,artifacts,mode});
  await checkFeedback({navigate,evaluate,until,call,artifacts,mode});
  await checkSurfaces({navigate,evaluate,until,call,artifacts,mode});
  }
  assert.deepEqual(exceptions,[]);
  if(!process.argv[4]?.startsWith('--')) console.log(`Catalog ${mode}: foundation, themes, ${Object.keys(JSON.parse(process.argv[4])).length} packaged export contracts, references, overlays, views, trees, comments, editors, aliases and native fallbacks passed`);
} catch (error) {
  try { await diagnostics?.capture(error); }
  catch (diagnosticError) { console.error('Browser diagnostics failed:', diagnosticError); }
  throw error;
} finally {
  checkpoint('cleanup started');
  if(ws)ws.close();browser.kill('SIGTERM');
  await new Promise(resolve => {
    if (browser.exitCode !== null || browser.signalCode !== null) return resolve();
    const timeout = setTimeout(resolve, 2000);
    browser.once('exit', () => { clearTimeout(timeout); resolve(); });
  });
  checkpoint('browser wait finished');
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:100});
  checkpoint('profile removed');
}
