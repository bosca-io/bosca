import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { checkLayout } from './layout-checks.mjs';
import { checkIconActions } from './icon-action-checks.mjs';
import { checkOverlays } from './overlay-checks.mjs';
import { checkClipboard } from './clipboard-checks.mjs';
import { checkToasts } from './toast-checks.mjs';
import { checkSelection } from './selection-checks.mjs';
import { checkTypedChoices } from './typed-choice-checks.mjs';
import { checkNavigation } from './navigation-checks.mjs';
import { checkSelects } from './select-checks.mjs';
import { checkComposition } from './composition-checks.mjs';
import { checkSource } from './source-checks.mjs';
import { checkJson } from './json-checks.mjs';
import { checkEmptyWidgets } from './empty-checks.mjs';
import { checkThemes } from './theme-checks.mjs';
import { checkWidgets } from './widget-checks.mjs';
import { checkWorkspaces } from './workspace-checks.mjs';
import { checkShell } from './shell-checks.mjs';

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
      const timeout = setTimeout(() => {
        const context={browserPid:browser.pid,browserExit:browser.exitCode,browserSignal:browser.signalCode,
          socketState:ws.readyState,pending:[...pending.entries()].map(([id,item])=>({id,method:item.method})),
          responses,consoleErrors,exceptions};
        pending.delete(id);
        reject(new Error(`CDP timeout: ${method}${params.expression ? `: ${params.expression}` : ""}\n${JSON.stringify(context)}`));
      }, 10000);
      pending.set(id, { resolve, reject, timeout, method });
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
      const body = await call('Network.getResponseBody', { requestId: response.id });
      failures.push({ ...response, body: body.body.slice(0, 2000) });
    }
    throw new Error(`Timed out: ${expression}\n${JSON.stringify({ context, responses, failures, consoleErrors })}`);
  }
  await call('Page.enable');
  await call('Runtime.enable');
  await call('Network.enable');
  await call('Page.bringToFront');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  if (process.argv[4] === 'shell') {
    await checkShell({ call, evaluate, until, base, mode, artifacts });
    console.log(`Shell ${mode} passed`);
    assert.deepEqual(exceptions, []);
    assert.deepEqual(consoleErrors, []);
  } else if (process.argv[4] === 'typed-choices') {
    await checkTypedChoices({ call, evaluate, until, base });
    assert.deepEqual(exceptions, []);
    assert.deepEqual(consoleErrors, []);
  } else if (process.argv[4] === 'structured') {
    await checkJson({ call, evaluate, until, base, mode, artifacts });
    await checkWidgets({ call, evaluate, until, base, mode, artifacts });
    assert.deepEqual(exceptions, []);
    assert.deepEqual(consoleErrors, []);
    console.log(`Structured input consumer checks passed (${mode}); artifacts: ${artifacts}`);
  } else if (process.argv[4] === 'workspace') {
    await checkWorkspaces({ call, evaluate, until, base, mode, artifacts });
    assert.deepEqual(exceptions, []);
    assert.deepEqual(consoleErrors, []);
    console.log(`Independent workspace checks passed (${mode}); artifacts: ${artifacts}`);
  } else {
  await call('Page.navigate', { url: base + '/' });
  await until('document.readyState === "complete" && document.querySelector("[data-save=light]")?.dataset.bmlActionBound === "true" || document.readyState === "complete" && document.querySelector("[data-form=light]")?.dataset.bmlActionBound === "true"');
  await evaluate(`Promise.all(['Geist', 'Geist Mono'].map(family => document.fonts.load('400 14px "' + family + '"', 'Bosca Ā Ж Ԁ Việt ┌')))`);
  const fonts = await evaluate(`Array.from(document.fonts, face => [face.family, face.status]).sort()`);
  assert.deepEqual(fonts, [...Array.from({ length: 5 }, () => ['Geist', 'loaded']),
    ...Array.from({ length: 6 }, () => ['Geist Mono', 'loaded'])].sort());
  const fontOverride = await evaluate(`(() => {
    const style = document.createElement('style');
    style.textContent = '[data-palette=light] { --font-sans: serif; --font-mono: monospace; font-family: var(--font-sans); }';
    document.head.append(style);
    const family = name => getComputedStyle(document.querySelector('[data-' + name + '=light]')).fontFamily;
    const result = [family('save'), family('input'),
      getComputedStyle(document.querySelector('[data-palette=light]')).getPropertyValue('--font-mono').trim()];
    style.remove();
    return result;
  })()`);
  assert.deepEqual(fontOverride, ['serif', 'serif', 'monospace']);
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
      spinner: Number.parseFloat(getComputedStyle(button.querySelector('.spinner')).width),
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
  assert.equal(await evaluate('document.fonts.size'), 11, 'aliases must not duplicate font faces');
  await call('Page.navigate', { url: base + '/foundation' });
  await until('document.readyState === "complete" && document.querySelector("[data-foundation-form=light]")?.dataset.bmlActionBound === "true"');
  const foundation = await evaluate(`(() => {
    const check = document.querySelector('[data-checkbox=light]');
    const toggle = document.querySelector('[data-switch=light]');
    const status = document.querySelector('[data-status=light]');
    return {
      native: check instanceof HTMLInputElement && check.type === 'checkbox' && toggle instanceof HTMLInputElement && toggle.type === 'checkbox',
      labels: check.labels.length === 1 && check.labels[0].textContent.includes('Notifications') && toggle.getAttribute('aria-label') === 'Enable previews',
      required: check.required, unchecked: !check.checked && !toggle.checked,
      target: check.className === 'subscription' && !check.labels[0].classList.contains('subscription') && !check.hasAttribute('description') && !check.hasAttribute('label'),
      disabled: document.querySelector('[data-disabled-checkbox=light]').disabled && document.querySelector('[data-disabled-switch=light]').disabled,
      checkboxSize: [check.getBoundingClientRect().width, check.getBoundingClientRect().height],
      switchSize: [toggle.getBoundingClientRect().width, toggle.getBoundingClientRect().height],
      role: toggle.getAttribute('role'), feedbackHidden: document.querySelector('[data-feedback=light]').hidden,
      status: status.getAttribute('role') === 'status' && status.textContent.includes('Loading records') && status.classList.contains('fixture-status'),
      empty: document.querySelectorAll('.empty-actions button').length === 2,
      success: getComputedStyle(document.querySelector('[data-badge=light]')).color,
      purple: getComputedStyle(document.querySelector('[data-purple-badge=light]')).color,
      darkPurple: getComputedStyle(document.querySelector('[data-purple-badge=purple]')).color,
    };
  })()`);
  assert.deepEqual(foundation, { native: true, labels: true, required: true, unchecked: true, target: true,
    disabled: true, checkboxSize: [14,14], switchSize: [32,18], role: 'switch', feedbackHidden: true,
    status: true, empty: true, success: 'rgb(21, 128, 61)', purple: 'rgb(124, 58, 237)', darkPurple: 'rgb(167, 139, 255)' });
  await evaluate('document.querySelector("[data-checkbox=light]").labels[0].click()');
  await until('document.querySelector("[data-checkbox=light]").checked && document.querySelector("[data-checkbox=light]").hasAttribute("checked")');
  assert.equal(await evaluate('document.querySelector("[data-checkbox=purple]").checked'), false);
  await evaluate('document.querySelector("[data-switch=light]").click()');
  await until('document.querySelector("[data-switch=light]").checked && document.querySelector("[data-switch=light]").hasAttribute("checked")');
  await until('getComputedStyle(document.querySelector("[data-switch=light]").nextElementSibling).backgroundColor === "rgb(21, 128, 61)"');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-switch=light]").nextElementSibling).backgroundColor'), 'rgb(21, 128, 61)');
  assert.equal(await evaluate('document.querySelector("[data-switch=purple]").checked'), false);
  assert.deepEqual(await evaluate(`(() => { const data = new FormData(document.querySelector('[data-foundation-form=light]')); return [data.get('subscription'), data.get('previews')] })()`), ['yes','enabled']);
  await evaluate('document.querySelector("[data-foundation-form=light]").requestSubmit()');
  await until('document.querySelector("[data-feedback=light]").textContent.includes("Saved yes (1)")');
  assert.equal(await evaluate('document.querySelector("[data-feedback=light]").hidden'), false);
  assert.equal(await evaluate('document.querySelector("[data-feedback=purple]").hidden'), true);
  assert.ok(await evaluate('document.querySelector("[data-checkbox=light]").checked && document.querySelector("[data-switch=light]").checked'));
  const switchHandle = await call('Runtime.evaluate', { expression: 'document.querySelector("[data-switch=light]")' });
  const switchAX = await call('Accessibility.getPartialAXTree', { objectId: switchHandle.result.objectId, fetchRelatives: false });
  assert.equal(switchAX.nodes.find(node => node.role?.value === 'switch').name.value, 'Enable previews');
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    const table = await evaluate(`(() => {
      const wrapper = document.querySelector('[data-table=light]'); const cell = wrapper.querySelector('th');
      return { focusable: wrapper.tabIndex === 0, role: wrapper.getAttribute('role'), name: wrapper.getAttribute('aria-label'),
        overflow: wrapper.scrollWidth > wrapper.clientWidth, local: wrapper.classList.contains('fixture-table'),
        font: getComputedStyle(cell).fontSize, padding: getComputedStyle(cell).padding, pageFits: document.documentElement.scrollWidth <= innerWidth };
    })()`);
    assert.ok(table.focusable && table.local && table.pageFits, JSON.stringify(table));
    assert.equal(table.role, 'region'); assert.equal(table.name, 'Records');
    assert.equal(table.font, '11px'); assert.equal(table.padding, '12px 14px');
    if (width === 390) {
      assert.equal(table.overflow, true);
      await evaluate(`(() => {const table=document.querySelector('[data-table=light]');
        window.fixtureKeyboardScrollEnded=false;
        table.addEventListener('scrollend',()=>window.fixtureKeyboardScrollEnded=true,{once:true});
        table.focus()})()`);
      await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 });
      await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 });
      await until('document.querySelector("[data-table=light]").scrollLeft > 0');
      await until('window.fixtureKeyboardScrollEnded === true');
      assert.equal(await evaluate('getComputedStyle(document.querySelector("[data-table=light]")).outlineColor'), 'rgb(21, 128, 61)');
      await evaluate(`document.querySelector('[data-table=light]').scrollTo({left:80,top:90,behavior:'instant'})`);
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      await evaluate(`window.fixtureRetainedTable=document.querySelector('[data-table=light]');window.fixtureTableScroll=[window.fixtureRetainedTable.scrollLeft,window.fixtureRetainedTable.scrollTop];document.querySelector('[data-foundation-form=light]').requestSubmit()`);
      await until(`document.querySelector('[data-feedback=light]').textContent.includes('Saved yes (2)')`);
      const retained=await evaluate(`({root:window.fixtureRetainedTable===document.querySelector('[data-table=light]'),
        focused:document.activeElement===window.fixtureRetainedTable,expected:window.fixtureTableScroll,
        scroll:[window.fixtureRetainedTable.scrollLeft,window.fixtureRetainedTable.scrollTop],documentFocus:document.hasFocus(),
        active:document.activeElement?.outerHTML.slice(0,400),size:[window.fixtureRetainedTable.clientWidth,window.fixtureRetainedTable.clientHeight,
          window.fixtureRetainedTable.scrollWidth,window.fixtureRetainedTable.scrollHeight]})`);
      await writeFile(join(artifacts,`table-retention-${mode}.json`),JSON.stringify(retained,null,2));
      assert.ok(retained.root&&retained.focused&&retained.expected[0]>0&&retained.expected[1]>0&&
        retained.scroll[0]===retained.expected[0]&&retained.scroll[1]===retained.expected[1],
        `A retained native table keeps focus and both scroll positions through a real surrounding save: ${JSON.stringify(retained)}`);
    }
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `foundation-${mode}-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
  await evaluate('document.querySelector("[data-checkbox=light]").click()');
  await until('!document.querySelector("[data-checkbox=light]").checked && !document.querySelector("[data-checkbox=light]").hasAttribute("checked")');
  await evaluate('document.querySelector("[data-switch=light]").click()');
  await until('!document.querySelector("[data-switch=light]").checked && !document.querySelector("[data-switch=light]").hasAttribute("checked")');
  await checkLayout({ call, evaluate, until, base, mode, artifacts });
  await checkIconActions({ call, evaluate, until, base, mode, artifacts });
  await checkOverlays({ call, evaluate, until, base, mode, artifacts });
  await checkClipboard({ call, evaluate, until, base, mode, artifacts });
  await checkToasts({ call, evaluate, until, base, mode, artifacts });
  await checkSelection({ call, evaluate, until, base, mode, artifacts });
  await checkNavigation({ call, evaluate, until, base, mode, artifacts });
  await checkSelects({ call, evaluate, until, base, mode, artifacts });
  await checkComposition({ call, evaluate, until, base, mode, artifacts });
  await checkSource({ call, evaluate, until, base, mode, artifacts });
  await checkJson({ call, evaluate, until, base, mode, artifacts });
  await checkThemes({ call, evaluate, until, base });
  await checkEmptyWidgets({ call, evaluate, until, base, mode, artifacts });
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  await call('Page.navigate', { url: base + '/' });
  await until('document.readyState === "complete" && document.querySelector("[data-input=light]")');
  assert.ok(await evaluate('document.querySelector("[data-input=light]") instanceof HTMLInputElement && document.querySelector("[data-input=light]").required && document.querySelector("[data-save=light]") instanceof HTMLButtonElement'));
  await call('Page.navigate', { url: base + '/foundation' });
  await until('document.readyState === "complete" && document.querySelector("[data-checkbox=light]")');
  await evaluate('document.querySelector("[data-checkbox=light]").click(); document.querySelector("[data-switch=light]").click()');
  assert.ok(await evaluate('document.querySelector("[data-checkbox=light]").checked && document.querySelector("[data-switch=light]").checked'));
  await call('Page.navigate', { url: base + '/layout' });
  await until('document.readyState === "complete" && document.querySelector("[data-disclosure]")');
  await evaluate('document.querySelector("[data-disclosure]").click()');
  assert.equal(await evaluate('document.querySelector("#layout-details").open'), true);
  await call('Page.navigate', { url: base + '/actions' });
  await until('document.readyState === "complete" && document.querySelector("[data-icon-action=light]")');
  assert.ok(await evaluate(`(() => { const button=document.querySelector('[data-icon-action=light]');
    const link=document.querySelector('[data-icon-link=light]');
    return button instanceof HTMLButtonElement && !!button.title && button.getAttribute('aria-label') === button.title &&
      document.querySelector('[data-disabled-action=light]').disabled && link instanceof HTMLAnchorElement && link.title === 'Read details' })()`));
  await evaluate('document.querySelector("[data-icon-link=light]").click()');
  assert.equal(await evaluate('location.hash'), '#link-target');
  await call('Page.navigate', { url: base + '/overlays' });
  await until('document.readyState === "complete" && document.querySelector("[data-menu=light]")');
  await evaluate('document.querySelector("[data-menu=light] > summary").click()');
  assert.ok(await evaluate(`(() => { const panel=document.querySelector('[data-menu=light]'); const content=panel.querySelector('[data-bml-ref=content]');
    return panel instanceof HTMLDetailsElement && panel.open && !content.hasAttribute('popover') && content.getBoundingClientRect().height>0 })()`));
  await call('Page.navigate', { url: base + '/clipboard' });
  await until('document.readyState === "complete" && document.querySelector("[data-copy-field=light] input")');
  assert.ok(await evaluate(`(() => { const input=document.querySelector('[data-copy-field=light] input'); input.focus(); input.select();
    return input.readOnly && input.selectionStart===0 && input.selectionEnd===input.value.length && input.getAttribute('aria-label')==='Token' &&
      (() => { const button=document.querySelector('[data-copy-standalone=light] button'); return !!button.title && button.title===button.getAttribute('aria-label') })() })()`), 'Native copy labels and manual selection survive scripts-disabled navigation');
  await call('Page.navigate', { url: base + '/selection' });
  await until('document.readyState === "complete" && document.querySelector("[data-search-query=light]")');
  assert.ok(await evaluate(`(() => { const query=document.querySelector('[data-search-query=light]');
    query.value='Native query';const form=new FormData(document.querySelector('[data-selection-form=light]'));
    return query instanceof HTMLInputElement&&query.type==='search'&&!query.autofocus&&form.get('q')==='Native query'&&form.get('mode')==='paths'&&
      document.querySelector('[data-views=light] [aria-selected=true]').dataset.tab==='Source'&&document.querySelector('[data-views=light] [aria-selected=true]').textContent.trim()==='Updated source'; })()`), 'No-script search and initial segment form values remain native');
  await call('Page.navigate', { url: base + '/navigation' });
  await until('document.readyState === "complete" && document.querySelector("[data-routes=light]")');
  assert.ok(await evaluate(`(() => { const nav=document.querySelector('[data-routes=light]'),row=nav.querySelector('.tab-row');
    const links=[...row.querySelectorAll('a')];return links.length===8&&links.every(link=>link instanceof HTMLAnchorElement&&!link.hidden)&&
      nav.querySelector('[data-bml-ref=more]').hidden&&row.scrollWidth>row.clientWidth&&getComputedStyle(row).overflowX==='auto';})()`), 'No-script route links remain reachable through native scrolling');
  await evaluate(`document.querySelector('[data-routes=light] .tab-row a').click()`);
  await until('document.readyState === "complete" && location.pathname === "/navigation-target" && document.querySelector("[data-destination]")');
  assert.equal(await evaluate('document.querySelector("[data-destination]").textContent.trim()'),'Destination 1');
  await call('Page.navigate', { url: base + '/selects' });
  await until('document.readyState === "complete" && document.querySelector("[data-select-single=light]")');
  assert.ok(await evaluate(`(() => {const n=document.querySelector('[data-select-single=light]'),m=document.querySelector('[data-select-multiple=light]');
    const form=new FormData(document.querySelector('[data-select-form=light]'));
    return n instanceof HTMLSelectElement&&n.required&&n.value==='a'&&!n.classList.contains('enhanced')&&getComputedStyle(n).height==='32px'&&
      m.multiple&&JSON.stringify(form.getAll('toppings'))==='["a","b"]'&&n.closest('[data-bml-design-select]').querySelector('[data-bml-ref=control]').hidden;})()`), 'No-script native single/multiple selects retain their names, form values and presentation');
  await call('Page.navigate', { url: base + '/composition' });
  await until('document.readyState === "complete" && document.querySelector("[data-comment=light]")');
  assert.deepEqual(await evaluate(`(() => {const field=document.querySelector('[data-comment=light]');
    return {native:field instanceof HTMLTextAreaElement&&field.required,value:new FormData(field.form).get('body'),
      links:[...document.querySelectorAll('[data-local-tree=light] a')].every(link=>link.getBoundingClientRect().height>0&&link.tabIndex===0),
      timestamp:document.querySelector('[data-default-time]').textContent};})()`),
    {native:true,value:'Server draft 3',links:true,timestamp:'Oct 18, 13:15 UTC'},'No-script native comments, every file link and server timestamps remain available');
  await call('Page.navigate', { url: base + '/source' });
  await until('document.readyState === "complete" && document.querySelector("[data-source=light] pre")');
  assert.ok(await evaluate(`(() => {const root=document.querySelector('[data-source=light]'),fallback=root.querySelector('pre');
    return fallback.tabIndex===0&&fallback.textContent.includes('replacement = 1')&&!document.querySelector('.cm-editor')&&
      document.querySelector('[data-source-long] pre').textContent.includes('<script>unsafe()</script>')&&
      !window.unsafe&&getComputedStyle(document.querySelector('[data-source-bare] pre')).whiteSpace==='pre-wrap';})()`),
    'Script-disabled source remains readable, escaped and keyboard-scrollable');
  await evaluate(`document.querySelector('[data-source-long] pre').scrollIntoView({block:'center'});document.querySelector('[data-source-long] pre').focus()`);
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'PageDown', code: 'PageDown', windowsVirtualKeyCode: 34 });
  await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'PageDown', code: 'PageDown', windowsVirtualKeyCode: 34 });
  await until(`document.querySelector('[data-source-long] pre').scrollTop>0`);
  await call('Page.navigate', { url: base + '/json' });
  await until('document.readyState === "complete" && document.querySelector("[data-json=light] textarea")');
  assert.ok(await evaluate(`(() => {const field=document.querySelector('[data-json=light] textarea');
    return !field.hidden&&field.value==='{"replacement":2}'&&new FormData(field.form).get('settings')===field.value&&
      document.querySelector('[data-json-invalid] textarea').value==='{invalid: '&&!document.querySelector('.jse-main');})()`),
    'No-script fallback keeps exact drafts and native form values');
  await call('Emulation.setScriptExecutionDisabled', { value: false });
  await checkWidgets({ call, evaluate, until, base, mode, artifacts });
  assert.deepEqual(exceptions, []);
  console.log(`Design library browser checks passed (${mode}); screenshots: ${artifacts}`);
  }
} finally {
  ws?.close();
  browser.kill('SIGTERM');
  await new Promise(resolve => {
    if (browser.exitCode !== null) resolve();
    else { browser.once('exit', resolve); setTimeout(resolve, 3000).unref(); }
  });
  await rm(profile, { recursive: true, force: true });
}
