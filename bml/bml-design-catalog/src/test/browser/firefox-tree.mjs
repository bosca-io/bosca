import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

// Firefox's supported BiDi endpoint accepts ordinary installed builds, without a driver download.
const base = process.argv[2], mode = process.argv[3];
assert.ok(base && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname),
  'Pass the isolated catalog fixture URL on localhost.');
assert.ok(['production', 'development'].includes(mode));
const artifacts = resolve('build/reports/browser');
await mkdir(artifacts, { recursive: true });
const axeSource = await readFile(fileURLToPath(new URL('../../../node_modules/axe-core/axe.min.js', import.meta.url)), 'utf8');
const report = { mode, cases: [], scans: [], destinations: [], errors: [], network: [], protocol: [], scope: 'Firefox DOM, trusted keyboard input, layout and axe; no native AX snapshot or spoken AT claim.' };
const root = id => `document.querySelector('[data-deep-tree=${id}]')`;
const row = (id, index) => `${root(id)}.querySelectorAll('[role=treeitem]')[${index}]`;
const control = (id, index) => `${row(id, index)}.querySelector('.ui-tree-row')`;
const keyValues = { Enter: '\uE007', Home: '\uE011', End: '\uE010', ArrowLeft: '\uE012', ArrowUp: '\uE013', ArrowRight: '\uE014', ArrowDown: '\uE015' };

async function qualify(scripts) {
  const profile = await mkdtemp(join(tmpdir(), 'bosca-catalog-firefox-'));
  // The preference is read on startup, from this owned profile only.
  if (!scripts) await writeFile(join(profile, 'user.js'), 'user_pref("javascript.enabled", false);\n');
  const browser = spawn(process.env.BML_DESIGN_FIREFOX_BINARY ?? (process.platform === 'darwin'
    ? '/Applications/Firefox.app/Contents/MacOS/firefox' : 'firefox'),
  ['--headless', '--no-remote', '--new-instance', '--profile', profile, '--remote-debugging-port', '0', 'about:blank'],
  { stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '', launchError, ws, sequence = 0, context, failed = false, cleanupError;
  const pending = new Map();
  browser.stdout.on('data', data => { log += data; });
  browser.stderr.on('data', data => { log += data; });
  browser.on('error', error => { launchError = error; });
  function call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      report.protocol.push({ id, method, scripts, at: Date.now(), expression: params.expression?.slice(0, 300) });
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`BiDi timeout: ${method}`)); }, 10000);
      pending.set(id, { resolve, reject, timer });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }
  const raw = expression => call('script.evaluate', { expression, target: { context }, awaitPromise: true });
  async function evaluate(expression) {
    // A disabled page can be inspected synchronously; its Promise callbacks do not run.
    const result = await raw(scripts ? `(async()=>JSON.stringify(await (${expression})))()` : `JSON.stringify((${expression}))`);
    assert.equal(result.type, 'success', JSON.stringify(result));
    return result.result.type === 'undefined' ? undefined : JSON.parse(result.result.value);
  }
  async function until(expression) {
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await evaluate(expression)) return;
      await delay(50);
    }
    throw new Error(`Timed out: ${expression}`);
  }
  const key = value => call('input.performActions', { context, actions: [{ type: 'key', id: 'tree-keyboard',
    actions: [{ type: 'keyDown', value: keyValues[value] ?? value }, { type: 'keyUp', value: keyValues[value] ?? value }] }] });
  const focused = async (id, index) => assert.equal(await evaluate(`document.activeElement===${control(id, index)}`), true, `${id}: focus at row ${index}`);
  async function navigate(path) {
    await call('browsingContext.navigate', { context, url: base + path, wait: 'complete' });
    await until(`location.pathname===${JSON.stringify(path)}&&document.readyState==='complete'&&!!document.querySelector('main')`);
  }
  try {
    let endpoint;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (launchError) throw launchError;
      assert.equal(browser.exitCode, null, log);
      endpoint = log.match(/WebDriver BiDi listening on (ws:\/\/[^\s]+)/)?.[1];
      if (endpoint) break;
      await delay(50);
    }
    assert.ok(endpoint, `The isolated Firefox instance did not start: ${log}`);
    ws = new WebSocket(endpoint.replace(/\/$/, '') + '/session');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Firefox WebSocket did not open')), 10000);
      ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('Firefox WebSocket failed')); }, { once: true });
    });
    ws.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.method === 'log.entryAdded' && message.params.level === 'error') report.errors.push(message.params);
      if (message.method?.startsWith('network.') && message.params.request?.url.startsWith(base)) {
        const { request, response, errorText, timestamp } = message.params;
        report.network.push({ event: message.method, request: request.request, url: request.url,
          status: response?.status, errorText, timestamp, scripts });
      }
      const item = pending.get(message.id);
      if (!item) return;
      pending.delete(message.id); clearTimeout(item.timer);
      if (message.type === 'error') item.reject(new Error(JSON.stringify(message)));
      else item.resolve(message.result);
    });
    ws.addEventListener('close', () => {
      for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error('Firefox WebSocket closed')); }
      pending.clear();
    });
    const session = await call('session.new', { capabilities: {} });
    report.browser ??= session.capabilities;
    await call('session.subscribe', { events: ['log.entryAdded', 'network.fetchError', 'network.responseCompleted'] });
    ({ context } = await call('browsingContext.create', { type: 'tab' }));
    for (const width of [1440, 390]) {
      await call('browsingContext.setViewport', { context, viewport: { width, height: 1000 }, devicePixelRatio: 1 });
      await navigate('/trees');
      if (scripts) await until(`document.querySelector('[data-tree-refresh=dark]')?.dataset.bmlActionBound==='true'&&${root('dark')}.querySelectorAll('[data-tree-children]').length===12`);
      else assert.equal(await evaluate(`document.querySelector('[data-tree-refresh=dark]')?.dataset.bmlActionBound`), undefined);
      await until(`document.fonts.status==='loaded'`);
      for (const id of ['dark', 'light']) {
        const result = { width, palette: id, scripts }; report.cases.push(result);
        result.rows = await evaluate(`[...${root(id)}.querySelectorAll('[role=treeitem]')].map(row=>({level:row.getAttribute('aria-level'),selected:row.getAttribute('aria-selected'),position:row.getAttribute('aria-posinset'),size:row.getAttribute('aria-setsize'),label:row.querySelector('.tree-label').textContent,disabled:row.querySelector('.ui-tree-row').disabled===true}))`);
        assert.equal(result.rows.length, 15);
        assert.deepEqual(result.rows.slice(0, 13).map(row => row.level), Array.from({ length: 13 }, (_, i) => String(i + 1)));
        assert.equal(result.rows[12].label, 'veryLongComponentIdentifierThatRemainsReadableWithoutSpaces.bml');
        assert.equal(result.rows[12].selected, 'true'); assert.equal(result.rows[13].disabled, true);
        if (scripts) {
          assert.deepEqual(result.rows.map(row => [row.position, row.size]), [['1', '3'], ...Array.from({ length: 12 }, () => ['1', '1']), ['2', '3'], ['3', '3']]);
          result.groups = await evaluate(`[...${root(id)}.querySelectorAll('[data-tree-children]')].map(group=>({role:group.getAttribute('role'),child:group.getAttribute('aria-owns'),exists:!!document.getElementById(group.getAttribute('aria-owns')),childLevel:document.getElementById(group.getAttribute('aria-owns'))?.getAttribute('aria-level')}))`);
          assert.equal(result.groups.length, 12); assert.ok(result.groups.every(group => group.role === 'group' && group.exists));
          assert.deepEqual(result.groups.map(group => group.childLevel), Array.from({ length: 12 }, (_, i) => String(i + 2)));
          assert.equal(await evaluate(`${root(id)}.querySelectorAll('[tabindex="0"]').length`), 1);
          await evaluate(`${root(id)}.querySelector('[tabindex="0"]').focus()`); await focused(id, 12);
          await key('Home'); await focused(id, 0);
          for (let index = 1; index < 13; index++) { await key('ArrowRight'); await focused(id, index); }
          await key('ArrowRight'); await focused(id, 12);
          await key('ArrowLeft'); await focused(id, 11);
          await key('ArrowLeft'); await focused(id, 11);
          assert.equal(await evaluate(`${row(id, 12)}.hidden&&${row(id, 11)}.getAttribute('aria-expanded')==='false'`), true);
          await key('ArrowLeft'); await focused(id, 10);
          await key('ArrowRight'); await focused(id, 11);
          await key('ArrowRight'); await focused(id, 11);
          assert.equal(await evaluate(`${row(id, 12)}.hidden`), false);
          await key('ArrowRight'); await focused(id, 12);
          await key('ArrowDown'); await focused(id, 14); await key('ArrowUp'); await focused(id, 12);
          await key('Home'); await key('Enter'); await key('End'); await focused(id, 14);
          await key('ArrowUp'); await focused(id, 0);
          await key('Enter'); await key('End'); await key('ArrowUp'); await focused(id, 12);
          assert.equal(await evaluate(`${root(id)}.querySelectorAll('[aria-selected="true"]').length`), 1);
        } else {
          result.links = await evaluate(`[...${root(id)}.querySelectorAll('a')].map(link=>({href:link.getAttribute('href'),visible:link.checkVisibility(),tabIndex:link.tabIndex}))`);
          assert.equal(result.links.length, 2); assert.ok(result.links.every(link => link.visible && link.tabIndex === 0 && link.href.startsWith('/')));
          await evaluate(`${control(id, 12)}.focus()`); await focused(id, 12);
        }
        result.geometry = await evaluate(`(()=>{const tree=${root(id)},link=${control(id, 12)},label=link.querySelector('.tree-label'),box=tree.getBoundingClientRect(),text=label.getBoundingClientRect();return {height:box.height,scrollTop:tree.scrollTop,labelWidth:text.width,labelHeight:text.height,labelRight:text.right,treeRight:box.right,overflow:document.documentElement.scrollWidth-innerWidth,href:link.getAttribute('href')};})()`);
        const geometry = result.geometry;
        assert.ok(geometry.height <= (width === 390 ? 220 : 260) && geometry.labelWidth > 0 && geometry.labelRight <= geometry.treeRight + 1 && geometry.overflow <= 0, JSON.stringify(geometry));
        if (scripts) assert.ok(geometry.scrollTop > 0, JSON.stringify(geometry));
        if (width === 390) assert.ok(geometry.labelHeight >= 32, 'Deep phone labels wrap');
        assert.equal(geometry.href, '/components/content#ref-tree-item');
        await evaluate(`${root(id)}.scrollIntoView({block:'center'})`);
        const capture = await call('browsingContext.captureScreenshot', { context, origin: 'viewport' });
        await writeFile(join(artifacts, `catalog-${mode}-firefox-tree-${scripts ? 'enhanced' : 'native'}-${id}-${width}.png`), Buffer.from(capture.data, 'base64'));
      }
      if (scripts) {
        const injected = await raw(axeSource); assert.equal(injected.type, 'success', JSON.stringify(injected));
        for (const appearance of ['dark', 'light']) {
          await evaluate(`(()=>{const control=document.querySelector('[data-catalog-light]');if(control.checked!==${appearance === 'light'})control.click();return true})()`);
          const scan = await evaluate(`axe.run(document,{resultTypes:['violations','incomplete','passes','inapplicable']}).then(result=>({version:result.testEngine.version,url:result.url,violations:result.violations,incomplete:result.incomplete,passes:result.passes.map(rule=>rule.id),inapplicable:result.inapplicable.map(rule=>rule.id)}))`);
          report.scans.push({ width, appearance, scripts, ...scan });
          assert.deepEqual(scan.violations, [], JSON.stringify(scan.violations));
        }
      } else {
        report.nativeAuditLimit = 'DOM, native keyboard, fonts and layout only: Firefox does not run asynchronous axe with page JavaScript disabled.';
      }
      await evaluate(`${control('light', 12)}.focus()`); await key('Enter');
      await until(`location.pathname==='/components/content'&&location.hash==='#ref-tree-item'&&document.readyState==='complete'`);
      await until(`document.fonts.status==='loaded'`);
      const destination = await evaluate(`({url:location.href,ready:document.readyState,fonts:[...document.fonts].map(font=>({family:font.family,status:font.status}))})`);
      report.destinations.push({ width, scripts, ...destination });
      assert.ok(destination.fonts.every(font => font.status !== 'error'), JSON.stringify(destination));
    }
    await call('input.releaseActions', { context });
    assert.deepEqual(report.errors, []);
  } catch (error) {
    failed = true; report.failure = { message: error.message, stack: error.stack, scripts, pid: browser.pid };
    if (ws?.readyState === WebSocket.OPEN && context) {
      try {
        report.failure.page = await evaluate(`({url:location.href,ready:document.readyState,active:document.activeElement?.outerHTML.slice(0,2000)})`);
        const capture = await call('browsingContext.captureScreenshot', { context, origin: 'viewport' });
        await writeFile(join(artifacts, `catalog-${mode}-firefox-tree-failure.png`), Buffer.from(capture.data, 'base64'));
      } catch (diagnosticError) { report.failure.diagnosticError = String(diagnosticError); }
    }
    throw error;
  } finally {
    if (ws?.readyState === WebSocket.OPEN) {
      try { await call('browser.close'); } catch (error) { cleanupError = error; report.cleanupError = String(error); }
      finally { ws.close(); }
    }
    if (browser.exitCode === null) {
      browser.kill('SIGTERM');
      for (let i = 0; i < 100 && browser.exitCode === null; i++) await delay(25);
      if (browser.exitCode === null) browser.kill('SIGKILL');
    }
    await writeFile(join(artifacts, `catalog-${mode}-firefox-tree-${scripts ? 'enhanced' : 'native'}.log`), log);
    await rm(profile, { recursive: true, force: true });
    if (cleanupError && !failed) throw cleanupError;
  }
}

try { await qualify(true); await qualify(false); }
finally { await writeFile(join(artifacts, `catalog-${mode}-firefox-tree.json`), JSON.stringify(report, null, 2)); }
console.log(`Firefox ${report.browser.browserVersion} ${mode}: ${report.cases.length} cases, ${report.scans.length} full-document axe scans`);
