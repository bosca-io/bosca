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
const reports = resolve('build/reports/nats-browser');
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
    throw new Error(`Timed out: ${label}. Errors: ${JSON.stringify(errors)}. Actions: ${JSON.stringify(actionRequests.slice(-8))}. Pending: ${await evaluate('Array.from(document.querySelectorAll("[data-bml-action-pending]"), e=>e.outerHTML.slice(0,300))')}. Page: ${await evaluate('document.body.innerText.slice(-6000)')}`);
  };
  const go = async path => {
    await call('Page.navigate', { url: base + path });
    await until(`location.pathname === ${JSON.stringify(path.split('?')[0])} && document.readyState === 'complete' && typeof boscaAuth !== 'undefined'`);
    assert.equal(await evaluate("document.body.innerText.includes('Unable to load this page')"), false, path);
    await evaluate('document.fonts.ready.then(() => true)');
  };
  const click = async selector => {
    const rect = await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); if (!element) throw new Error('Missing ${selector}'); element.scrollIntoView({block:'center'}); const r = element.getBoundingClientRect(); const x=r.x+r.width/2,y=r.y+r.height/2; const hit=document.elementFromPoint(x,y); if(hit!==element&&!element.contains(hit)) throw new Error('Click blocked: '+element.outerHTML.slice(0,300)+' by '+hit?.outerHTML.slice(0,300)); return {x,y}; })()`);
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
  const state=`JSON.parse(document.querySelector('script[data-bml-state-key^="system-nats.model"]').textContent)`;
  const streamPanel='[data-nats-stream-panel] dialog[open]', bucketPanel='[data-nats-bucket-panel] dialog[open]', confirmation='[data-nats-confirmation] dialog[open]';
  const capture=async(host,name,selector)=>{
    for(const width of [1920,1024,768,390]){
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
      const bounds=await evaluate(`(() => {const panel=[...document.querySelectorAll('dialog[open]')].at(-1), footer=panel?.querySelector('.modal-footer'), box=panel?.getBoundingClientRect(), actions=footer?.getBoundingClientRect();return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:box?.width??0,drawer:panel?.dataset.placement==='right',drawerTop:box?.top,drawerRight:box?.right,drawerHeight:box?.height,footerVisible:!actions||actions.top>=box.top&&actions.bottom<=Math.min(innerHeight,box.bottom)+2};})()`);
      assert.ok(bounds.scrollWidth<=width+2 && bounds.dialogWidth<=width,JSON.stringify(bounds));
      if(bounds.drawer) assert.ok(Math.abs(bounds.drawerTop)<2 && Math.abs(bounds.drawerRight-width)<2 && Math.abs(bounds.drawerHeight-1000)<2,`Right drawer: ${JSON.stringify(bounds)}`);
      assert.ok(bounds.footerVisible,`Fixed modal actions: ${JSON.stringify(bounds)}`);
      assert.ok(await evaluate("[...document.querySelectorAll('[data-resource-name]')].every(name=>!name.closest('button,a,[role=button]')&&!name.closest('td').querySelector('button,a,[role=button]'))"),'Resource names are plain table text with separate actions');
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(shot.data,'base64'));
      measurements.push({host,name,...bounds});
    }
  };
  const tab=async name=>{await click(`[data-nats-tabs] [data-tab="${name}"]`);await until(`${state}.tab===${JSON.stringify(name)} && !document.querySelector('#nats-panel-${name}').hidden`);};
  const confirm=async()=>{
    await until(`document.querySelector('${confirmation} input[name=confirmed]')`);
    await click(`${confirmation} input[name=confirmed]`);await click(`${confirmation} button[type=submit]`);
    await until(`${state}.operationCompleted && !document.querySelector('${confirmation}')`);
  };
  const fillJson=async text=>{
    const selector=`${bucketPanel} [data-bml-json-editor][data-name=value] .cm-content[contenteditable=true]`;
    await until(`document.querySelector(${JSON.stringify(selector)})`);await click(selector);
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,windowsVirtualKeyCode:65,commands:['selectAll']});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});
    await call('Input.insertText',{text});
  };
  for(const [host,origin] of [base,process.env.ATLAS_NATS_STANDALONE_URL].entries()){
    assert.ok(origin&&['localhost','127.0.0.1'].includes(new URL(origin).hostname));base=origin;
    const name=`system_ui_nats_${host+2}`;
    await go('/system/nats');await until(`${state}.overview && document.querySelector('[data-nats-console]')`);
    assert.equal(await evaluate('document.querySelectorAll("[data-nats-tabs] [role=tab]").length'),5);
    await capture(host,'overview','#nats-panel-overview');
    await clickText('Server details','summary');await clickText('JetStream server configuration','summary');
    await capture(host,'server-details','#nats-panel-overview details');
    await tab('connections');await fill('[data-bml-preserve=nats-connection-filter] input','SYSTEM-UI-NATS-FIXTURE');
    await until(`${state}.connectionFilter==='SYSTEM-UI-NATS-FIXTURE' && document.querySelectorAll('[data-nats-connection]').length===1`);
    await click('[data-nats-connection] summary');await capture(host,'connections','#nats-panel-connections');
    await tab('routes');await capture(host,'routes','#nats-panel-routes');
    await tab('streams');await fill('[data-bml-preserve=nats-stream-filter] input',name.toUpperCase());
    await until(`${state}.streamFilter===${JSON.stringify(name.toUpperCase())}`);
    const refreshBefore=await evaluate(`${state}.refreshes`);
    await until(`${state}.refreshes>${refreshBefore}`,'automatic overview refresh');
    assert.equal(await evaluate('document.querySelector("[data-bml-preserve=nats-stream-filter] input").value'),name.toUpperCase());
    await capture(host,'streams','#nats-panel-streams');
    await click(`[data-nats-stream="${name}"] button`);await until(`document.querySelector('${streamPanel}') && ${state}.messages.length===4`);
    assert.ok(await evaluate(`document.querySelector('${streamPanel}').innerText.includes('fixture_consumer')`));
    await capture(host,'stream',streamPanel);
    await click(`[data-nats-message="9007199254740993"] button`);
    await until(`document.querySelector('[data-nats-message="9007199254740993"] [data-json-ready]')`);
    assert.ok(await evaluate(`document.querySelector('[data-nats-message="9007199254740993"]').innerText.includes('Fixture-Header')`));
    await capture(host,'json-message','[data-nats-message="9007199254740993"]');
    await clickText('Delete message');await until(`document.querySelector('${confirmation}')`);
    await capture(host,'message-delete',confirmation);await clickText('Cancel',`${confirmation} button`);
    await until(`!document.querySelector('${confirmation}') && document.querySelector('${streamPanel}')`);
    await clickText('Delete message');await confirm();
    assert.equal(actionRequests.findLast(request=>request.method==='prepareStreamOperation').args[1],'9007199254740993','sequence is an exact string in the browser action');
    await until(`!document.querySelector('[data-nats-message="9007199254740993"]')`);
    await fill(`${streamPanel} input[name=subjectFilter]`,`${name}.*`);
    await fill(`${streamPanel} input[name=fromSeq]`,'9007199254740994');await fill(`${streamPanel} input[name=toSeq]`,'9007199254740995');
    await fill(`${streamPanel} input[name=dataSearch]`,'TEXT');await clickText('Apply message filters');
    await until(`${state}.messages.length===1 && ${state}.dataSearch==='TEXT'`);
    await click('[data-nats-message="9007199254740994"] button');
    await until(`document.querySelector('[data-nats-message="9007199254740994"] pre')`);
    await capture(host,'text-message','[data-nats-message="9007199254740994"]');
    assert.equal(await evaluate(`document.querySelector('${streamPanel} input[name=fromSeq]').value`),'9007199254740994');
    await clickText('Purge stream tombstones');await until(`document.querySelector('${confirmation}')`);await capture(host,'stream-tombstones',confirmation);await confirm();
    await fill(`${streamPanel} input[name=dataSearch]`,'');await fill(`${streamPanel} input[name=fromSeq]`,'');await fill(`${streamPanel} input[name=toSeq]`,'');
    await clickText('Apply message filters');await until(`${state}.messages.length===2 && ${state}.dataSearch===''`);
    await click('[data-nats-message="9007199254740995"] button');await until(`document.querySelector('[data-nats-message="9007199254740995"] pre')`);
    assert.ok(await evaluate(`document.querySelector('[data-nats-message="9007199254740995"] pre').innerText.includes('(empty)')`));
    await capture(host,'empty-message','[data-nats-message="9007199254740995"]');
    await clickText('Purge stream');await until(`document.querySelector('${confirmation}')`);await capture(host,'purge-stream',confirmation);await confirm();
    await until(`${state}.messages.length===0 && document.querySelector('${streamPanel}')`);await escape();await until('!document.querySelector("dialog[open]")');
    assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-nats-stream-trigger]')`));
    await tab('kv');assert.equal(await evaluate('document.querySelectorAll("dialog[open]").length'),0,'dismissed stream stays closed after tab changes');
    await fill('[data-bml-preserve=nats-bucket-filter] input',name);await until(`${state}.bucketFilter===${JSON.stringify(name)}`);
    await capture(host,'buckets','#nats-panel-kv');await click(`[data-nats-bucket="${name}"] button`);await until(`document.querySelector('${bucketPanel}') && ${state}.entries.length===3`);
    await capture(host,'bucket',bucketPanel);
    await fill(`${bucketPanel} input[name=key]`,'unsaved.key');await fill(`${bucketPanel} textarea[name=value]`,'unsaved text');
    await fill(`${bucketPanel} input[name=keyFilter]`,'fixture.*');await fill(`${bucketPanel} input[name=valueSearch]`,'TRUE');
    await clickText('Apply entry filters');await until(`${state}.entries.length===1 && ${state}.valueSearch==='TRUE'`);
    assert.equal(await evaluate(`document.querySelector('${bucketPanel} textarea[name=value]').value`),'unsaved text');
    await click('[data-nats-entry="fixture.json"] button');await until(`document.querySelector('[data-nats-entry="fixture.json"] [data-json-ready]')`);
    await capture(host,'json-entry','[data-nats-entry="fixture.json"]');
    await clickText('Delete key');await until(`document.querySelector('${confirmation}')`);await capture(host,'delete-key',confirmation);await confirm();
    assert.equal(await evaluate(`document.querySelector('${bucketPanel} textarea[name=value]').value`),'unsaved text');
    await fill(`${bucketPanel} input[name=keyFilter]`,'');await fill(`${bucketPanel} input[name=valueSearch]`,'TEXT');await clickText('Apply entry filters');
    await until(`${state}.entries.length===1 && ${state}.valueSearch==='TEXT'`);await click('[data-nats-entry="fixture.text"] button');
    await until(`document.querySelector('[data-nats-entry="fixture.text"] pre')`);await capture(host,'text-entry','[data-nats-entry="fixture.text"]');
    await fill(`${bucketPanel} input[name=key]`,'invalid key');await fill(`${bucketPanel} textarea[name=value]`,'retained rejected value');
    await clickText('Put entry');await until(`${state}.feedback.failed`);
    assert.equal(await evaluate(`document.querySelector('${bucketPanel} textarea[name=value]').value`),'retained rejected value');await capture(host,'write-error',bucketPanel);
    await fill(`${bucketPanel} input[name=key]`,'browser.json');await fill(`${bucketPanel} textarea[name=value]`,'{"browser":true}');
    await click(`${bucketPanel} button[aria-label="Value format"]`);await clickText('JSON','[role=option]');
    await until(`${state}.valueMode==='json' && document.querySelector('${bucketPanel} [data-json-ready] .cm-content[contenteditable=true]')`);
    await fillJson('{"browser":true,"native":true}');await capture(host,'json-writer',`${bucketPanel} [data-bml-json-editor][data-name=value]`);
    await clickText('Put entry');await until(`${state}.operationNotice==='Stored browser.json.' && !${state}.feedback.failed`);
    await click(`${bucketPanel} button[aria-label="Value format"]`);await clickText('Text','[role=option]');await until(`${state}.valueMode==='text'`);
    await fill(`${bucketPanel} input[name=key]`,'browser.empty');await fill(`${bucketPanel} textarea[name=value]`,'');
    await clickText('Put entry');await until(`${state}.operationNotice==='Stored browser.empty.' && !${state}.feedback.failed`);
    await clickText('Purge key-value tombstones');await until(`document.querySelector('${confirmation}')`);await capture(host,'bucket-tombstones',confirmation);await confirm();
    await escape();await until('!document.querySelector("dialog[open]")');
    assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-nats-bucket-trigger]')`));
    await tab('overview');assert.equal(await evaluate('document.querySelectorAll("dialog[open]").length'),0,'dismissed bucket stays closed');
    await tab('streams');await fill('[data-bml-preserve=nats-stream-filter] input','system_ui_nats_protected');
    await until(`${state}.streamFilter==='system_ui_nats_protected'`);await click('[data-nats-stream="system_ui_nats_protected"] button');
    await until(`document.querySelector('${streamPanel}') && ${state}.streamName==='system_ui_nats_protected'`);
    await fill(`${streamPanel} input[name=subjectFilter]`,'retained.>');await clickText('Purge stream');
    await until(`document.querySelector('${confirmation}')`);await click(`${confirmation} input[name=confirmed]`);await click(`${confirmation} button[type=submit]`);
    await until(`${state}.feedback.failed && !${state}.operationCompleted && document.querySelector('${confirmation}')`);
    assert.equal(await evaluate(`document.querySelector('${streamPanel} input[name=subjectFilter]').value`),'retained.>');
    assert.ok(await evaluate(`document.querySelector('${confirmation}').innerText.toLowerCase().includes('purge')`));
    await capture(host,'purge-rejected',confirmation);await clickText('Cancel',`${confirmation} button`);await until(`!document.querySelector('${confirmation}')`);
    await escape();await until('!document.querySelector("dialog[open]")');
  }
  assert.deepEqual(errors,[]);await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));console.log(`NATS browser passed in both hosts: ${measurements.length} measurements.`);
} finally {
  ws?.close();chrome.kill('SIGTERM');await new Promise(resolve=>chrome.once('exit',resolve));await rm(profile,{recursive:true,force:true});
}
