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
const reports = resolve('build/reports/bible-browser');
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
  const measurements = [];
  const state = `JSON.parse(document.querySelector('script[data-bml-state-key^="system-bible.model"]').textContent)`;
  const choose = async (label,value) => { await click(`button[aria-label="${label}"]`); await clickText(value,'[role=option]'); if (label==='Permitted uses') await escape(); };
  const capture = async (host,name,dialog=false) => {
    for (const width of [1920,1024,768,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      const bounds = await evaluate(`({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:${dialog?'document.querySelector("dialog[open]").getBoundingClientRect().width':'0'}})`);
      assert.ok(bounds.scrollWidth<=width+2 && bounds.dialogWidth<=width,JSON.stringify(bounds));
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from(shot.data,'base64'));
      measurements.push({host,name,...bounds});
    }
  };
  for (const [host,origin] of [base,process.env.ATLAS_BIBLE_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base=origin;
    await go('/system/integrations/bible');
    await until("document.querySelector('button[aria-label=Configuration]')?.disabled");
    await click('button[aria-label=Configuration]');
    assert.equal(await evaluate(`${state}.feedback.failed`),false,'An empty configuration selector does not report an error');
    assert.equal(await evaluate("document.querySelectorAll('.system-content .alert').length"),0);
    await capture(host,'empty');
    await clickText('Add configuration'); await until("document.querySelector('dialog[open] input[name=name]')");
    await fill('dialog[open] input[name=apiKey]','fixture-key');
    for(const width of [1440,390]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await fill('dialog[open] input[name=name]',' '); await clickText('Save configuration');
      await until(`document.querySelector('dialog[open] [data-bible-modal-error]:popover-open .toast-message')?.textContent==='Enter a configuration name.'`);
      assert.equal(await evaluate("Boolean(document.querySelector('[data-system-error]:popover-open'))"),false,'Modal errors have only the dialog-owned toast');
      await click('dialog[open] [data-bible-modal-error]:popover-open button[aria-label="Dismiss notification"]');
      await until("!document.querySelector('dialog[open] [data-bible-modal-error]:popover-open')");
      assert.ok(await evaluate("Boolean(document.querySelector('dialog[open]'))"),'Dismissing the toast retains the modal');
      assert.equal(await evaluate("document.querySelector('dialog[open] input[name=apiKey]').value"),'fixture-key','Validation preserves the draft');
    }
    await escape(); await until(`!document.querySelector('dialog[open]') && ${state}.mode===''`);
    assert.equal(await evaluate(`${state}.feedback.message`),'','Dismissing validation clears its feedback');
    await clickText('Add configuration'); await until("document.querySelector('dialog[open] input[name=name]')");
    await fill('dialog[open] input[name=name]',`Browser Bible ${host}`);
    await fill('dialog[open] input[name=apiKey]','fixture-key');
    await capture(host,'configuration',true);
    await clickText('Save configuration');
    await until(`!document.querySelector('dialog[open]') && ${state}.catalog.length===20`,'initial catalog automatically loads after saving');
    assert.equal(await evaluate(`${state}.draft.apiKey`),'');
    await capture(host,'catalog');
    await clickText('Next'); await until(`${state}.offset===20 && ${state}.catalog.length===1`);
    await clickText('Previous'); await until(`${state}.offset===0 && ${state}.catalog.length===20`);
    await fill('input[name=query]','edition-1'); await clickText('Search');
    await until(`${state}.search==='edition-1' && ${state}.catalog.length===11`);
    await click('[data-bible-entry="edition-1"] button');
    await until("document.querySelector('dialog[open] input[name=variant]')");
    await fill('dialog[open] input[name=variant]','reader');
    await choose('Permitted uses','Display'); await choose('Permitted uses','AI context');
    await capture(host,'text-registration',true);
    await clickText('Register','dialog[open] button');
    await until(`!document.querySelector('dialog[open]') && ${state}.sources.length===1`);
    assert.deepEqual(await evaluate(`${state}.sources[0].allowedUses`),['DISPLAY','AI_CONTEXT']);
    await clickText('Edit configuration'); await until("document.querySelector('dialog[open] input[name=apiKey]')");
    assert.equal(await evaluate("document.querySelector('dialog[open] input[name=apiKey]').value"),'');
    await click('dialog[open] input[name=enabled]'); await clickText('Save configuration');
    await until(`!document.querySelector('dialog[open]') && !${state}.configurations[0].enabled`);
    assert.ok(await evaluate("document.body.innerText.includes('Enable this configuration to browse its catalog.')"));
    await clickText('Test connection'); await until(`${state}.feedback.message==='Bible provider connection succeeded.'`);
    await clickText('Edit configuration'); await until("document.querySelector('dialog[open] input[name=apiKey]')");
    await fill('dialog[open] input[name=apiKey]','rejected-key'); await clickText('Save configuration');
    await until(`!document.querySelector('dialog[open]') && ${state}.feedback.message==='Bible provider configuration saved.'`);
    for (const theme of ['dark','light']) for (const width of [1920,1024,768,390]) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await go('/system/integrations/bible');
      await until("document.querySelector('button[aria-label=Configuration]') && !document.querySelector('button[aria-label=Configuration]').disabled");
      const before=await evaluate("(() => {const r=document.querySelector('.system-content .ui-card').getBoundingClientRect();return {top:r.top,height:r.height};})()");
      let previous=await evaluate(`${state}.feedback.noticeSequence`);
      for (let attempt=0;attempt<2;attempt++) {
        await clickText('Test connection');
        await until(`${state}.feedback.failed && ${state}.feedback.message.length>0 && document.querySelector('[data-system-error]:popover-open .toast-message')?.textContent===${state}.feedback.message && ${state}.feedback.noticeSequence>${previous}`);
        const notice=await evaluate("(() => {const n=document.querySelector('[data-system-error]:popover-open'),r=n.getBoundingClientRect(),c=document.querySelector('.system-content .ui-card').getBoundingClientRect();return {tone:n.dataset.tone,position:getComputedStyle(n).position,top:c.top,height:c.height,left:r.left,right:r.right,bottom:r.bottom,role:n.querySelector('.toast-message').getAttribute('role'),inline:document.querySelectorAll('.system-content .alert').length};})()");
        assert.equal(notice.tone,'error');assert.equal(notice.role,'alert');assert.equal(notice.position,'fixed');assert.equal(notice.inline,0);
        assert.equal(notice.top,before.top);assert.equal(notice.height,before.height);
        assert.ok(notice.left>=0 && notice.right<=width && notice.bottom<=1000,JSON.stringify(notice));
        previous=await evaluate(`${state}.feedback.noticeSequence`);
        if(attempt===0) {
          const shot=await call('Page.captureScreenshot',{format:'png'});
          await writeFile(join(reports,`${host}-error-toast-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
        }
        if(attempt===1) {
          await clickText('Edit configuration'); await until("document.querySelector('dialog[open] input[name=apiKey]')");
          assert.equal(await evaluate("document.querySelector('.toast-region:popover-open')?.dataset.toastTone"),'error','Clearing action feedback retains the visible error tone');
          await clickText('Cancel','dialog[open] button'); await until("!document.querySelector('dialog[open]')");
        }
        await click('.toast-region:popover-open button[aria-label="Dismiss notification"]');
        await until("!document.querySelector('[data-system-error]:popover-open')");
      }
      await click('button[aria-label=Configuration]'); await escape();
      assert.equal(await evaluate("Boolean(document.querySelector('[data-system-error]:popover-open'))"),false,'Unrelated interaction does not replay a dismissed error');
    }
    await clickText('Edit configuration'); await until("document.querySelector('dialog[open] input[name=enabled]')");
    await fill('dialog[open] input[name=apiKey]','fixture-key');
    await click('dialog[open] input[name=enabled]'); await clickText('Save configuration');
    await until(`!document.querySelector('dialog[open]') && ${state}.configurations[0].enabled && ${state}.catalogLoaded`);
    await fill('input[name=query]',''); await choose('Catalog','Audio');
    await until(`${state}.kind==='AUDIO' && ${state}.catalog.length===1`);
    await click('[data-bible-entry="recording"] button');
    await until(`document.querySelector('dialog[open] select[name=target]') && ${state}.targetsLoaded && ${state}.targets.length===1`,'audio variants automatically load');
    const targetName=await evaluate("[...document.querySelector('dialog[open] select[name=target]').options].find(option=>option.value).textContent");
    await choose('Bible variant',targetName); await choose('Permitted uses','Playback');
    await capture(host,'audio-registration',true);
    await clickText('Register','dialog[open] button');
    await until(`!document.querySelector('dialog[open]') && ${state}.sources.length===2`);
    const textId=await evaluate(`${state}.sources.find(source=>source.kind==='TEXT').id`);
    const audioId=await evaluate(`${state}.sources.find(source=>source.kind==='AUDIO').id`);
    await capture(host,'sources');
    await clickText('Refresh',`[data-bible-source="${textId}"] button`);
    await until(`${state}.feedback.message==='Bible source refreshed.'`);
    await clickText('Remove source',`[data-bible-source="${audioId}"] button`);
    await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await capture(host,'remove-source',true);
    await clickText('Remove source','dialog[open] button');
    assert.ok(await evaluate('Boolean(document.querySelector("dialog[open]"))'),'source removal requires confirmation');
    await click('dialog[open] input[name=confirmed]'); await clickText('Remove source','dialog[open] button');
    await until(`!document.querySelector('dialog[open]') && ${state}.sources.length===1`);
    await clickText('Remove configuration'); await until("document.querySelector('dialog[open] input[name=purge]')");
    await capture(host,'purge-configuration',true);
    await click('dialog[open] input[name=confirmed]'); await clickText('Remove configuration','dialog[open] button');
    assert.ok(await evaluate('Boolean(document.querySelector("dialog[open]"))'),'purge requires explicit consent');
    await click('dialog[open] input[name=purge]'); await clickText('Remove configuration','dialog[open] button');
    await until(`!document.querySelector('dialog[open]') && ${state}.configurations.length===0`);
  }
  assert.deepEqual(errors,[]);
  await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));
  console.log(`Bible browser checks passed: private credential settings, automatic catalog/target loading, paging/search, text/audio registration and uses, disabled configuration testing, source refresh/removal and explicit purge in both hosts; ${measurements.length} responsive measurements.`);
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit',resolve));
  await rm(profile,{recursive:true,force:true});
}
