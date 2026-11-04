import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';

let base = process.env.ATLAS_SYSTEM_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use an isolated local Atlas fixture.');
const password = process.env.ATLAS_SYSTEM_TEST_PASSWORD;
assert.ok(password);
const reports = resolve('build/reports/providers-browser');
await mkdir(reports, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'atlas-system-chrome-'));
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const chrome = spawn(process.env.ATLAS_CHROME_BINARY ?? (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let ws, launchError;
chrome.on('error', error => { launchError = error; });
const errors = [];
try {
  let targets;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launchError) throw launchError;
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); if (targets.some(item => item.type === 'page')) break; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(targets?.some(item => item.type === 'page'), 'Chrome did not start');
  ws = new WebSocket(targets.find(item => item.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  let sequence = 0;
  const pending = new Map();
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
    if (!message.id) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id); clearTimeout(request.timeout);
    message.error ? request.reject(message.error) : request.resolve(message.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 30000);
    pending.set(id, { resolve, reject, timeout }); ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const until = async (expression, label = expression) => {
    for (let attempt = 0; attempt < 150; attempt++) { if (await evaluate(`Boolean(${expression})`)) return; await new Promise(resolve => setTimeout(resolve, 100)); }
    throw new Error(`Timed out: ${label}. Errors: ${JSON.stringify(errors)}. Page: ${await evaluate('document.body.innerText.slice(-6000)')}`);
  };
  const go = async path => {
    await call('Page.navigate', { url: base + path });
    await until(`location.pathname === ${JSON.stringify(path.split('?')[0])} && document.readyState === 'complete' && typeof boscaAuth !== 'undefined'`);
    assert.equal(await evaluate("document.body.innerText.includes('Unable to load this page')"), false, path);
    await evaluate('document.fonts.ready.then(() => true)');
  };
  const click = async selector => {
    const rect = await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); if (!element) throw new Error('Missing ${selector}'); element.scrollIntoView({block:'center'}); const r = element.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()`);
    await call('Input.dispatchMouseEvent', { type: 'mousePressed', ...rect, button: 'left', clickCount: 1 });
    await call('Input.dispatchMouseEvent', { type: 'mouseReleased', ...rect, button: 'left', clickCount: 1 });
  };
  const clickText = async (text, selector = 'button') => {
    const target = await evaluate(`(() => { const element = [...document.querySelectorAll(${JSON.stringify(selector)})].find(item => item.textContent.trim() === ${JSON.stringify(text)} && item.getBoundingClientRect().width); if (!element) throw new Error('Missing control: '+${JSON.stringify(text)}); element.dataset.securityTestTarget='true'; return '[data-security-test-target]'; })()`);
    await click(target); await evaluate("document.querySelector('[data-security-test-target]')?.removeAttribute('data-security-test-target')");
  };
  const fill = async (selector, value) => {
    await evaluate(`(() => { const input = document.querySelector(${JSON.stringify(selector)}); if (!input) throw new Error('Missing field'); input.focus(); input.select(); })()`);
    await call('Input.insertText', { text: value });
  };
  const escape = async () => { await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); };
  await call('Page.enable'); await call('Runtime.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await go('/login');
  await fill('input[name=identifier]', 'admin'); await fill('input[name=password]', password); await click('button[type=submit]');
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')");
  const measurements = [];
  const setChecked = async (name,value) => { if (await evaluate(`document.querySelector('[name=${name}]').checked`) !== value) await click(`[name=${name}]`); };
  const choose = async (label,value) => { await click(`button[aria-label="${label}"]`); await clickText(value,'[role=option]'); };
  const stateExpression = kind => `JSON.parse(document.querySelector('script[data-bml-state-key^="system-${kind}.model"]').textContent)`;
  const fillJson = async value => {
    await until("document.querySelector('[data-json-ready] .cm-content[contenteditable=true]')");
    await click('[data-bml-json-editor] .cm-content');
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,windowsVirtualKeyCode:65});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});
    await call('Input.insertText',{text:value});
  };
  const capture = async (host,name,section = '') => {
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await evaluate('document.fonts.ready.then(()=>{window.scrollTo(0,0);document.querySelector(".shell-main").scrollTo(0,0);return true;})');
      if (section) await evaluate(`document.querySelector(${JSON.stringify(section)}).closest('.ui-card').scrollIntoView({block:'start'})`);
      const bounds=await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})'); assert.ok(bounds.scrollWidth<=width+2);
      const metrics=await call('Page.getLayoutMetrics');
      const screenshot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width,height:metrics.cssContentSize.height,scale:1}});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(screenshot.data,'base64'));
      measurements.push({host,name,...bounds});
    }
  };
  for (const [host,origin] of [base,process.env.ATLAS_PROVIDERS_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base=origin;
    await go('/system/integrations/mux');
    await fill('input[name=tokenId]','browser-mux-id'); await fill('input[name=tokenSecret]','browser-mux-secret');
    await choose('Playback policy','Signed'); await fill('input[name=maxWaitSeconds]','7200');
    await choose('Video quality','Premium'); await choose('Max resolution','2160p (4K)');
    await setChecked('mp4Support',false); await setChecked('test',true); await fill('input[name=subtitleLanguage]','fr');
    await clickText('Save Mux settings');
    await until(`${stateExpression('mux')}.hasTokenSecret && ${stateExpression('mux')}.draft.tokenSecret==='' && ${stateExpression('mux')}.draft.videoQuality==='premium'`);
    assert.equal(await evaluate("document.querySelector('input[name=tokenSecret]').value"),'');
    await capture(host,'mux');
    await capture(host,'mux-quality','select[name=videoQuality]');
    await choose('Video quality','Default'); await choose('Max resolution','Default (1080p)');
    await fill('input[name=maxWaitSeconds]','3600'); await clickText('Save Mux settings');
    await until(`${stateExpression('mux')}.draft.videoQuality==='default' && ${stateExpression('mux')}.draft.maxWaitSeconds==='3600'`);
    assert.equal(await evaluate(`${stateExpression('mux')}.hasTokenSecret`),true);
    await go('/system/integrations/push');
    await setChecked('fcmEnabled',false); await setChecked('apnsEnabled',false);
    await capture(host,'push-collapsed');
    await setChecked('enabled',true); await setChecked('fcmEnabled',true); await setChecked('apnsEnabled',true);
    await setChecked('useDeploymentCredentials',false); await setChecked('clearPrivateKey',false);
    assert.ok(await evaluate("document.querySelector('textarea[name=privateKey]').getBoundingClientRect().height>=96"));
    await fill('input[name=teamId]','BROWSERTEAM'); await fill('input[name=keyId]','BROWSERKEY'); await fill('input[name=bundleId]','io.bosca.browser');
    await fill('textarea[name=privateKey]','browser-private-key'); await setChecked('sandbox',false);
    await fillJson('{invalid'); await clickText('Save push settings');
    await until(`${stateExpression('push')}.feedback.failed===true`);
    assert.equal(await evaluate("document.querySelector('textarea[name=privateKey]').value"),'browser-private-key');
    await fillJson('{"project_id":"browser-fcm-credential"}');
    await until("document.querySelector('textarea[name=serviceAccountJson]').value.includes('browser-fcm-credential')");
    await setChecked('fcmEnabled',false); await setChecked('fcmEnabled',true);
    assert.ok(await evaluate("document.querySelector('textarea[name=serviceAccountJson]').value.includes('browser-fcm-credential')"),'toggling providers preserves a JSON draft');
    await setChecked('apnsEnabled',false); await setChecked('apnsEnabled',true);
    assert.equal(await evaluate("document.querySelector('textarea[name=privateKey]').value"),'browser-private-key','toggling providers preserves PEM draft');
    await clickText('Save push settings');
    await until(`${stateExpression('push')}.hasServiceAccount && ${stateExpression('push')}.hasPrivateKey && !${stateExpression('push')}.feedback.failed`);
    assert.equal(await evaluate("document.querySelector('textarea[name=privateKey]').value"),'');
    assert.equal(await evaluate("document.querySelector('textarea[name=serviceAccountJson]').value"),'null');
    assert.equal(await evaluate("getComputedStyle(document.querySelector('.apns-warning')).display"),'none');
    await capture(host,'push-expanded');
    await capture(host,'push-apns','input[name=teamId]');
    await setChecked('useDeploymentCredentials',true); await clickText('Save push settings');
    await until(`!${stateExpression('push')}.hasServiceAccount && !${stateExpression('push')}.draft.useDeploymentCredentials && !${stateExpression('push')}.feedback.failed`);
    assert.equal(await evaluate(`${stateExpression('push')}.hasPrivateKey`),true);
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));
  console.log('Provider browser checks passed: complete Mux settings, push toggles, FCM JSON validation/draft recovery, APNs multiline credentials and ADC clearing in both hosts; 40 responsive measurements.');
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit',resolve));
  await rm(profile,{recursive:true,force:true});
}
