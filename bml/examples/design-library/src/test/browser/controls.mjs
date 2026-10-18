import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const base = process.argv[2];
const mode = process.argv[3] ?? 'fixture';
if (!base || !['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname)) {
  throw new Error('Pass the isolated design-library fixture URL on localhost.');
}
const artifacts = resolve('build/reports/browser');
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bosca-design-browser-'));
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
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
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
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
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
    throw new Error(`Timed out: ${expression}`);
  }
  await call('Page.enable');
  await call('Runtime.enable');
  await call('Network.enable');
  await call('Page.bringToFront');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: base + '/' });
  await until('document.readyState === "complete" && document.querySelector("[data-save=light]")?.dataset.bmlActionBound === "true" || document.readyState === "complete" && document.querySelector("[data-form=light]")?.dataset.bmlActionBound === "true"');
  const controls = await evaluate(`(() => {
    const get = name => document.querySelector('[data-' + name + '=light]');
    const input = get('input');
    return {
      regular: get('save').getBoundingClientRect().height,
      compact: get('compact').getBoundingClientRect().height,
      small: get('small').getBoundingClientRect().height,
      input: input.getBoundingClientRect().height,
      labeled: input.labels.length === 1 && input.labels[0].textContent.includes('Display name'),
      required: input.required, disabled: get('disabled').disabled,
      native: !document.querySelector('#native-button').hasAttribute('data-bml-c'),
      light: getComputedStyle(document.querySelector('[data-palette=light] .ui-card')).backgroundColor,
      dark: getComputedStyle(document.querySelector('[data-palette=purple] .ui-card')).backgroundColor,
      scheme: getComputedStyle(input).colorScheme,
      inputClass: input.classList.contains('input') && input.classList.contains('display-name'),
      shell: document.querySelector('main').className.trim(),
      shellClasses: document.querySelector('main').outerHTML.match(/<main[^>]+>/)[0].match(/class=/g).length,
      loading: get('loading').disabled && get('loading').getAttribute('aria-busy') === 'true',
    };
  })()`);
  assert.deepEqual(controls, { regular: 32, compact: 30, small: 24, input: 32, labeled: true,
    required: true, disabled: true, native: true, light: 'rgb(255, 255, 255)', dark: 'rgb(16, 19, 28)', scheme: 'light',
    inputClass: true, shell: 'page fixture-shell', shellClasses: 1, loading: true });
  await evaluate('document.querySelector("[data-input=light]").focus()');
  await until('document.activeElement === document.querySelector("[data-input=light]")');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-input=light]")).borderTopColor'), 'rgb(21, 128, 61)');
  await evaluate('document.querySelector("[data-increment=light]").click()');
  await until('document.querySelector("[data-increment=light]").textContent.includes("Count 1")');
  assert.match(await evaluate('document.querySelector("[data-increment=purple]").textContent'), /Count 0/);
  await evaluate('document.querySelector("[data-disabled=light]").click()');
  assert.match(await evaluate('document.querySelector("[data-increment=light]").textContent'), /Count 1/);
  const before = await evaluate(`(() => { const r=document.querySelector('[data-save=light]').getBoundingClientRect(); return [r.width,r.height] })()`);
  // Hold only this fixture's next action to inspect pending geometry, then send it to the real server.
  await evaluate(`(() => {
    const original = window.fetch.bind(window);
    window.fetch = async (...args) => {
      if (String(args[0]).includes('/_bml/action')) {
        window.fetch = original;
        await new Promise(resolve => { window.releaseDesignAction = resolve; });
      }
      return original(...args);
    };
    document.querySelector('[data-input=light]').value = 'Grace';
    document.querySelector('[data-save=light]').click();
  })()`);
  await until('document.querySelector("[data-form=light]").hasAttribute("data-bml-action-pending") && typeof window.releaseDesignAction === "function"');
  const busy = await evaluate(`(() => {
    const button = document.querySelector('[data-save=light]'); const r=button.getBoundingClientRect();
    return { size: [r.width,r.height], text: button.textContent.trim(),
      content: getComputedStyle(button.querySelector('.button-content')).opacity,
      progress: getComputedStyle(button.querySelector('.button-progress')).display,
      spinner: button.querySelector('.spinner').getBoundingClientRect().width,
      color: getComputedStyle(button).getPropertyValue('--accent').trim() };
  })()`);
  assert.deepEqual(busy, { size: before, text: 'Save', content: '0', progress: 'flex', spinner: 14, color: '#15803d' });
  const handle = await call('Runtime.evaluate', { expression: 'document.querySelector("[data-save=light]")' });
  const accessible = await call('Accessibility.getPartialAXTree', { objectId: handle.result.objectId, fetchRelatives: false });
  assert.equal(accessible.nodes.find(node => node.role?.value === 'button').name.value, 'Save');
  await evaluate('window.releaseDesignAction()');
  await until('document.querySelector("[data-result=light]").textContent.includes("Saved Grace (1)")');
  assert.match(await evaluate('document.querySelector("[data-result=purple]").textContent'), /Saved Ada \(0\)/);
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-input=light]")).backgroundColor'), 'rgb(248, 250, 252)');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-input=purple]")).getPropertyValue("--accent").trim()'), '#c084fc');
  await evaluate('document.querySelector("[data-palette=light]").classList.add("alternate"); document.querySelector("[data-input=light]").focus()');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-input=light]")).borderTopColor'), 'rgb(37, 99, 235)');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-input=purple]")).getPropertyValue("--accent").trim()'), '#c084fc');
  await evaluate('document.querySelector("[data-palette=light]").classList.remove("alternate")');
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'), `Page overflow at ${width}px`);
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `design-${mode}-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
  await call('Page.navigate', { url: base + '/aliases' });
  await until('document.readyState === "complete" && document.querySelector("[data-alias=qualified]")');
  assert.ok(await evaluate(`document.querySelector('[data-alias=plain]').getAttribute('data-bml-c') === document.querySelector('[data-alias=qualified]').getAttribute('data-bml-c')`));
  assert.ok(await evaluate(`(() => { const links=Array.from(document.querySelectorAll('link[rel=stylesheet]'), e=>e.href); return links.length === new Set(links).size })()`));
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  await call('Page.navigate', { url: base + '/' });
  await until('document.readyState === "complete" && document.querySelector("[data-input=light]")');
  assert.ok(await evaluate('document.querySelector("[data-input=light]") instanceof HTMLInputElement && document.querySelector("[data-input=light]").required && document.querySelector("[data-save=light]") instanceof HTMLButtonElement'));
  assert.deepEqual(exceptions, []);
  console.log(`Design library browser checks passed (${mode}); screenshots: ${artifacts}`);
} finally {
  ws?.close();
  browser.kill('SIGTERM');
  await new Promise(resolve => {
    if (browser.exitCode !== null) resolve();
    else { browser.once('exit', resolve); setTimeout(resolve, 3000).unref(); }
  });
  await rm(profile, { recursive: true, force: true });
}
