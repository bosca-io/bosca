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
const reports = resolve('build/reports/security-browser');
await mkdir(reports, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'atlas-system-chrome-'));
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const chrome = spawn(process.env.ATLAS_CHROME_BINARY ?? (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let ws, launchError;
chrome.on('error', error => { launchError = error; });
const errors = [], modalChecks = [], scopeModalChecks = [];
const contrastRatio = (foreground, background) => {
  const luminance = color => color.match(/[\d.]+/g).slice(0,3).map(Number).map(channel=>{const value=channel/255;return value<=0.04045?value/12.92:((value+0.055)/1.055)**2.4;}).reduce((sum,value,index)=>sum+value*[0.2126,0.7152,0.0722][index],0);
  const a=luminance(foreground),b=luminance(background);return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);
};
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
  const checkModalLayout = async () => {
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const bounds = await evaluate(`(() => {
      const dialog = [...document.querySelectorAll('dialog[open]')].at(-1); if (!dialog) return null;
      const footer = [...dialog.querySelectorAll('.modal-footer')].find(footer=>footer.closest('dialog')===dialog); if (!footer) throw Error('Missing modal footer: '+dialog.getAttribute('aria-label'));
      const box = dialog.getBoundingClientRect(), f = footer.getBoundingClientRect(), style = getComputedStyle(footer);
      const buttons = [...footer.querySelectorAll('button')].map(button => { const r=button.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom}; });
      return {title:dialog.getAttribute('aria-label'),width:innerWidth,height:innerHeight,left:box.left,right:box.right,top:box.top,bottom:box.bottom,
        footerLeft:f.left,footerRight:f.right,footerTop:f.top,footerBottom:f.bottom,paddingLeft:parseFloat(style.paddingLeft),paddingRight:parseFloat(style.paddingRight),gap:parseFloat(style.gap),buttons};
    })()`);
    if (!bounds) return;
    assert.ok(bounds.left>=-1 && bounds.right<=bounds.width+1 && bounds.top>=-1 && bounds.bottom<=bounds.height+1,JSON.stringify(bounds));
    assert.ok(bounds.paddingLeft>=18 && bounds.paddingRight>=18 && bounds.gap>=8,JSON.stringify(bounds));
    assert.ok(bounds.footerBottom<=bounds.bottom+1 && bounds.footerTop>=bounds.top,JSON.stringify(bounds));
    for (const button of bounds.buttons) assert.ok(button.left>=bounds.footerLeft+17 && button.right<=bounds.footerRight-17 && button.bottom<=bounds.footerBottom-10,JSON.stringify(bounds));
    modalChecks.push(bounds);
  };
  const until = async (expression, label = expression) => {
    for (let attempt = 0; attempt < 150; attempt++) { if (await evaluate(`Boolean(${expression})`)) { if(expression.includes('dialog[open]')) await checkModalLayout(); return; } await new Promise(resolve => setTimeout(resolve, 100)); }
    throw new Error(`Timed out: ${label}. Errors: ${JSON.stringify(errors)}. Page: ${await evaluate('document.body.innerText.slice(-6000)')}`);
  };
  const go = async path => {
    await call('Page.navigate', { url: base + path });
    await until(`location.pathname === ${JSON.stringify(path.split('?')[0])} && document.readyState === 'complete' && typeof boscaAuth !== 'undefined'`);
    assert.equal(await evaluate("document.body.innerText.includes('Unable to load this page')"), false, path);
    await evaluate('document.fonts.ready.then(() => true)');
  };
  const click = async selector => {
    await until("!document.querySelector('[data-bml-action-pending]')");
    await evaluate(`document.querySelector(${JSON.stringify(selector)})?.scrollIntoView({block:'center'})`);
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const rect = await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); if (!element) throw new Error('Missing ${selector}'); const r = element.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,hit=document.elementFromPoint(x,y);if(hit!==element&&!element.contains(hit))throw Error('Click blocked: '+element.outerHTML.slice(0,200)+' by '+hit?.outerHTML.slice(0,200));return {x,y}; })()`);
    await call('Input.dispatchMouseEvent', { type: 'mousePressed', ...rect, button: 'left', clickCount: 1 });
    await call('Input.dispatchMouseEvent', { type: 'mouseReleased', ...rect, button: 'left', clickCount: 1 });
  };
  const clickText = async (text, selector = 'button') => {
    await until("!document.querySelector('[data-bml-action-pending]')");
    const target = await evaluate(`(() => { const element = [...document.querySelectorAll(${JSON.stringify(selector)})].find(item => item.textContent.trim() === ${JSON.stringify(text)} && item.getBoundingClientRect().width); if (!element) throw new Error('Missing control: '+${JSON.stringify(text)}); element.dataset.securityTestTarget='true'; return '[data-security-test-target]'; })()`);
    await click(target); await evaluate("document.querySelector('[data-security-test-target]')?.removeAttribute('data-security-test-target')");
  };
  const fill = async (selector, value) => {
    await click(selector);
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,windowsVirtualKeyCode:65,commands:['selectAll']});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});
    await call('Input.insertText',{text:value});
    assert.equal(await evaluate(`document.querySelector(${JSON.stringify(selector)}).value`),value,selector);
  };
  const escape = async () => { await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); };
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await go('/login');
  await fill('input[name=identifier]', 'admin'); await fill('input[name=password]', password); await click('button[type=submit]');
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')");
  const modalHost = base;
  for (const [host, origin] of [modalHost,process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    for (const theme of ['dark','light']) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      for (const width of [1920,1024,768,390]) {
        await call('Emulation.setDeviceMetricsOverride',{width,height:width<600?660:1000,deviceScaleFactor:1,mobile:width<768});
        for (const [route,label] of [['personas','New persona'],['groups','New group'],['tokens','Create token']]) {
          await go('/system/security/'+route); await clickText(label);
          await until("document.querySelector('dialog[open] input[name=name]')");
          const shot=await call('Page.captureScreenshot',{format:'png'});
          await writeFile(join(reports,`modal-${route}-${host}-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
          if(route==='personas') {
            assert.ok(await evaluate("document.querySelector('dialog[open] textarea[name=description]').getBoundingClientRect().height<70"), 'Two-row descriptions retain the source density');
            assert.ok(await evaluate("(() => { const body=document.querySelector('dialog[open] .modal-fields'),button=[...body.querySelectorAll('button')].find(button=>button.textContent.trim()==='Select all subsystems'); return button && button.getBoundingClientRect().left>=body.getBoundingClientRect().left+17; })()"),'Select all belongs inside the padded form body');
          }
          if(route==='tokens') {
            await fill('dialog[open] input[name=name]','Scope modal draft');
            await fill('dialog[open] textarea[name=description]','Retain description across scope choices');
            const expiry=await evaluate("document.querySelector('dialog[open] input[name=expires]').value");
            assert.ok(expiry,'New tokens start with an expiration');
            await clickText('Configure scopes');await until("document.querySelector('[data-token-scope-builder] > dialog[open]')");
            assert.equal(await evaluate("document.querySelectorAll('dialog[open]').length"),2,'Scope configuration keeps the token dialog open underneath');
            await click('[data-token-scope-builder] [data-name=selectedScopes] button[role=combobox]');
            const scope=await evaluate("[...document.querySelectorAll('[role=option]')].find(option=>option.textContent.startsWith('content:view —'))?.textContent");
            assert.ok(scope);await clickText(scope,'[role=option]');await escape();
            assert.ok(await evaluate("Boolean(document.querySelector('[data-token-scope-builder] > dialog[open]'))"),'Escape closes the scope select before its containing dialog');
            const scopeShot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(reports,`scope-modal-${host}-${theme}-${width}.png`),Buffer.from(scopeShot.data,'base64'));
            const dismiss=width===1024?'header':width===768?'escape':'done';
            if(dismiss==='header')await click('[data-token-scope-builder] > dialog[open] [data-bml-ref=close]');
            else if(dismiss==='escape')await escape();
            else await clickText('Done','[data-token-scope-builder] button');
            await until("!document.querySelector('[data-token-scope-builder] > dialog[open]') && document.querySelector('.token-scope-tags')?.textContent.includes('content:view') && document.activeElement?.hasAttribute('data-token-scopes-trigger')");
            assert.equal(await evaluate("document.querySelector('[data-token-editor] input[name=name]').value"),'Scope modal draft');
            assert.equal(await evaluate("document.querySelector('[data-token-editor] textarea[name=description]').value"),'Retain description across scope choices');
            assert.equal(await evaluate("document.querySelector('[data-token-editor] input[name=expires]').value"),expiry);
            scopeModalChecks.push({host,theme,width,dismiss,expiry});
          }
          await click('dialog[open] .modal-footer [data-dialog-close]');
          await until("!document.querySelector('dialog[open]')");
        }
      }
    }
  }
  await writeFile(join(reports,'scope-modal-checks.json'),JSON.stringify(scopeModalChecks,null,2));
  base=modalHost;
  const groupCopyHost = base, groupCopyChecks = [];
  for (const [host, origin] of [groupCopyHost, process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    await call('Browser.grantPermissions', {origin:base,permissions:['clipboardReadWrite','clipboardSanitizedWrite']});
    for (const theme of ['dark','light']) {
      await call('Network.setCookie', {name:'git_ui_appearance',value:theme,url:base,path:'/'});
      for (const width of [1440,390]) {
        await call('Emulation.setDeviceMetricsOverride', {width,height:1000,deviceScaleFactor:1,mobile:width<768});
        await go('/system/security/groups'); await call('Page.bringToFront');
        await call('Input.dispatchMouseEvent', {type:'mouseMoved',x:1,y:1});
        const geometry = () => evaluate(`(() => {
          const row = document.querySelector('.group-name-copy'), name = row.querySelector('.group-name'), control = row.querySelector('.copy-control'), button = control.querySelector('button');
          const n = name.getBoundingClientRect(), c = control.getBoundingClientRect(), b = button.getBoundingClientRect(), style = getComputedStyle(button);
          return {gap:c.left-n.right,width:b.width,height:b.height,controlWidth:c.width,nameLeft:n.left,
            background:style.backgroundColor,border:style.borderTopColor,iconColor:getComputedStyle(control.querySelector('.copy-icon')).color,nameColor:getComputedStyle(name).color,label:button.getAttribute('aria-label'),value:control.dataset.copyValue};
        })()`);
        assert.deepEqual(await evaluate("[...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())"),['Name','Description','Type','Actions'],'Groups use the source column meanings');
        const filterLayout=await evaluate(`(() => {const form=document.querySelector('.system-list-filters'),select=form.querySelector('.select-trigger'),search=form.querySelector('.search-input'),submit=form.querySelector('button[type=submit]'),rect=element=>{const r=element.getBoundingClientRect();return {top:r.top,bottom:r.bottom,height:r.height};};return {select:rect(select),search:rect(search),submit:rect(submit),labels:[...form.querySelectorAll('.field > span')].map(element=>element.getBoundingClientRect().top),radius:[select,search,submit].map(element=>getComputedStyle(element).borderRadius)};})()`);
        assert.equal(filterLayout.select.height,filterLayout.search.height,'Group search and select heights match');
        assert.equal(filterLayout.select.height,filterLayout.submit.height,'Group filter button matches the controls');
        assert.equal(filterLayout.search.bottom,filterLayout.submit.bottom,'Group search and submit align');
        if(width>=768)assert.equal(filterLayout.labels[0],filterLayout.labels[1],'Group filter labels align');
        assert.equal(filterLayout.radius[0],filterLayout.radius[1],'Group search uses the same control corners');
        assert.ok(await evaluate("document.querySelector('input[type=search][name=search]') && [...document.querySelectorAll('tbody .badge')].every(badge=>badge.textContent.trim()!=='SYSTEM'||badge.dataset.tone==='accent')"),'Groups use the design search control and System type accent');
        const before = await geometry();
        assert.equal(before.gap,8,'Group name has an explicit copy-action gap');
        assert.equal(before.width,24); assert.equal(before.height,24); assert.equal(before.controlWidth,24,'No visible status-message space');
        assert.equal(before.background,'rgba(0, 0, 0, 0)'); assert.equal(before.border,'rgba(0, 0, 0, 0)');
        assert.equal(before.label,'Copy group ID');
        assert.notEqual(before.iconColor,before.nameColor,'The copy icon uses the muted foreground');
        const shot = await call('Page.captureScreenshot', {format:'png'});
        await writeFile(join(reports, `groups-copy-${host}-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
        await click('.group-name-copy .copy-control button');
        await until("document.querySelector('.group-name-copy .copy-control button').getAttribute('aria-label')==='Copied'");
        assert.equal(await evaluate('navigator.clipboard.readText()'),before.value);
        const after = await geometry();
        assert.equal(after.controlWidth,before.controlWidth,'Copy feedback does not move group names');
        assert.equal(after.nameLeft,before.nameLeft);
        groupCopyChecks.push({host,theme,viewport:width,...before,afterWidth:after.controlWidth});
      }
    }
  }
  await writeFile(join(reports,'groups-copy-checks.json'),JSON.stringify(groupCopyChecks,null,2));
  base = groupCopyHost;
  await call('Network.setCookie', {name:'git_ui_appearance',value:'dark',url:base,path:'/'});
  await call('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  const principalHost = base, principalFilterChecks = [];
  for (const [host, origin] of [principalHost, process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    assert.ok(origin && ['localhost','127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    for (const theme of ['dark','light']) for (const width of [1440,1024,768,390]) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await go('/system/security/principals');
      const layout=await evaluate(`(() => {
        const form=document.querySelector('.principal-filters'), search=form.querySelector('.search-input'), submit=form.querySelector('button[type=submit]'), checkbox=form.querySelector('.checkbox-row');
        const rect=element=>{const r=element.getBoundingClientRect(),s=getComputedStyle(element);return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,height:r.height,center:r.top+r.height/2,radius:s.borderRadius,background:s.backgroundColor};};
        return {search:rect(search),submit:rect(submit),checkbox:rect(checkbox),scrollWidth:document.documentElement.scrollWidth};
      })()`);
      assert.equal(layout.search.height,32,'Principal search uses the standard control height');
      assert.equal(layout.search.height,layout.submit.height,'Principal search and button heights match');
      assert.equal(layout.search.radius,layout.submit.radius,'Principal search and button corners match');
      assert.equal(layout.search.background,layout.submit.background,'Principal search and button surfaces match');
      assert.ok(Math.abs(layout.checkbox.center-layout.submit.center)<1,'Include deleted aligns with the Search button');
      if(width>=768) assert.equal(layout.search.top,layout.submit.top,'Principal filters align on desktop');
      else assert.ok(layout.checkbox.top>=layout.search.bottom+11,'Principal filters wrap with the standard gap');
      assert.ok(layout.scrollWidth<=width+1,'Principal filters stay within the viewport');
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`principal-filters-${host}-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
      principalFilterChecks.push({host,theme,width,...layout});
    }
    await call('Network.setCookie',{name:'git_ui_appearance',value:'dark',url:base,path:'/'});
    await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
    await go('/system/security/principals?offset=25');
    await fill('input[name=q]', 'PRINCIPAL-PAGE-');
    await click('input[name=includeDeleted]'); await clickText('Search');
    await until("new URLSearchParams(location.search).get('q')==='PRINCIPAL-PAGE-' && !new URLSearchParams(location.search).has('offset') && document.querySelectorAll('tbody tr').length===25");
    assert.equal(await evaluate("document.querySelector('nav[aria-label=\"Principals pages\"] button:disabled')?.textContent.trim()"), 'Previous');
    await clickText('Next', 'nav[aria-label="Principals pages"] a');
    await until("new URLSearchParams(location.search).get('offset')==='25' && document.querySelectorAll('tbody tr').length===1");
    assert.equal(await evaluate("document.querySelector('input[name=q]').value"), 'PRINCIPAL-PAGE-');
    assert.equal(await evaluate("document.querySelector('input[name=includeDeleted]').checked"), true);
    await clickText('Previous', 'nav[aria-label="Principals pages"] a');
    await until("new URLSearchParams(location.search).get('offset')==='0' && document.querySelectorAll('tbody tr').length===25");
    await click('input[name=includeDeleted]'); await clickText('Search');
    await until("!new URLSearchParams(location.search).has('includeDeleted') && !document.querySelector('nav[aria-label=\"Principals pages\"] a') && document.querySelectorAll('tbody tr').length===25");
    await fill('input[name=q]', 'no-such-principal%_'); await clickText('Search');
    await until("document.body.innerText.includes('No principals match this search.')");
    assert.equal(await evaluate("document.querySelectorAll('nav[aria-label=\"Principals pages\"] button:disabled').length"), 2);
  }
  await writeFile(join(reports,'principal-filter-checks.json'),JSON.stringify(principalFilterChecks,null,2));
  base = principalHost;
  await go('/system/security/personas'); await clickText('New persona');
  await until("document.querySelector('dialog[open] input[name=name]')");
  await fill('dialog[open] input[name=name]', 'Browser security persona');
  await fill('dialog[open] textarea[name=description]', 'Created through native design controls');
  await clickText('Select all subsystems');
  await until("document.querySelector('dialog[open] select[name=subsystemIds]') && [...document.querySelector('dialog[open] select[name=subsystemIds]').options].every(option=>option.selected)");
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=name]').value"),'Browser security persona','Select all retains the name');
  assert.equal(await evaluate("document.querySelector('dialog[open] textarea[name=description]').value"),'Created through native design controls','Select all retains the description');
  const unselect = await evaluate("[...document.querySelector('dialog[open] select[name=subsystemIds]').options].filter(option=>!['Git','System'].includes(option.textContent)).map(option=>option.textContent)");
  await click('dialog[open] [data-bml-design-select][data-name=subsystemIds] button[role=combobox]');
  for(const label of unselect) await clickText(label,'[role=option]');
  await escape();
  await click('dialog[open] button[type=submit]');
  await until("location.pathname.startsWith('/system/security/personas/') && document.querySelector('h1')?.textContent === 'Browser security persona'", 'persona create and navigation');
  const personaPath = await evaluate('location.pathname');
  assert.deepEqual(await evaluate("[...document.querySelector('select[name=subsystemIds]').selectedOptions].map(option => option.value)"), ['git', 'system']);
  await fill('input[name=name]', 'Browser persona edited'); await clickText('Save changes');
  await until("document.querySelector('h1')?.textContent === 'Browser persona edited'");
  await go(`/system/security/principals/${process.env.ATLAS_SYSTEM_TEST_PRINCIPAL}`);
  const assignedProfile = await evaluate("document.querySelector('.principal-profile-link').getAttribute('href').split('/').pop()");
  await go(personaPath);
  await fill('input[name=name]', 'Retained persona draft');
  await clickText('Assign to profile');
  await until("document.querySelector('dialog[open] input[name=profileId]')");
  await fill('dialog[open] input[name=profileId]', 'invalid-profile');
  await clickText('Assign persona');
  await until("document.querySelector('dialog[open] .alert[data-tone=error]')");
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=profileId]').value"), 'invalid-profile', 'Rejected assignment retains its profile draft');
  assert.equal(await evaluate("document.querySelector('input[name=name]').value"), 'Retained persona draft', 'Rejected assignment retains the unsaved persona editor');
  await fill('dialog[open] input[name=profileId]', assignedProfile);
  await clickText('Assign persona');
  await until("!document.querySelector('dialog[open]') && document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent==='Persona assigned to profile.'");
  assert.equal(await evaluate("document.querySelector('input[name=name]').value"), 'Retained persona draft', 'Assignment retains the unsaved persona editor');
  assert.equal(await evaluate("document.activeElement?.textContent.trim()"), 'Assign to profile', 'Assignment returns focus to its header action');

  const embedded = base;
  for (const origin of [embedded, process.env.ATLAS_SYSTEM_STANDALONE_URL]) {
    assert.ok(origin && ['localhost', '127.0.0.1'].includes(new URL(origin).hostname)); base = origin;
    await go('/system/security/tokens');
    await until("document.querySelector('[data-bml-design-select][data-name=owner] button[role=combobox]')");
    await click('[data-bml-design-select][data-name=owner] button[role=combobox]');
    await fill('input[aria-label="Search filter by owner"]', 'Security Reader');
    await until("[...document.querySelectorAll('[role=option]')].some(option=>option.textContent==='Security Reader')");
    await clickText('Security Reader', '[role=option]');
    await until(`location.search.includes(${JSON.stringify(process.env.ATLAS_SYSTEM_TEST_PRINCIPAL)})&&document.readyState==='complete'&&document.body.innerText.includes('Reader token')`);
    await until("document.querySelector('[data-bml-design-select][data-name=owner] button[role=combobox]')?.textContent.includes('Security Reader')");
    await click('[data-bml-design-select][data-name=owner] button[role=combobox]'); await clickText('Current account', '[role=option]');
    await until("location.search.includes('__self__')&&document.readyState==='complete'&&!document.body.innerText.includes('Reader token')");
  }
  base = embedded;
  await go('/system/security/tokens'); await clickText('Create token');
  await until("document.querySelector('dialog[open] input[name=name]')");
  await fill('dialog[open] input[name=name]', 'Browser security token');
  await until("document.querySelector('dialog[open] [data-name=selectedOwner] button[role=combobox]')");
  await click('dialog[open] [data-name=selectedOwner] button[role=combobox]'); await fill('input[aria-label="Search owner"]', 'Security Reader');
  await until("[...document.querySelectorAll('[role=option]')].some(option=>option.textContent==='Security Reader')"); await clickText('Security Reader', '[role=option]');
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=name]').value"), 'Browser security token', 'Owner search retains the token draft');
  await click('dialog[open] [data-name=selectedOwner] button[role=combobox]'); await clickText('Current account', '[role=option]');
  await clickText('Configure scopes');await until("document.querySelector('[data-token-scope-builder] > dialog[open]')");
  await click('dialog[open] [data-bml-design-select][data-name=selectedScopes] button[role=combobox]');
  for (const prefix of ['content:view', 'git:read']) {
    const label = await evaluate(`[...document.querySelectorAll('[role=option]')].find(item => item.textContent.startsWith(${JSON.stringify(prefix + ' —')}))?.textContent`);
    assert.ok(label, prefix); await clickText(label, '[role=option]');
  }
  await escape();
  const expiryDraft=await evaluate("document.querySelector('dialog[open] input[name=expires]').value");
  await clickText('Load registry targets','dialog[open] button');
  await until("document.querySelector('dialog[open] input[name=name]')&&JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-tokens.model\"]').textContent).scopeResourcesLoaded");
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=name]').value"),'Browser security token');
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=expires]').value"),expiryDraft,'Loading scope targets retains the expiration draft');
  assert.equal(await evaluate("document.querySelector('dialog[open] select[name=selectedScopes]').selectedOptions.length"),2);
  await clickText('Done','[data-token-scope-builder] button');await until("!document.querySelector('[data-token-scope-builder] > dialog[open]') && document.activeElement?.hasAttribute('data-token-scopes-trigger')");
  await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Copy your new token')", 'token creation and one-time value');
  const rawToken = await evaluate("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-tokens.model\"]').textContent).rawToken");
  assert.ok(rawToken.startsWith('bsk_'));
  const tokenPath = await evaluate("[...document.querySelectorAll('a')].find(item => item.textContent === 'Browser security token').getAttribute('href')");
  await go('/system/security/tokens'); assert.equal(await evaluate(`document.documentElement.outerHTML.includes(${JSON.stringify(rawToken)})`), false, 'raw tokens are not available after refresh');
  await go(tokenPath);
  await clickText('Edit'); await until("document.querySelector('[data-token-editor] dialog[open]')");
  assert.equal(await evaluate("document.querySelector('select[name=selectedScopes]').selectedOptions.length"), 2);
  await fill('input[name=name]', 'Scoped token draft'); await fill('textarea[name=description]', 'Retained while browsing registry targets');
  await clickText('Edit scopes');await until("document.querySelector('[data-token-scope-builder] > dialog[open]')");await clickText('Load registry targets');
  const tokenModel="JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-tokens.model\"]').textContent)";
  await until(`${tokenModel}.scopeResourcesLoaded`);
  const pickScope=async(name,label)=>{await until(`document.querySelector('[data-name=${name}] button[role=combobox]')`);await click(`[data-name=${name}] button[role=combobox]`);await clickText(label,'[role=option]');};
  await pickScope('scopeType','raw'); await clickText('Update target choices'); await until(`${tokenModel}.scopeType==='raw'`);
  assert.equal(await evaluate("[...document.querySelector('select[name=scopePath]').options].some(option=>option.value==='token-scope/package')"),false,'Registry type filters target choices');
  await pickScope('scopePath','token-scope/tool'); await clickText('Update target choices'); await until(`${tokenModel}.scopePath==='token-scope/tool'`);
  await pickScope('scopeVersion','1.0'); await pickScope('scopeActions','Admin'); await escape(); await clickText('Add scope rule');
  const artifactScope='artifacts:raw:token-scope/tool:1.0:pull,admin';
  await until(`${tokenModel}.draft.scopes.includes(${JSON.stringify(artifactScope)})`);
  assert.equal(await evaluate("document.querySelector('input[name=name]').value"),'Scoped token draft');
  assert.equal(await evaluate("document.querySelector('textarea[name=description]').value"),'Retained while browsing registry targets');
  await clickText('Done','[data-token-scope-builder] button');await until("!document.querySelector('[data-token-scope-builder] > dialog[open]') && document.querySelector('.token-scope-tags')?.textContent.includes('artifacts:raw:token-scope/tool:1.0:pull,admin')");
  await clickText('Save token'); await until(`${tokenModel}.token.scopes.includes(${JSON.stringify(artifactScope)})`);
  await until("!document.querySelector('[data-token-editor] dialog[open]')");
  await clickText('Edit'); await until("document.querySelector('[data-token-editor] dialog[open]')");
  await clickText('Edit scopes');await until("document.querySelector('[data-token-scope-builder] > dialog[open]')");
  await click('[data-bml-design-select][data-name=selectedScopes] button[role=combobox]');
  for (const prefix of ['content:view', 'git:read']) {
    const label = await evaluate(`[...document.querySelectorAll('[role=option]')].find(item => item.textContent.startsWith(${JSON.stringify(prefix + ' —')}))?.textContent`);
    await clickText(label, '[role=option]');
  }
  await clickText(artifactScope,'[role=option]');
  await escape(); assert.equal(await evaluate("document.querySelector('select[name=selectedScopes]').selectedOptions.length"),0);
  await clickText('Done','[data-token-scope-builder] button');await until("!document.querySelector('[data-token-scope-builder] > dialog[open]') && document.querySelector('.token-scope-summary')?.textContent.includes('Unrestricted')");
  await clickText('Save token');
  await until(`${tokenModel}.token.scopes===null&&!document.querySelector('[data-bml-action-pending]')`);
  assert.equal(await evaluate("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-tokens.model\"]').textContent).token.scopes"), null, 'empty selections clear scope restrictions');
  const principalPath = `/system/security/principals/${process.env.ATLAS_SYSTEM_TEST_PRINCIPAL}`;
  await go(principalPath); await clickText('Mark deleted');
  await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Principal marked deleted.')");
  await clickText('Restore principal');
  await until("document.body.innerText.includes('Principal restored.')");
  await go('/system/config'); await clickText('New entry');
  await until("document.querySelector('dialog[open] [data-bml-json-editor][data-json-ready] .cm-content[contenteditable=true]')");
  await fill('input[name=key]', 'browser.configuration');
  await fill('input[name=description]', 'Browser configuration');
  const fillJson = async (value, root = "[data-bml-json-editor]") => {
    if(!await evaluate(`Boolean(document.querySelector(${JSON.stringify(root+" .cm-content")}))`)) {
      await click(root+' button[title^="Switch to text"]');
      await until(`document.querySelector(${JSON.stringify(root+" .cm-content")})`);
    }
    await click(root + " .cm-content");
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: process.platform === 'darwin' ? 4 : 2, windowsVirtualKeyCode: 65, commands: ['selectAll'] });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65, commands: ['selectAll'] });
    await call('Input.insertText', { text: value });
  };
  const configurationSecret='browser-configuration-secret-only-in-editor';
  const configurationState="JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-configurations.model\"]').textContent)";
  const checkConfigurationList=async () => {
    assert.equal(await evaluate(`document.documentElement.outerHTML.includes(${JSON.stringify(configurationSecret)})`),false,'Closed configuration pages do not contain the secret in HTML or live state');
    assert.ok(await evaluate(`${configurationState}.entries.every(entry=>!Object.hasOwn(entry,'value'))`),'List state contains metadata only');
    assert.deepEqual(await evaluate("[...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())"),['Key','Description','Visibility','Actions'],'Configuration values have no list column');
    assert.equal(await evaluate("Boolean(document.querySelector('[data-bml-json-editor]'))"),false,'Closed editors have no hidden value fields');
  };
  await fillJson(JSON.stringify({nested:[true,3],name:'Created in browser',secret:configurationSecret}));
  await click('input[name=public]'); await clickText('Save configuration');
  await until("document.body.innerText.includes('Configuration saved.') && !document.querySelector('input[name=key]')");
  await checkConfigurationList();
  assert.equal(await evaluate("(() => {const name=[...document.querySelectorAll('[data-resource-name]')].find(name=>name.textContent==='browser.configuration');return Boolean(name&&!name.closest('td').querySelector('button,a,[role=button]'));})()"),true,'Configuration keys are plain table text');
  await click('[data-configuration-edit][aria-label="Edit browser.configuration"]');
  await until("document.querySelector('dialog[open] input[name=key][readonly]') && document.querySelector('dialog[open] [data-bml-json-editor][data-json-ready] .cm-content')");
  assert.equal(await evaluate("document.querySelector('input[name=public]').checked"), true);
  assert.deepEqual(await evaluate("JSON.parse(document.querySelector('textarea[name=value]').value)"), { nested: [true, 3], name: 'Created in browser', secret:configurationSecret });
  await fillJson('{invalid'); await clickText('Save configuration');
  await until("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-configurations.model\"]').textContent).feedback.failed === true");
  await until("document.querySelector('dialog[open] [data-configuration-editor-error]:popover-open')");
  assert.equal(await evaluate("document.querySelector('textarea[name=value]').value"), '{invalid', 'invalid JSON remains editable after a rejected save');
  await click('dialog[open] [data-configuration-editor-error] button[aria-label="Dismiss notification"]');
  assert.ok(await evaluate("Boolean(document.querySelector('dialog[open] input[name=key]'))"),'Dismissing modal validation feedback keeps the editor open');
  await fillJson(JSON.stringify([false,42,configurationSecret])); await fill('input[name=description]', 'Edited in browser');
  await clickText('Save configuration');
  await until("document.body.innerText.includes('Edited in browser') && !document.querySelector('input[name=key]')");
  await checkConfigurationList();
  const configurationHost=base, configurationModalChecks=[];
  for(const [host,origin] of [configurationHost,process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    base=origin;
    for(const theme of ['dark','light']) for(const width of [1920,1024,768,390]) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      await call('Emulation.setDeviceMetricsOverride',{width,height:width<600?660:1000,deviceScaleFactor:1,mobile:width<768});
      await go('/system/config');await checkConfigurationList();
      await click('[data-configuration-edit][aria-label="Edit browser.configuration"]');
      await until("document.querySelector('dialog[open] [data-bml-json-editor][data-json-ready] .cm-content')");
      assert.deepEqual(await evaluate("JSON.parse(document.querySelector('dialog[open] textarea[name=value]').value)"),[false,42,configurationSecret],'Edit loads the saved value on demand');
      const screenshot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`configuration-modal-${host}-${theme}-${width}.png`),Buffer.from(screenshot.data,'base64'));
      const dismiss=width===1920?'cancel':width===1024?'close':width===768?'escape':'backdrop';
      if(dismiss==='cancel')await clickText('Cancel','dialog[open] button');
      else if(dismiss==='close')await click('dialog[open] button[aria-label="Close dialog"]');
      else if(dismiss==='escape')await escape();
      else {
        const point=await evaluate("(() => {const r=document.querySelector('dialog[open]').getBoundingClientRect();return {x:r.left-5,y:r.top+5};})()");
        await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
        await call('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});
      }
      await until(`!document.querySelector('dialog[open]') && ${configurationState}.editing===false && !document.querySelector('[data-bml-json-editor]')`);
      await checkConfigurationList();
      configurationModalChecks.push({host,theme,width,dismiss,secretRemoved:true});
    }
  }
  await writeFile(join(reports,'configuration-modal-checks.json'),JSON.stringify(configurationModalChecks,null,2));
  base=configurationHost;
  await call('Network.setCookie',{name:'git_ui_appearance',value:'dark',url:base,path:'/'});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await go('/system/config');
  await fill('input[name=search]', 'no-such-config'); await clickText('Filter');
  await until("document.body.innerText.includes('No matching configurations.')");
  await go('/system/utilities'); await clickText('Clear cache');
  await until("document.body.innerText.includes('Server caches cleared.')");
  await clickText('Clear CDN cache');
  await until("document.body.innerText.includes('CDN is not configured')");
  await clickText('Expire all jobs'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Jobs expired.')");
  await clickText('Clear job locks'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Job locks released.')");
  await go('/system/storage'); await clickText('New storage system');
  await until("document.querySelector('dialog[open] [data-storage-editor] [data-json-ready] .cm-content')");
  await fill('dialog[open] input[name=name]', 'Browser storage');
  await fill('dialog[open] input[name=description]', 'Unsaved storage description');
  await fillJson('{"indexName":"browser-storage","contentIndex":false}', 'dialog[open] [data-bml-json-editor][data-name=configuration]');
  await clickText('Add model');
  await until("document.querySelector('dialog[open] [data-storage-model-row] [data-json-ready]')");
  assert.equal(await evaluate("document.querySelector('dialog[open] input[name=name]').value"), 'Browser storage', 'adding a model retains system fields');
  await click('dialog[open] [data-storage-model-row] [data-bml-design-select] button[role=combobox]');
  await clickText('browser-storage-embedding — Browser storage model', '[role=option]');
  await fillJson('{"dimension":16}', 'dialog[open] [data-storage-model-row] [data-bml-json-editor]');
  await clickText('Add model');
  await until("document.querySelectorAll('dialog[open] [data-storage-model-row]').length === 2");
  const removeExtra = await evaluate("(() => { const row = [...document.querySelectorAll('dialog[open] [data-storage-model-row]')].at(-1); row.querySelector('[data-storage-model-action]').dataset.removeExtra = ''; return '[data-remove-extra]'; })()");
  await click(removeExtra);
  await until("document.querySelectorAll('dialog[open] [data-storage-model-row]').length === 1");
  assert.equal(await evaluate("document.querySelector('dialog[open] [data-storage-model-row] select').value"), process.env.ATLAS_SYSTEM_TEST_MODEL_ID);
  await clickText('Save storage system');
  await until("document.body.innerText.includes('Storage system saved.') && !document.querySelector('dialog[open]')");
  assert.equal(await evaluate("(() => { const name = [...document.querySelectorAll('.storage-name')].find(cell => cell.textContent.trim() === 'Browser storage'); return Boolean(name && !name.querySelector('button,a,[role=button]')); })()"), true, 'Storage names are plain table text');
  await click('[data-storage-edit][aria-label="Edit Browser storage"]');
  await until("document.querySelector('dialog[open] [data-storage-model-row] [data-json-ready]')");
  assert.deepEqual(await evaluate("JSON.parse(document.querySelector('dialog[open] [data-storage-model-row] textarea').value)"), {dimension:16});
  await fill('dialog[open] input[name=description]', 'Edited storage description'); await clickText('Save storage system');
  await until("document.body.innerText.includes('Edited storage description') && !document.querySelector('dialog[open]')");
  await clickText('Reindex all'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Full reindex started.')"); await escape();
  await go('/system/search');
  await until("document.querySelector('[data-bml-json-editor][data-name=configuration][data-json-ready]')");
  await fillJson('{"expressions":{"metadata":"metadata"}}');
  await fill('input[name=metadataId]', process.env.ATLAS_SYSTEM_TEST_METADATA_ID); await clickText('Preview');
  await until("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-search.model\"]').textContent).previewed === true");
  assert.equal(await evaluate("JSON.parse(document.querySelector('[data-bml-json-editor][data-name=configuration] textarea').value).expressions.metadata"), 'metadata', 'preview retains unsaved search configuration');
  assert.equal(await evaluate("JSON.parse(document.querySelector('[data-bml-json-editor][data-name=preview] textarea').value).metadata.name"), 'Search preview fixture');
  await click('[data-search-save]'); await until("document.body.innerText.includes('Search configuration saved.')");
  for (const label of ['Reindex', 'Full reindex']) {
    await clickText(label); await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
    await until(`document.body.innerText.includes(${JSON.stringify(label === 'Reindex' ? 'Reindex started.' : 'Full reindex started.')})`); await escape();
  }
  const searchToastChecks=[];
  for(const [host,origin] of [modalHost,process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    base=origin;
    for(const theme of ['dark','light']) for(const width of [1920,1024,768,390]) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      await call('Emulation.setDeviceMetricsOverride',{width,height:width<600?660:1000,deviceScaleFactor:1,mobile:width<768});
      await go('/system/search');
      await until("document.querySelector('[data-bml-json-editor][data-name=configuration][data-json-ready]')");
      assert.equal(await evaluate("Boolean(document.querySelector('[data-system-success]:popover-open'))"),false,'Navigation does not replay success notices');
      await evaluate("document.querySelector('[data-search-save]').scrollIntoView({block:'center'})");
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      const geometry="[...document.querySelectorAll('.system-content > .ui-card')].map(card=>{const r=card.getBoundingClientRect();return {top:r.top,left:r.left,width:r.width,height:r.height};})";
      const before=await evaluate(geometry);assert.equal(before.length,2,'Measure both actual Search cards');
      assert.ok(await evaluate("(() => {const editor=document.querySelector('[data-bml-json-editor][data-name=configuration]');return editor.dataset.mode==='tree'&&editor.hasAttribute('data-main-menu')&&!editor.hasAttribute('data-navigation')&&editor.hasAttribute('data-status-bar')&&document.querySelector('[data-search-save]').closest('form')===editor.querySelector('textarea').form;})()"),'Search uses the source tree/menu options and a header save in the same native form');
      await click('[data-search-save]');
      await until("document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent==='Search configuration saved.' && !document.querySelector('[data-bml-action-pending]')");
      assert.deepEqual(await evaluate(geometry),before,'Success feedback does not move search cards');
      const toast=await evaluate("(() => {const notices=[...document.querySelectorAll('[data-system-success]:popover-open')],n=notices[0],r=n.getBoundingClientRect();return {count:notices.length,tone:n.dataset.tone,sequence:Number(n.dataset.sequence),position:getComputedStyle(n).position,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:innerWidth,height:innerHeight,inlineSuccess:document.querySelectorAll('.alert[data-tone=success]').length,tookFocus:n.contains(document.activeElement)};})()");
      assert.equal(toast.count,1);assert.equal(toast.tone,'success');assert.equal(toast.position,'fixed');assert.equal(toast.inlineSuccess,0);assert.equal(toast.tookFocus,false);
      assert.ok(toast.left>=0&&toast.right<=width&&toast.top>=0&&toast.bottom<=toast.height,JSON.stringify(toast));
      await evaluate("Promise.all(document.querySelector('[data-system-success]:popover-open').getAnimations({subtree:true}).map(animation=>animation.finished))");
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(reports,`search-toast-${host}-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
      searchToastChecks.push({host,theme,width,before,toast});
      await click('[data-system-success]:popover-open button[aria-label="Dismiss notification"]');
      await until("!document.querySelector('[data-system-success]:popover-open')");
      await click('[data-search-save]');
      await until(`Number(document.querySelector('[data-system-success]:popover-open')?.dataset.sequence)>${toast.sequence}`);
      await click('[data-system-success]:popover-open button[aria-label="Dismiss notification"]');
      await until("!document.querySelector('[data-system-success]:popover-open')");
      await fill('input[name=metadataId]',process.env.ATLAS_SYSTEM_TEST_METADATA_ID);await clickText('Preview');
      await until("JSON.parse(document.querySelector('script[data-bml-state-key^=\"system-search.model\"]').textContent).previewed && !document.querySelector('[data-bml-action-pending]')");
      assert.equal(await evaluate("Boolean(document.querySelector('[data-system-success]:popover-open'))"),false,'Unrelated preview does not replay dismissed success');
    }
  }
  await writeFile(join(reports,'search-toast-checks.json'),JSON.stringify(searchToastChecks,null,2));
  for(const [host,origin] of [modalHost,process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    base=origin;await go('/system/security/groups?search=Browser%20toast%20group');
    const name=`Browser toast group ${host}`;
    await clickText('New group');await until("document.querySelector('dialog[open] input[name=name]')");
    await fill('dialog[open] input[name=name]',name);await click('dialog[open] button[type=submit]');
    await until("!document.querySelector('dialog[open]') && document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent==='Group created.'");
    assert.equal(await evaluate("document.querySelectorAll('.alert[data-tone=success]').length"),0,'Group creation has no inline success message');
    const trigger=await evaluate(`(() => {const row=[...document.querySelectorAll('tbody tr')].find(row=>row.querySelector('.group-name')?.textContent===${JSON.stringify(name)});if(!row)throw Error('Missing scratch group');const button=[...row.querySelectorAll('button')].find(button=>button.textContent.trim()==='Delete');button.dataset.toastGroupDelete='';return '[data-toast-group-delete]';})()`);
    await click(trigger);await until("document.querySelector('dialog[open] input[name=confirmed]')");
    await click('dialog[open] input[name=confirmed]');await click('dialog[open] button[type=submit]');
    await until(`!document.querySelector('dialog[open]') && document.querySelector('[data-system-success]:popover-open .toast-message')?.textContent==='Action completed.' && ![...document.querySelectorAll('.group-name')].some(element=>element.textContent===${JSON.stringify(name)})`);
  }
  base=modalHost;
  const reportsData = [];
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 768 });
    for (const [name, path] of [['principals', '/system/security/principals'], ['principal', principalPath], ['personas', '/system/security/personas'], ['persona', personaPath], ['tokens', '/system/security/tokens'], ['token', tokenPath], ['configurations', '/system/config'], ['utilities', '/system/utilities'], ['storage', '/system/storage'], ['search', '/system/search']]) {
      await go(path);
      await until("document.querySelector('[data-bml-design-profile-menu]')");
      if (name === 'principal') {
        assert.ok(await evaluate("[...document.querySelectorAll('.principal-profile-link')].some(link=>link.getAttribute('href').startsWith('/audience/profiles/') && link.querySelector('[data-profile-name]')?.textContent==='Security Reader')"), 'Linked profiles retain the existing audience destination');
        assert.ok(await evaluate("[...document.querySelectorAll('.credential-identifier')].every(field=>field.scrollWidth<=field.clientWidth+1 && field.getBoundingClientRect().right<=field.closest('.ui-card').getBoundingClientRect().right)"), 'Long credential identifiers wrap within their card');
      }
      const bounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,title:document.querySelector("h1")?.textContent})');
      assert.ok(bounds.scrollWidth <= bounds.width + 2, `${name} at ${width}px overflows: ${JSON.stringify(bounds)}`);
      const screenshot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(reports, `${name}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
      reportsData.push({ name, width, ...bounds });
      if (name === 'token') {
        await clickText('Edit');
        await until("document.querySelector('[data-token-editor] dialog[open] input[name=name]')");
        const dialogBounds = await evaluate(`(() => {
          const dialog = document.querySelector('[data-token-editor] dialog[open]');
          const box = dialog.getBoundingClientRect(), footer = [...dialog.querySelectorAll('.modal-footer')].find(footer=>footer.closest('dialog')===dialog).getBoundingClientRect();
          return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:box.width,
            dialogTop:box.top,dialogBottom:box.bottom,footerTop:footer.top,footerBottom:footer.bottom,height:innerHeight};
        })()`);
        assert.ok(dialogBounds.scrollWidth <= width + 2 && dialogBounds.dialogWidth <= width, 'Token editor stays within the viewport');
        assert.ok(dialogBounds.dialogTop >= 0 && dialogBounds.dialogBottom <= dialogBounds.height + 1, 'Token editor fits the viewport height');
        assert.ok(dialogBounds.footerTop >= dialogBounds.dialogTop && dialogBounds.footerBottom <= dialogBounds.dialogBottom, 'Token actions remain visible');
        const screenshot = await call('Page.captureScreenshot', {format:'png'});
        await writeFile(join(reports, `token-editor-${width}.png`), Buffer.from(screenshot.data, 'base64'));
        reportsData.push({name:'token-editor',width,...dialogBounds});
        await escape();
        await until("!document.querySelector('[data-token-editor] dialog[open]') && document.activeElement?.hasAttribute('data-token-edit-trigger')");
      }
      if (name === 'persona') {
        await clickText('Assign to profile');
        await until("document.querySelector('dialog[open] input[name=profileId]')");
        const shot = await call('Page.captureScreenshot', {format:'png'});
        await writeFile(join(reports, `persona-assignment-${width}.png`), Buffer.from(shot.data, 'base64'));
        await escape();
        await until("!document.querySelector('dialog[open]')");
      }
      if (name === 'storage') {
        await click('[data-storage-edit][aria-label="Edit Browser storage"]');
        await until("document.querySelector('dialog[open] [data-storage-model-row] [data-json-ready]')");
        const dialogBounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialogWidth:document.querySelector("dialog[open]").getBoundingClientRect().width})');
        assert.ok(dialogBounds.scrollWidth <= width + 2 && dialogBounds.dialogWidth <= width, 'Storage editor stays within the viewport');
        const screenshot = await call('Page.captureScreenshot', { format:'png' });
        await writeFile(join(reports, `storage-editor-${width}.png`), Buffer.from(screenshot.data, 'base64'));
        reportsData.push({name:'storage-editor',width,...dialogBounds}); await escape();
      }
      if (name === 'configurations') {
        await click('[data-configuration-edit][aria-label="Edit browser.configuration"]');
        await until("document.querySelector('dialog[open] [data-bml-json-editor][data-json-ready] .cm-content')");
        const editorBounds = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,title:document.querySelector("h1")?.textContent,editorWidth:document.querySelector("[data-bml-json-editor]").getBoundingClientRect().width})');
        assert.ok(editorBounds.scrollWidth <= editorBounds.width + 2, `Configuration editor at ${width}px overflows: ${JSON.stringify(editorBounds)}`);
        assert.ok(editorBounds.editorWidth <= editorBounds.width, 'The editor stays within the page');
        const screenshot = await call('Page.captureScreenshot', { format: 'png' });
        await writeFile(join(reports, `configuration-editor-${width}.png`), Buffer.from(screenshot.data, 'base64'));
        reportsData.push({ name: 'configuration-editor', width, ...editorBounds });
        await clickText('Cancel','dialog[open] button');
        await until(`!document.querySelector('dialog[open]') && ${configurationState}.editing===false`);
        await checkConfigurationList();
      }
    }
  }
  const storageLayoutChecks=[], storageLayoutHost=base;
  for(const [host,origin] of [storageLayoutHost,process.env.ATLAS_SYSTEM_STANDALONE_URL].entries()) {
    base=origin;
    for(const theme of ['dark','light']) for(const width of [1920,1024,768,390]) {
      await call('Network.setCookie',{name:'git_ui_appearance',value:theme,url:base,path:'/'});
      await call('Emulation.setDeviceMetricsOverride',{width,height:width<600?660:1000,deviceScaleFactor:1,mobile:width<768});
      await go('/system/storage');
      assert.equal(await evaluate("Boolean(document.querySelector('[data-storage-list] .card-header'))"),false,'Storage table has no redundant section title');
      assert.ok(await evaluate("[...document.querySelectorAll('[data-storage-list] tbody .badge')].every(badge=>badge.textContent.trim()!=='SEARCH'||badge.dataset.tone==='info')"),'Storage type badges match the source palette');
      await click('[data-storage-edit][aria-label="Edit Browser storage"]');
      await until("document.querySelector('dialog[open] [data-storage-model-row] [data-json-ready] .cm-content')");
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      const bounds=await evaluate(`(() => {const dialog=document.querySelector('dialog[open]'),row=dialog.querySelector('[data-storage-model-row]'),rect=e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};},editors=[...dialog.querySelectorAll('[data-bml-json-editor]')];return {width:innerWidth,dialog:rect(dialog),row:rect(row),model:rect(row.children[0]),configuration:rect(row.children[1]),editors:editors.map(e=>({name:e.dataset.name,height:e.querySelector('.json-surface').getBoundingClientRect().height,surface:rect(e.querySelector('.json-surface')),engine:rect(e.querySelector('.jse-main')),background:getComputedStyle(e.querySelector('.jse-main')).backgroundColor,colors:[...e.querySelectorAll('.cm-line span')].slice(0,4).map(span=>({color:getComputedStyle(span).color,classes:span.className,key:getComputedStyle(e.querySelector('.jse-text-mode')).getPropertyValue('--internal-key-color'),paletteKey:getComputedStyle(e).getPropertyValue('--jse-key-color'),foreground:getComputedStyle(e).getPropertyValue('--fg-0')})),status:e.hasAttribute('data-status-bar'),mainMenu:e.hasAttribute('data-main-menu'),navigation:e.hasAttribute('data-navigation'),format:e.hasAttribute('data-ask-format')})),scrollWidth:document.documentElement.scrollWidth};})()`);
      assert.ok(bounds.dialog.width<=600&&bounds.dialog.width<=width&&bounds.scrollWidth<=width,JSON.stringify(bounds));
      assert.ok(bounds.row.height<=(width<600?230:150),JSON.stringify(bounds));
      if(width>=600) assert.ok(Math.abs(bounds.model.top-bounds.configuration.top)<1&&bounds.configuration.left>=bounds.model.right,JSON.stringify(bounds));
      else assert.ok(bounds.configuration.top>=bounds.model.bottom,JSON.stringify(bounds));
      for(const editor of bounds.editors){assert.equal(editor.height,editor.name==='configuration'?112:76);assert.equal(editor.mainMenu,false);assert.equal(editor.navigation,false);assert.equal(editor.format,false);assert.equal(editor.status,false);assert.ok(editor.engine.top>=editor.surface.top&&editor.engine.bottom<=editor.surface.bottom+1&&editor.engine.right<=editor.surface.right+1,JSON.stringify(editor));for(const color of editor.colors)assert.ok(contrastRatio(color.color,editor.background)>=4.5,JSON.stringify({theme,editor:editor.name,background:editor.background,...color}));}
      const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(reports,`storage-layout-${host}-${theme}-${width}.png`),Buffer.from(shot.data,'base64'));
      storageLayoutChecks.push({host,theme,...bounds});
      await clickText('Cancel','dialog[open] button');await until("!document.querySelector('dialog[open]')");
    }
  }
  await writeFile(join(reports,'storage-layout-checks.json'),JSON.stringify(storageLayoutChecks,null,2));base=storageLayoutHost;
  await go('/system/storage');
  const storageDelete = await evaluate("(() => { const row = [...document.querySelectorAll('tbody tr')].find(row => row.textContent.includes('Browser storage')); const trigger = row.querySelector('button[aria-label=\"Delete storage system\"]'); trigger.dataset.storageDelete = ''; return '[data-storage-delete]'; })()");
  await click(storageDelete); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Storage system deleted.')");
  await go('/system/config');
  const deleteSelector = await evaluate("(() => { const row = [...document.querySelectorAll('tbody tr')].find(row => row.textContent.includes('browser.configuration')); const trigger = row.querySelector('[data-bml-design-dialog] button'); if (!trigger) throw new Error('Missing configuration delete'); trigger.dataset.configDelete = ''; return '[data-config-delete]'; })()");
  await click(deleteSelector); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Configuration deleted.') && ![...document.querySelectorAll('tbody tr')].some(row => row.textContent.includes('browser.configuration'))");
  await go(tokenPath); await clickText('Revoke token');
  await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("document.body.innerText.includes('Token revoked.')");
  assert.ok(await evaluate("[...document.querySelectorAll('.badge[data-tone=error]')].some(badge=>badge.textContent.trim()==='Revoked')"),'Revoked tokens use the source error tone');
  await clickText('Delete token'); await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("location.pathname === '/system/security/tokens'");
  await go(personaPath); await clickText('Delete persona');
  await until("document.querySelector('dialog[open] input[name=confirmed]')");
  await click('dialog[open] input[name=confirmed]'); await click('dialog[open] button[type=submit]');
  await until("location.pathname === '/system/security/personas'");
  assert.deepEqual(errors, []);
  await writeFile(join(reports, 'checks.json'), JSON.stringify(reportsData, null, 2));
  await writeFile(join(reports, 'modal-layout-checks.json'), JSON.stringify(modalChecks, null, 2));
  console.log('Security browser checks passed: persona CRUD controls, multiple subsystem/scope values, one-time token disclosure, unrestricted edits, configuration JSON editing/filter/delete, utility controls, storage/model editing, indexing preview, reindex controls, and twelve views at four widths.');
} finally {
  ws?.close(); chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit', resolve));
  await rm(profile, { recursive: true, force: true });
}
