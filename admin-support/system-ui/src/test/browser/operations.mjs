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
const kind = process.env.ATLAS_OPERATIONS_KIND;
assert.ok(['scheduler','packages'].includes(kind));
const reports = resolve(`build/reports/operations-${kind}-browser`);
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
  await call('Page.enable'); await call('Runtime.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await go('/login');
  await fill('input[name=identifier]', 'admin'); await fill('input[name=password]', password); await click('button[type=submit]');
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')");
  const measurements = [];
  const state = name => `JSON.parse(document.querySelector('script[data-bml-state-key^="system-${name}.model"]').textContent)`;
  const choose = async (label,value) => { await click(`button[aria-label="${label}"]`); await clickText(value,'[role=option]'); };
  const checked = async (selector,value) => { if (await evaluate(`document.querySelector(${JSON.stringify(selector)}).checked`) !== value) await click(selector); };
  const capture = async (host,name,selector='') => {
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      if (selector) await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
      const bounds = await evaluate(`({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:document.querySelector('dialog[open]')?.getBoundingClientRect().width??0})`);
      assert.ok(bounds.scrollWidth<=width+2 && bounds.dialogWidth<=width,JSON.stringify(bounds));
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(shot.data,'base64'));
      measurements.push({host,name,...bounds});
    }
  };
  const fillJson = async value => {
    const selector='[data-bml-json-editor][data-name=parameters] .cm-content[contenteditable=true]';
    await until(`document.querySelector(${JSON.stringify(selector)})`);
    await click(selector);
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,windowsVirtualKeyCode:65,commands:['selectAll']});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});
    await call('Input.insertText',{text:value});
  };
  const hosts=[base,process.env.ATLAS_OPERATIONS_STANDALONE_URL];
  for(const [host,origin] of hosts.entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base=origin;
    if(kind==='packages') {
      await go('/system/packages');
      const key=host===0?'root-collection-installer':'bible';
      const card=`[data-package-key="${key}"]`;
      assert.ok(await evaluate(`document.querySelector('${card} details').open`),'Package installers are visible on arrival'); await capture(host,'available-package',card);
      await clickText(host===0?'Run installer':'Install package',`${card} button`);
      await until("document.querySelector('dialog[open] input[name=confirmed]')");
      await capture(host,host===0?'installer-confirmation':'package-confirmation','dialog[open]');
      await click('dialog[open] button[type=submit]');
      assert.ok(await evaluate('Boolean(document.querySelector("dialog[open]"))'),'installation requires confirmation');
      await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
      await until(`${state('packages')}.data.packages.all.find(pkg=>pkg.key==='${key}').versions[0].installed.length===1`);
      assert.ok(await evaluate(`${state('packages')}.feedback.message==='Installation completed.'`));
      await until("document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent==='Installation completed.'");
      assert.equal(await evaluate("document.querySelectorAll('.alert[data-tone=success]').length"),0,'Package installation success is only a toast');
      continue;
    }
    await go('/system/scheduler/new');
    await fill('input[name=name]',`Browser maintenance ${host}`);
    await fill('textarea[name=description]','Retain this description while changing definitions.');
    await choose('Job definition','Purge Job History');
    await until(`${state('schedule')}.jobName==='purge-job-history'`);
    assert.equal(await evaluate("document.querySelector('input[name=name]').value"),`Browser maintenance ${host}`);
    assert.equal(await evaluate("document.querySelector('textarea[name=description]').value"),'Retain this description while changing definitions.');
    assert.ok(await evaluate("document.body.innerText.includes('Queue: common')"));
    if(await evaluate("Boolean(document.querySelector('[data-parameter-schema]'))")) await click('[data-parameter-schema] summary');
    else assert.equal(await evaluate(`${state('schedule')}.definitions.find(definition=>definition.name==='purge-job-history').parameterSchema`),null,'absent schemas have no disclosure');
    await capture(host,'definition','[data-schedule-editor]');
    await fill('input[name=cron]','bad cron'); await clickText('Validate cron');
    await until(`${state('schedule')}.cronCheck && !${state('schedule')}.cronCheck.valid`);
    await fill('input[name=cron]','0 2 * * *'); await clickText('Validate cron');
    await until(`${state('schedule')}.cronCheck?.valid && ${state('schedule')}.cronCheck.nextRuns.length===5`);
    await capture(host,'cron-preview','[data-cron-preview]');
    await checked('input[name=concurrent]',false); await checked('input[name=catchUp]',true);
    await fill('input[name=maxCatchUp]','3');
    await fillJson('{invalid'); await clickText('Save schedule');
    await until(`${state('schedule')}.feedback.failed`);
    assert.equal(await evaluate("document.querySelector('input[name=maxCatchUp]').value"),'3');
    await fillJson('{"retentionDays":47}'); await fill('input[name=cron]','');
    assert.equal(await evaluate("document.querySelector('input[name=cron]').value"),'');
    assert.ok(await evaluate("!document.querySelector('[data-cron-preview]') || document.querySelector('[data-cron-preview]').hidden"),'editing cron hides stale previews without a request');
    await clickText('Save schedule');
    await until(`location.pathname.startsWith('/system/scheduler/') && !location.pathname.endsWith('/new') && document.readyState==='complete' && document.querySelector('[data-schedule-editor]') && ${state('schedule')}.id!=='new'`);
    const path=await evaluate('location.pathname');
    assert.equal(await evaluate(`${state('schedule')}.parameters`),'{"retentionDays":47}');
    assert.equal(await evaluate(`${state('schedule')}.cron`),'');
    assert.equal(await evaluate(`${state('schedule')}.maxCatchUp`),'3');
    await capture(host,'detail','[data-schedule-editor]');
    await fill('input[name=name]',`Unsaved while triggering ${host}`); await fillJson('{"retentionDays":49}');
    await clickText('Trigger now'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await capture(host,'trigger-confirmation','dialog[open]');
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until(`${state('schedule')}.history.length===1 && !document.querySelector('dialog[open]')`);
    assert.equal(await evaluate("document.querySelector('input[name=name]').value"),`Unsaved while triggering ${host}`,'triggering retains unsaved schedule fields');
    assert.ok(await evaluate("document.querySelector('textarea[name=parameters]').value.includes('49')"),'triggering retains unsaved JSON');
    await clickText('Trigger now'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await checked('dialog[open] input[name=confirmed]',true); await click('dialog[open] button[type=submit]');
    await until(`${state('schedule')}.feedback.failed && document.querySelector('dialog[open]')?.innerText.includes('active executions')`,'backend rejection stays visible in the confirmation');
    assert.equal(await evaluate("document.querySelector('input[name=name]').value"),`Unsaved while triggering ${host}`,'rejection retains unsaved fields');
    await capture(host,'trigger-rejection','dialog[open]');
    await escape(); await checked('input[name=concurrent]',true); await clickText('Save schedule');
    await until(`${state('schedule')}.feedback.message==='Scheduled job saved.' && ${state('schedule')}.concurrent`);
    await clickText('Trigger now'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await checked('dialog[open] input[name=confirmed]',true); await click('dialog[open] button[type=submit]');
    await until(`${state('schedule')}.history.length===2 && !document.querySelector('dialog[open]')`,'a repeated successful confirmation closes again');
    await click('[data-schedule-history] summary'); await capture(host,'execution-history','[data-schedule-history]');
    await clickText('Cancel execution','[data-schedule-history] button');
    await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until(`${state('schedule')}.history[0].status==='CANCELLED'`); await escape();
    await go('/system/scheduler');
    assert.deepEqual(await evaluate("[...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())"), ['Name','Cron','Enabled','Last run','Next run','Actions'], 'Scheduler retains separate source columns');
    assert.equal(await evaluate("document.querySelectorAll('tbody td:first-child .copy-control').length"),0,'Scheduler names have no extra copy control');
    const row=await evaluate(`(() => {const a=[...document.querySelectorAll('a')].find(a=>a.getAttribute('href')===${JSON.stringify(path)}); a.closest('tr').dataset.operationsRow='true'; return '[data-operations-row]';})()`);
    await clickText('Disable',`${row} button`); await until(`${state('list')}.rows.find(row=>row.id===${JSON.stringify(path.substring(path.lastIndexOf('/')+1))}).enabled===false`);
    await evaluate(`document.querySelector('a[href="${path}"]').closest('tr').dataset.operationsRow='true'`);
    assert.ok(await evaluate("document.querySelector('[data-operations-row]').innerText.includes('Disabled')"));
    await clickText('Enable','[data-operations-row] button'); await until(`${state('list')}.rows.find(row=>row.id===${JSON.stringify(path.substring(path.lastIndexOf('/')+1))}).enabled===true`);
    await capture(host,'scheduler-list','table');
    await go('/system/jobs?status=PENDING&source=SCHEDULER');
    assert.ok(await evaluate(`${state('list')}.history.every(job=>job.status==='PENDING' && job.source==='SCHEDULER')`));
    assert.ok(await evaluate(`${state('list')}.hasNext`));
    await click('[data-job-history] summary'); await capture(host,'global-execution','[data-job-history]');
    await clickText('Next','a'); await until("location.search.includes('offset=25') && document.querySelector('[data-job-history]')");
    assert.equal(await evaluate(`${state('list')}.offset`),25);
    await go(`/system/scheduler/${process.env.ATLAS_PRINCIPAL_SCHEDULE_ID}`);
    await fill('input[name=principalId]','not-a-principal');
    await checked('form:has(input[name=principalId]) input[name=confirmed]',true); await clickText('Update principal');
    await until(`${state('schedule')}.feedback.failed`);
    assert.equal(await evaluate("document.querySelector('input[name=principalId]').value"),'not-a-principal','failed assignment keeps its draft');
    await fill('input[name=principalId]',process.env.ATLAS_OPERATIONS_ADMIN_ID);
    await checked('form:has(input[name=principalId]) input[name=confirmed]',true); await clickText('Update principal');
    await until(`${state('schedule')}.principalState==='ACTIVE'`);
    await capture(host,'execution-principal','input[name=principalId]');
    await checked('input[name=clear]',true); await checked('form:has(input[name=principalId]) input[name=confirmed]',true); await clickText('Update principal');
    await until(`${state('schedule')}.principalState==='NEEDS_PRINCIPAL'`);
    await go(path); await clickText('Delete schedule'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await capture(host,'delete-confirmation','dialog[open]');
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until("location.pathname==='/system/scheduler'");
  }
  if(kind==='packages') for(const [host,origin] of hosts.entries()) {
    base=origin; await go('/system/packages'); await clickText('Refresh');
    await until(`${state('packages')}.data.packages.all.find(pkg=>pkg.key==='bible').versions[0].installed.length===1`);
    for(const key of ['root-collection-installer','bible']) assert.ok(await evaluate(`document.querySelector('[data-package-key="${key}"]').innerText.includes('1 / 1 versions installed')`));
    await capture(host,'installed-packages','[data-package-key=bible]');
    await clickText('History','a'); await until("location.search.includes('tab=history') && document.querySelector('table')");
    assert.ok(await evaluate("document.querySelector('table').innerText.includes('bible')"));
    await capture(host,'installation-history','table');
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));
  console.log(`${kind} browser checks passed in both hosts: ${measurements.length} responsive measurements.`);
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit',resolve));
  await rm(profile,{recursive:true,force:true});
}
