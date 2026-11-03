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
const reports = resolve('build/reports/appearance-browser');
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
  const routes=[
    'security/principals','security/groups','security/personas','security/tokens','security/passkeys',
    'jobs','backups','postgres','nats','meilisearch','utilities','storage','search','packages',
    'scheduler','scheduler/new','config','integrations','integrations/email','integrations/smtp',
    'integrations/mailgun','integrations/sendgrid','integrations/mux','integrations/push',
    'integrations/federation','integrations/bible','integrations/hubspot',
  ];
  for(const [host,origin] of [base,process.env.ATLAS_APPEARANCE_STANDALONE_URL].entries()){
    assert.ok(origin&&['localhost','127.0.0.1'].includes(new URL(origin).hostname));base=origin;
    for(const theme of ['dark','light']){
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      for(const route of routes){
        await go('/system/'+route);
        await until(`document.querySelector('.system-document[data-theme="${theme}"] main h1')`);
        if(await evaluate('Boolean(document.querySelector("[data-bml-json-editor]"))')) await until('!document.querySelector("[data-bml-json-editor]:not([data-json-ready])")');
        for(const width of [1440,390]){
          await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
          await evaluate(`document.querySelector('.system-content')?.scrollTo(0,0)`);
          const geometry=await evaluate(`(() => {const main=document.querySelector('main'), heading=main.querySelector('h1'),content=main.querySelector('.system-content');return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,title:heading.textContent,headingTop:heading.getBoundingClientRect().top,contentTop:content?.getBoundingClientRect().top,contentHeight:content?.clientHeight,cardPadding:[...main.querySelectorAll('.card-body')].slice(0,3).map(el=>getComputedStyle(el).padding),theme:getComputedStyle(document.body).colorScheme};})()`);
          assert.ok(geometry.scrollWidth<=width+2,JSON.stringify({host,theme,route,...geometry}));
          assert.ok(geometry.headingTop>=0 && geometry.headingTop<1000,JSON.stringify(geometry));
          assert.equal(geometry.theme,theme);
          const shot=await call('Page.captureScreenshot',{format:'png'});
          await writeFile(join(reports,`${host}-${theme}-${route.replaceAll('/','-')}-${width}.png`),Buffer.from(shot.data,'base64'));
          measurements.push({host,theme,route,...geometry});
        }
      }
    }
  }
  assert.deepEqual(errors,[]);await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));console.log(`System appearance captured: ${measurements.length} views.`);
} finally {
  ws?.close();chrome.kill('SIGTERM');await new Promise(resolve=>chrome.once('exit',resolve));await rm(profile,{recursive:true,force:true});
}
