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
const reports = resolve('build/reports/hubspot-browser');
await mkdir(reports, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'atlas-system-chrome-'));
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const chrome = spawn(process.env.ATLAS_CHROME_BINARY ?? (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let ws, launchError;
chrome.on('error', error => { launchError = error; });
const errors = [], actionRequests = [];
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
    if (message.method === 'Network.requestWillBeSent' && message.params.request.url.endsWith('/_bml/action')) {
      const request = JSON.parse(message.params.request.postData);
      actionRequests.push({method:request.method,args:request.args,stateKey:request.stateKey});
    }
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
    throw new Error(`Timed out: ${label}. Errors: ${JSON.stringify(errors)}. Actions: ${JSON.stringify(actionRequests.slice(-8))}. Native: ${JSON.stringify(await evaluate("window.hubspotNativeTrace"))}. Pending: ${await evaluate('Array.from(document.querySelectorAll("[data-bml-action-pending]"), e=>e.outerHTML.slice(0,300))')}. Page: ${await evaluate('document.body.innerText.slice(-6000)')}`);
  };
  const go = async path => {
    await call('Page.navigate', { url: base + path });
    await until(`location.pathname === ${JSON.stringify(path.split('?')[0])} && document.readyState === 'complete' && typeof boscaAuth !== 'undefined'`);
    assert.equal(await evaluate("document.body.innerText.includes('Unable to load this page')"), false, path);
    await evaluate('document.fonts.ready.then(() => true)');
  };
  const click = async selector => {
    await evaluate(`document.querySelector(${JSON.stringify(selector)})?.scrollIntoView({block:'center'})`);
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const rect = await evaluate(`(() => { window.hubspotNativeTrace ??= []; if (!window.hubspotNativeBound) {window.hubspotNativeBound=true; for (const type of ["mousedown","mouseup","click"]) document.addEventListener(type,event=>{const button=event.target.closest?.("button");if(button){window.hubspotNativeTrace.push({type,button:button.outerHTML.slice(0,900),active:document.activeElement?.outerHTML.slice(0,200)});window.hubspotNativeTrace=window.hubspotNativeTrace.slice(-9);}},true);} const element = document.querySelector(${JSON.stringify(selector)}); if (!element) throw new Error('Missing ${selector}'); element.scrollIntoView({block:'center'}); const r = element.getBoundingClientRect(); const x=r.x+r.width/2,y=r.y+r.height/2; const hit=document.elementFromPoint(x,y); if(hit!==element&&!element.contains(hit)) throw new Error('Click blocked: '+element.outerHTML.slice(0,300)+' by '+hit?.outerHTML.slice(0,300)); return {x,y}; })()`);
    await call('Input.dispatchMouseEvent', { type: 'mousePressed', ...rect, button: 'left', clickCount: 1 });
    await call('Input.dispatchMouseEvent', { type: 'mouseReleased', ...rect, button: 'left', clickCount: 1 });
  };
  const clickText = async (text, selector = 'button') => {
    const target = await evaluate(`(() => { const element = [...document.querySelectorAll(${JSON.stringify(selector)})].find(item => item.textContent.trim() === ${JSON.stringify(text)} && item.getBoundingClientRect().width); if (!element) throw new Error('Missing control: '+${JSON.stringify(text)}); element.dataset.securityTestTarget='true'; return '[data-security-test-target]'; })()`);
    await click(target); await evaluate("document.querySelector('[data-security-test-target]')?.removeAttribute('data-security-test-target')");
  };
  const fill = async (selector, value) => {
    await click(selector);
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: process.platform === 'darwin' ? 4 : 2, windowsVirtualKeyCode: 65, commands: ['selectAll'] });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65 });
    if (value) await call('Input.insertText', { text: value });
    else {
      await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
      await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    }
    const field = await evaluate(`(() => { const input = document.querySelector(${JSON.stringify(selector)}); return {value:input.value,focused:document.activeElement===input,disabled:input.disabled,readOnly:input.readOnly}; })()`);
    assert.equal(field.value, value, `Native field ${selector}: ${JSON.stringify(field)}`);
  };
  const escape = async () => { await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); };
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable'); await call('Page.bringToFront');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await go('/login');
  await fill('input[name=identifier]', 'admin'); await fill('input[name=password]', password); await click('button[type=submit]');
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')");
  const measurements=[];
  const settings=`JSON.parse(document.querySelector('script[data-bml-state-key^="system-hubspot-settings.model"]').textContent)`;
  const preview=`JSON.parse(document.querySelector('script[data-bml-state-key^="system-hubspot-preview.model"]').textContent)`;
  const fillCode=async(name,value)=>{
    const selector=`[data-bml-code-editor][data-name="${name}"] .cm-content`;
    await until(`document.querySelector(${JSON.stringify(selector)})`);
    await click(selector);
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,commands:['selectAll']});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA'});
    await call('Input.insertText',{text:value});
    assert.equal(await evaluate(`document.querySelector('textarea[name="${name}"]').value`),value);
  };
  const capture=async(host,name,selector)=>{
    for(const width of [1920,1024,768,390]){
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
      const bounds=await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})');
      assert.ok(bounds.scrollWidth<=width+2,JSON.stringify(bounds));
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(shot.data,'base64'));
      measurements.push({host,name,...bounds});
    }
  };
  const choose=async label=>{await click('button[aria-label="Profile"]');await clickText(label,'[role=option]');};
  for(const [host,origin] of [base,process.env.ATLAS_HUBSPOT_STANDALONE_URL].entries()){
    assert.ok(origin&&['localhost','127.0.0.1'].includes(new URL(origin).hostname));base=origin;
    await go('/system/integrations/hubspot');
    await until('document.querySelectorAll("[data-code-ready]").length>=2');
    assert.equal(await evaluate('document.querySelector("input[name=token]").value'),'');
    await fill('input[name=token]','browser-hubspot-token');
    await fill('input[name=associationTypeId]','279');
    await fill('input[name=associationCategory]','HUBSPOT_DEFINED');
    await fillCode('genericExpression','{"firstname": profile.name}');
    await fillCode('organizationExpression','{"name": organization.name}');
    await evaluate(`(() => {const original=window.fetch; window.fetch=(input,init)=>{if(String(input).endsWith('/_bml/action')&&JSON.parse(init.body).method==='save'){window.fetch=original;return new Promise((resolve,reject)=>{window.releaseHeaderSave=()=>original.call(window,input,init).then(resolve,reject);});}return original.call(window,input,init);};})()`);
    await clickText('Save HubSpot settings');
    await until("typeof window.releaseHeaderSave==='function' && document.querySelector('form#system-hubspot-settings').hasAttribute('data-bml-action-pending') && document.querySelector('button[form=system-hubspot-settings]').getAttribute('aria-busy')==='true'");
    assert.equal(await evaluate("getComputedStyle(document.querySelector('button[form=system-hubspot-settings] .button-content')).opacity"),'0','Header button owns the pending spinner state');
    await evaluate('window.releaseHeaderSave();delete window.releaseHeaderSave');
    await until(`${settings}.feedback.message.startsWith('HubSpot settings saved.') && !${settings}.feedback.failed`);
    assert.equal(await evaluate('document.querySelector("input[name=token]").value'),'');
    await until("document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent.startsWith('HubSpot settings saved.')");
    assert.equal(await evaluate("document.querySelectorAll('.alert[data-tone=success]').length"),0,'Settings success is a toast');
    await click('[data-system-success]:popover-open button[aria-label="Dismiss notification"]');
    await until("!document.querySelector('[data-system-success]:popover-open')");
    await capture(host,'general','input[name=token]');
    const draft='{"firstname": profile.name & " live"}';
    await fillCode('genericExpression',draft);
    await capture(host,'expressions','[data-bml-code-editor][data-name=genericExpression]');
    await fill('input[name=query]','HubSpot');await clickText('Search profiles');
    await until(`${preview}.profiles.length>=2`);
    await choose('HubSpot Reader · GENERIC');await clickText('Load profile');
    await until(`${preview}.profileId===${JSON.stringify(process.env.ATLAS_HUBSPOT_PERSON_ID)} && document.querySelector('textarea[name=hubspotOutput]')?.value.includes('HubSpot Reader live')`);
    assert.equal(await evaluate('document.querySelector("textarea[name=genericExpression]").value'),draft,'profile loading retains the settings draft');
    await capture(host,'person-preview','[data-hubspot-preview] .preview-grid');
    await fillCode('genericExpression','profile.');
    await until('!document.querySelector("[data-hubspot-preview-error]").hidden');
    await capture(host,'preview-error','[data-hubspot-preview-error]');
    await fillCode('genericExpression',draft);
    await until('document.querySelector("[data-hubspot-preview-error]").hidden');
    await clickText('Sync this profile');await until(`${preview}.feedback.message.startsWith('Sync started for')`);
    assert.equal(await evaluate('document.querySelector("textarea[name=genericExpression]").value'),draft);
    await choose('HubSpot Company · ORGANIZATION');await clickText('Load profile');
    await until(`${preview}.profileId===${JSON.stringify(process.env.ATLAS_HUBSPOT_COMPANY_ID)} && document.querySelector('textarea[name=hubspotOutput]')?.value.includes('HubSpot Company')`);
    await capture(host,'company-preview','[data-hubspot-preview] .preview-grid');
    await clickText('Refresh context');await until(`${preview}.profileType==='ORGANIZATION' && !document.querySelector('[data-bml-action-pending]')`);
    await clickText('Sync this profile');await until(`${preview}.feedback.message==='Sync started for HubSpot Company.'`);
    await clickText('Sync all profiles');await until(`${preview}.feedback.message==='Sync started for all profiles.'`);
    await go('/system/integrations');
    await until('document.querySelector(".integration-grid")');
    const cards=await evaluate(`JSON.parse(document.querySelector('script[data-bml-state-key^="system-integrations.model"]').textContent).cards`);
    assert.equal(cards.length,8);assert.equal(cards.find(card=>card.key==='hubspot').configured,true);
    await capture(host,'integration-index','.integration-grid');
    await click('a[href="/system/integrations/hubspot"]');await until('location.pathname==="/system/integrations/hubspot"');
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));
  console.log(`HubSpot and integration index passed in both hosts: ${measurements.length} measurements.`);
} finally {
  ws?.close();chrome.kill('SIGTERM');await new Promise(resolve=>chrome.once('exit',resolve));await rm(profile,{recursive:true,force:true});
}
