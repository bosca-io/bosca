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
const reports = resolve('build/reports/backups-browser');
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
  const reportsData = [];
  const captureDialog = async (host, name) => {
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      const bounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:document.querySelector("dialog[open]").getBoundingClientRect().width})');
      assert.ok(bounds.scrollWidth <= width+2 && bounds.dialogWidth <= width);
      const screenshot = await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(screenshot.data,'base64'));
      reportsData.push({host,name,...bounds});
    }
  };
  for (const [index, origin] of [base, process.env.ATLAS_BACKUPS_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    await go('/system/backups');
    await clickText('Create backup'); await until("document.querySelector('dialog[open] input[name=includeFiles]')");
    assert.equal(await evaluate("document.querySelector('dialog[open] input[name=includeFiles]').checked"), false);
    if (index === 1) await click('dialog[open] input[name=includeFiles]');
    await captureDialog(index,'create');
    const state = () => evaluate("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-backups.model\"]').textContent)");
    const original = (await state()).entries.map(entry=>entry.id);
    await click('dialog[open] button[type=submit]');
    await until("!document.querySelector('dialog[open]') && document.body.innerText.includes('Backup started.')");
    const created = (await state()).entries.find(entry=>!original.includes(entry.id));
    assert.ok(created); assert.equal(created.includeFiles,index===1);
    for (let attempt=0; attempt<100; attempt++) {
      await clickText('Refresh');
      await until("![...document.querySelectorAll('button')].some(button=>button.disabled && button.textContent.trim()==='Refresh')");
      const current = (await state()).entries.find(entry=>entry.id===created.id);
      if (current.status==='completed') break;
      assert.notEqual(current.status,'failed',current.error);
      await new Promise(resolve=>setTimeout(resolve,150));
    }
    assert.equal((await state()).entries.find(entry=>entry.id===created.id).status,'completed');
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      const bounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})');
      assert.ok(bounds.scrollWidth <= width+2);
      const screenshot = await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${index}-list-${width}.png`),Buffer.from(screenshot.data,'base64'));
      reportsData.push({host:index,name:'list',...bounds});
    }
    const token = await evaluate('boscaAuth.token');
    const history = async () => {
      const response = await fetch(base+'/graphql',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify({query:'{ scheduler { history(offset: 0, limit: 100) { id name status errorMessage } } }'})});
      assert.equal(response.status,200); const result = await response.json(); assert.equal(result.errors,undefined,JSON.stringify(result));
      return result.data.scheduler.history;
    };
    const previousExecutions = (await history()).map(entry=>entry.id);
    await clickText('Restore',`[data-backup-id="${created.id}"] button`);
    await until("document.querySelector('dialog[open] input[name=confirmed]')");
    assert.ok(await evaluate("document.querySelector('dialog[open]').innerText.includes('Skip — keep existing data')"));
    if (index === 1) {
      await click('dialog[open] button[aria-label="Conflict strategy"]');
      await clickText('Overwrite — replace with backup','[role=option]');
      assert.equal(await evaluate("document.querySelector('dialog[open] select[name=strategy]').value"),'OVERWRITE');
    }
    await captureDialog(index,'restore');
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until("!document.querySelector('dialog[open]') && document.body.innerText.includes('Restore started.')");
    let restored;
    for (let attempt=0; attempt<150; attempt++) {
      restored = (await history()).find(entry=>entry.name==='restore-system' && !previousExecutions.includes(entry.id) && ['COMPLETED','FAILED'].includes(entry.status));
      if (restored) break;
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(restored?.status,'COMPLETED',JSON.stringify(restored));
    await click(`[data-backup-id="${created.id}"] button[aria-label="Delete backup"]`);
    await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await captureDialog(index,'delete');
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until(`!document.querySelector('dialog[open]') && !document.querySelector('[data-backup-id="${created.id}"]')`);
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(reportsData,null,2));
  console.log('Backup browser checks passed: file inclusion, creation, completion refresh, restore selection and confirmed deletion in both hosts; 32 responsive measurements.');
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit',resolve));
  await rm(profile,{recursive:true,force:true});
}
