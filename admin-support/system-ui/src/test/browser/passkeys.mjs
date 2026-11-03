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
const reports = resolve('build/reports/passkeys-browser');
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
  await call('Network.enable');
  const registrations = [];
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Network.requestWillBeSent' && message.params.request.url.includes('/api/v1/security/passkeys/register/')) registrations.push(message.params.request.url);
  });
  await call('WebAuthn.enable');
  const { authenticatorId } = await call('WebAuthn.addVirtualAuthenticator', { options: {
    protocol:'ctap2', transport:'internal', hasResidentKey:true, hasUserVerification:true,
    isUserVerified:true, automaticPresenceSimulation:true,
  }});
  const reportsData = [];
  const origins = [base, process.env.ATLAS_PASSKEY_STANDALONE_URL];
  for (const [index, origin] of origins.entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    await go('/system/security/passkeys');
    if (index === 0) {
      await call('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled:false});
      await clickText('Register passkey'); await until("document.querySelector('dialog[open] input[name=passkeyName]')");
      await fill('dialog[open] input[name=passkeyName]', 'Cancelled passkey');
      await click('dialog[open] button[type=submit]'); await until("document.querySelector('dialog[open] form[aria-busy=true]')");
      await click('dialog[open] .modal-footer [data-dialog-close]');
      await until("!document.querySelector('dialog[open]') && !document.querySelector('input[name=passkeyName]').disabled");
      assert.equal((await call('WebAuthn.getCredentials', {authenticatorId})).credentials.length, 0, 'closing registration aborts the ceremony');
      await call('WebAuthn.setAutomaticPresenceSimulation', {authenticatorId, enabled:true});
    }
    const name = index === 0 ? 'Embedded passkey' : 'Standalone passkey';
    await clickText('Register passkey'); await until("document.querySelector('dialog[open] input[name=passkeyName]')");
    assert.equal(await evaluate("document.querySelector('dialog[open] input[name=passkeyName]').value"), '');
    await fill('dialog[open] input[name=passkeyName]', ' '); await click('dialog[open] button[type=submit]');
    await until("document.querySelector('dialog[open] [role=alert]:not([hidden])')");
    await fill('dialog[open] input[name=passkeyName]', name); await click('dialog[open] button[type=submit]');
    await until(`!document.querySelector('dialog[open]') && [...document.querySelectorAll('[data-passkey-entry]')].some(row => row.textContent.includes(${JSON.stringify(name)}))`, 'verified WebAuthn registration');
    const stateKey = await evaluate("document.querySelector('script[data-bml-state-key^=\"system-passkeys.model\"]').dataset.bmlStateKey");
    const state = await evaluate("document.querySelector('script[data-bml-state-key^=\"system-passkeys.model\"]').textContent");
    const passkey = JSON.parse(state).passkeys.find(key => key.name === name);
    assert.ok(passkey.credentialId);
    assert.ok(passkey.createdAt);
    assert.equal(passkey.lastUsedAt, null);
    assert.ok(passkey.transports.includes('internal'));
    const replay = await fetch(base + '/_bml/action', {method:'POST', headers:{'Content-Type':'application/json',Authorization:`Bearer ${process.env.ATLAS_PASSKEY_READER_TOKEN}`}, body:JSON.stringify({page:'/system/security/passkeys',path:'/system/security/passkeys',stateKey,state,method:'delete',args:[passkey.credentialId,true]})});
    assert.equal(replay.status,200);
    assert.equal(JSON.parse((await replay.json()).state).feedback.failed,true,'another principal cannot remove this passkey');
    await go('/system/security/passkeys');
    assert.ok(await evaluate(`document.querySelector('[data-passkey-entry]')?.textContent.includes(${JSON.stringify(name)})`));
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      const bounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})');
      assert.ok(bounds.scrollWidth <= bounds.width + 2);
      let screenshot = await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${index}-list-${width}.png`),Buffer.from(screenshot.data,'base64'));
      reportsData.push({host:index,name:'passkeys',width,...bounds});
      await clickText('Register passkey'); await until("document.querySelector('dialog[open] input[name=passkeyName]')");
      const dialogBounds = await evaluate(`(() => { const dialog=document.querySelector('dialog[open]'), footer=dialog.querySelector('.modal-footer'),box=dialog.getBoundingClientRect(),f=footer.getBoundingClientRect(),last=footer.querySelector('button[type=submit]').getBoundingClientRect(),style=getComputedStyle(footer);return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:box.width,footerBottom:f.bottom,dialogBottom:box.bottom,rightInset:box.right-last.right,bottomInset:box.bottom-last.bottom,gap:parseFloat(style.gap)};})()`);
      assert.ok(dialogBounds.dialogWidth <= width && dialogBounds.scrollWidth <= width+2);
      assert.ok(dialogBounds.rightInset>=18 && dialogBounds.bottomInset>=12 && dialogBounds.gap>=8 && dialogBounds.footerBottom<=dialogBounds.dialogBottom+1,JSON.stringify(dialogBounds));
      screenshot = await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${index}-register-${width}.png`),Buffer.from(screenshot.data,'base64'));
      reportsData.push({host:index,name:'registration',width,...dialogBounds}); await escape();
    }
    await click('button[aria-label="Remove passkey"]'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    assert.ok(await evaluate("document.querySelector('dialog[open]').textContent.includes('all devices')"));
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until("location.pathname === '/login' && document.body.innerText.includes('Passkey removed. Sign in again to continue.')");
    assert.equal(await evaluate('boscaAuth.token'),null,'revoked browser state is cleared');
    await fill('input[name=identifier]','admin'); await fill('input[name=password]',password); await click('button[type=submit]');
    await until("location.pathname === '/system/security/passkeys' && document.readyState === 'complete'");
    assert.equal(await evaluate(`document.querySelector('[data-passkey-entry]')?.textContent.includes(${JSON.stringify(name)}) ?? false`),false,'passkey deletion persists');
    assert.ok(registrations.some(url=>url===base+'/api/v1/security/passkeys/register/begin'));
    assert.ok(registrations.some(url=>url===base+'/api/v1/security/passkeys/register/complete'));
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(reportsData,null,2));
  console.log('Passkey browser checks passed: native WebAuthn registration in both hosts, cancellation, account isolation, deletion/session reset and 16 responsive measurements.');
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit',resolve));
  await rm(profile,{recursive:true,force:true});
}
