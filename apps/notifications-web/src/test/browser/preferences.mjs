import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [base, api, artifacts] = process.argv.slice(2);
function contrast(foreground, background) {
  const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(value => {
    const channel = value / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  }).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
  const values = [luminance(foreground), luminance(background)].sort((a,b) => b-a);
  return (values[0] + 0.05) / (values[1] + 0.05);
}
for (const url of [base, api]) assert.ok(['localhost', '127.0.0.1'].includes(new URL(url).hostname));
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'notifications-design-browser-'));
const browser = spawn(process.env.BML_DESIGN_CHROME_BINARY ?? (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' });
let launchError;
browser.on('error', error => { launchError = error; });
let ws;
try {
  let port;
  for (let i = 0; i < 100 && !port; i++) {
    if (launchError) throw launchError;
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; } catch {}
    if (!port) await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(port, 'Isolated Chrome did not start');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  ws = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  let sequence = 0;
  const pending = new Map();
  const exceptions = [];
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id); clearTimeout(request.timer);
    if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  });
  function call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
      pending.set(id, { resolve, reject, timer }); ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async function evaluate(expression) {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  }
  async function until(expression) {
    for (let i = 0; i < 100; i++) {
      if (await evaluate(expression)) return;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error(`Timed out: ${expression}\n${await evaluate('document.body.outerHTML')}`);
  }
  const control = async path => assert.equal((await fetch(api + '/fixture/' + path)).status, 200);
  const toast = `document.querySelector('.toast-region')`;
  async function navigate(path) {
    await call('Page.navigate', { url: base + path });
    await until(`document.readyState === 'complete' && location.pathname === ${JSON.stringify(path.split('?')[0])}`);
  }
  await call('Page.enable'); await call('Runtime.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await navigate('/preferences?token=fixture-token');
  await until(`document.querySelector('[name="email-digest"]')?.closest('[data-bml-method]')?.dataset.bmlActionBound === 'true'`);
  assert.equal(await evaluate(`document.querySelectorAll('input[role="switch"]').length`), 2);
  assert.equal(await evaluate(`document.querySelector('[name="email-digest"]').checked`), true);
  assert.equal(await evaluate(`document.querySelector('[name="email-digest"]').getAttribute('aria-labelledby')`), 'email-channel preference-digest');
  assert.equal(await evaluate(`${toast}.matches(':popover-open')`), false);
  await evaluate(`document.querySelector('[name="email-digest"]').focus()`);
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
  await call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
  await until(`${toast}.dataset.sequence === '1' && ${toast}.matches(':popover-open')`);
  assert.equal(await evaluate(`document.querySelector('[name="email-digest"]').checked`), false);
  assert.deepEqual(await (await fetch(api + '/fixture/state')).json(), { email: true, push: false });
  await evaluate(`${toast}.querySelector('button').click()`);
  await until(`!${toast}.matches(':popover-open')`);
  await control('reject');
  await evaluate(`document.querySelector('[name="push-digest"]').click()`);
  await until(`${toast}.dataset.sequence === '2' && ${toast}.dataset.tone === 'error' && ${toast}.matches(':popover-open')`);
  assert.equal(await evaluate(`document.querySelector('[name="push-digest"]').checked`), true, 'Rejected checkbox returns to saved state');
  await evaluate(`document.querySelector('[name="push-digest"]').click()`);
  await until(`${toast}.dataset.sequence === '3' && ${toast}.dataset.tone === 'success'`);
  assert.deepEqual(await (await fetch(api + '/fixture/state')).json(), { email: true, push: true });
  await control('reject'); await control('hold');
  const before = await evaluate(`(() => { const r=document.querySelector('form button').getBoundingClientRect(); return [r.width,r.height]; })()`);
  await evaluate(`document.querySelector('form button').click()`);
  await until(`document.querySelector('form').hasAttribute('data-bml-action-pending')`);
  assert.deepEqual(await evaluate(`(() => { const r=document.querySelector('form button').getBoundingClientRect(); return [r.width,r.height]; })()`), before);
  await control('release');
  await until(`${toast}.dataset.sequence === '4' && ${toast}.dataset.tone === 'error'`);
  await evaluate(`${toast}.querySelector('button').click()`);

  const measurements = [];
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    const geometry = await evaluate(`(() => {
      const rect = el => { const r=el.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right}; };
      return { width:innerWidth,document:document.documentElement.scrollWidth,
        card:rect(document.querySelector('.card')),button:rect(document.querySelector('form button')),
        labels:[...document.querySelectorAll('.switch-cell label')].map(rect),
        text:getComputedStyle(document.querySelector('.row-name')).color,
        surface:getComputedStyle(document.querySelector('.card')).backgroundColor,
        accent:getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() };
    })()`);
    assert.ok(geometry.document <= width, JSON.stringify(geometry));
    assert.ok(geometry.button.height >= 42 && geometry.button.right <= width - 20, JSON.stringify(geometry));
    assert.ok(geometry.labels.every(r => r.height >= 44 && r.right <= width - 20), JSON.stringify(geometry));
    assert.equal(geometry.surface, 'rgb(255, 255, 255)');
    assert.equal(geometry.accent, '#047a52');
    assert.ok(contrast(geometry.text, geometry.surface) >= 4.5, JSON.stringify(geometry));
    measurements.push(geometry);
    const capture = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    await writeFile(join(artifacts, `preferences-${width}.png`), Buffer.from(capture.data, 'base64'));
  }
  await evaluate(`document.documentElement.style.setProperty('--accent','#3159a7')`);
  await evaluate(`document.querySelector('[name="push-digest"]').click()`);
  await until(`${toast}.dataset.sequence === '5'`);
  await new Promise(resolve => setTimeout(resolve, 200));
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('[name="push-digest"] + .switch-track')).backgroundColor`), 'rgb(49, 89, 167)');
  await navigate('/unsubscribe?token=fixture-token');
  await until(`document.querySelector('form')?.dataset.bmlActionBound === 'true'`);
  assert.equal(await evaluate(`${toast}.matches(':popover-open')`), false);
  const primary = await evaluate(`(() => { const s=getComputedStyle(document.querySelector('form button')); return [s.color,s.backgroundColor]; })()`);
  assert.ok(contrast(...primary) >= 4.5, `Primary action contrast: ${primary}`);
  const confirmation = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile(join(artifacts, 'unsubscribe-390.png'), Buffer.from(confirmation.data, 'base64'));
  for (const sequence of [1, 2]) {
    await control('reject'); await evaluate(`document.querySelector('form button').click()`);
    await until(`${toast}.dataset.sequence === '${sequence}' && ${toast}.matches(':popover-open')`);
    await evaluate(`${toast}.querySelector('button').click()`); await until(`!${toast}.matches(':popover-open')`);
  }
  await evaluate(`document.querySelector('a[href^="/preferences"]').click()`);
  await until(`location.pathname === '/preferences' && document.querySelector('[name="email-digest"]')?.closest('[data-bml-method]')?.dataset.bmlActionBound === 'true'`);
  assert.equal(await evaluate(`${toast}.matches(':popover-open')`), false, 'Navigation does not replay an outcome');
  await evaluate('history.back()');
  await until(`location.pathname === '/unsubscribe' && document.querySelector('form')?.dataset.bmlActionBound === 'true'`);
  assert.equal(await evaluate(`${toast}.matches(':popover-open')`), false, 'Back does not replay a dismissed toast');
  await evaluate(`document.querySelector('form button').click()`);
  await until(`document.querySelector('.status.ok')?.textContent.includes("You're unsubscribed")`);
  await navigate('/preferences?token=invalid');
  assert.ok(await evaluate(`document.querySelector('.status.error')?.textContent.includes('invalid')`));
  assert.equal(await evaluate(`document.querySelectorAll('[role="switch"]').length`), 0);
  assert.deepEqual(exceptions, []);
  await writeFile(join(artifacts, 'measurements.json'), JSON.stringify(measurements, null, 2));
  console.log('Notifications native switches, rejected/successful actions, forms, repeated toasts, navigation, CSS and responsive layout passed.');
} finally {
  ws?.close();
  if (browser.exitCode === null) {
    browser.kill('SIGTERM');
    await Promise.race([new Promise(resolve => browser.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3000))]);
    if (browser.exitCode === null) { browser.kill('SIGKILL'); await new Promise(resolve => browser.once('exit', resolve)); }
  }
  await rm(profile, { recursive: true, force: true });
}
