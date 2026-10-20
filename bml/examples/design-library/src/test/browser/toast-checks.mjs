import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real notifications from caller-owned server actions, native popovers and retained component DOM. */
export async function checkToasts({ call, evaluate, until, base, mode, artifacts }) {
  const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const root=palette=>`[data-notice=${palette}]`;
  const message=palette=>`${root(palette)} [data-bml-ref=message]`;
  const dismiss=palette=>`${root(palette)} [data-bml-ref=dismiss]`;
  const shown=palette=>`document.querySelector('${root(palette)}')?.matches(':popover-open')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const focus=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  const escape=async()=>{
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
  };
  const notify=async(kind,palette='light')=>{
    const sequence=await evaluate(`Number(document.querySelector('${root(palette)}').dataset.sequence)`);
    await click(`[data-${kind}-notice=${palette}]`);
    await until(`(${shown(palette)}) && Number(document.querySelector('${root(palette)}').dataset.sequence)>${sequence}`);
  };
  const listeners=async()=>{
    const counts={};
    for(const expression of ['document','window']) {
      const object=await call('Runtime.evaluate',{expression});
      const result=await call('DOMDebugger.getEventListeners',{objectId:object.result.objectId});
      counts[expression]=result.listeners.filter(listener=>['visibilitychange','bml:update','keydown','resize','pagehide'].includes(listener.type)).length;
      await call('Runtime.releaseObject',{objectId:object.result.objectId});
    }
    return counts;
  };
  const instrumentation=await call('Page.addScriptToEvaluateOnNewDocument',{source:`
    window.fixtureNoticeObservers=new Set();
    const Observer=ResizeObserver;
    window.ResizeObserver=class extends Observer {
      observe(...args){super.observe(...args);window.fixtureNoticeObservers.add(this);}
      disconnect(){super.disconnect();window.fixtureNoticeObservers.delete(this);}
    };
    window.fixtureNoticeTimers=new Set();
    const schedule=window.setTimeout.bind(window),cancel=window.clearTimeout.bind(window);
    window.setTimeout=(callback,delay,...args)=>{
      if(typeof callback!=='function')return schedule(callback,delay,...args);
      const id=schedule(()=>{window.fixtureNoticeTimers.delete(id);callback(...args)},delay);
      if(delay===5000)window.fixtureNoticeTimers.add(id);
      return id;
    };
    window.clearTimeout=id=>{window.fixtureNoticeTimers.delete(id);cancel(id);};`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/notifications'});
  await until(`document.readyState==='complete' && document.querySelector('[data-success-notice=light]')?.dataset.bmlActionBound==='true' && document.querySelector('${dismiss('light')}') && !document.querySelector('${dismiss('light')}').hasAttribute('title')`);
  assert.equal(await evaluate('document.querySelectorAll(".toast-region:popover-open").length'),0,'Initial empty notifications remain hidden');
  assert.ok(await evaluate(`document.querySelector('${root('light')}').classList.contains('fixture-notice') && !document.querySelector('${root('light')}').hasAttribute('id')`),'The ID is a preservation key; classes/attrs target the root');
  const baseline=await listeners();
  const observers=await evaluate('window.fixtureNoticeObservers.size');
  const before=await evaluate('document.querySelector("[data-notice-focus=light]").getBoundingClientRect().top');
  await evaluate('document.querySelector("[data-notice-focus=light]").value="Unsent draft"');
  await focus('[data-notice-focus=light]');
  await notify('success');
  assert.ok(await evaluate('document.activeElement===document.querySelector("[data-notice-focus=light]")'),'A notice does not take focus from the caller');
  assert.equal(await evaluate('document.querySelector("[data-notice-focus=light]").value'),'Unsent draft');
  assert.equal(await evaluate('document.querySelector("[data-notice-focus=light]").getBoundingClientRect().top'),before,'The floating notice does not shift caller content');
  assert.deepEqual(await evaluate(`(() => { const node=document.querySelector('${message('light')}');return [node.textContent,node.getAttribute('role'),node.getAttribute('aria-live'),node.getAttribute('aria-atomic')]})()`),['Saved light','status','polite','true']);
  await notify('success','purple');
  const themes=await evaluate(`['light','purple'].map(palette=>{const root=document.querySelector('[data-notice='+palette+']');const box=root.getBoundingClientRect();
    return {background:getComputedStyle(root.querySelector('.toast')).backgroundColor,text:getComputedStyle(root.querySelector('.toast-message')).color,tone:getComputedStyle(root).color,owned:root.closest('[data-palette]')?.dataset.palette,left:box.left,right:box.right,top:box.top}})`);
  assert.deepEqual(themes.map(theme=>({background:theme.background,text:theme.text,tone:theme.tone,owned:theme.owned})),[
    {background:'rgb(255, 255, 255)',text:'rgb(30, 41, 59)',tone:'rgb(21, 128, 61)',owned:'light'},
    {background:'rgb(16, 19, 28)',text:'rgb(205, 210, 226)',tone:'rgb(52, 217, 154)',owned:'purple'}]);
  assert.ok(themes[0].right<themes[1].left && themes.every(theme=>Math.abs(theme.top-750)<1),'Consumer CSS places simultaneous themed notices at the default block offset');
  await evaluate('document.querySelector("[data-palette=light]").classList.add("alternate")');
  assert.deepEqual(await evaluate(`(() => {const root=document.querySelector('${root('light')}');return [getComputedStyle(root.querySelector('.toast')).backgroundColor,getComputedStyle(root.querySelector('.toast-message')).color,getComputedStyle(root).color]})()`),['rgb(239, 246, 255)','rgb(30, 58, 138)','rgb(37, 99, 235)']);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('${root('purple')} .toast')).backgroundColor`),'rgb(16, 19, 28)');
  await evaluate('document.querySelector("[data-palette=light]").classList.remove("alternate")');
  await pause(280);
  let shot=await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`notifications-${mode}-1440.png`),Buffer.from(shot.data,'base64'));
  for(const palette of ['light','purple']) {await click(dismiss(palette));await until(`!(${shown(palette)})`);}
  for(const tone of ['info','warning','error','success']) {
    await notify(tone);
    const state=await evaluate(`(() => {const root=document.querySelector('${root('light')}');const node=root.querySelector('.toast-message');
      return {tone:root.dataset.tone,role:node.getAttribute('role'),live:node.getAttribute('aria-live'),icons:[...root.querySelectorAll('[data-tone-icon]')].filter(icon=>getComputedStyle(icon).display!=='none').map(icon=>icon.dataset.toneIcon)} })()`);
    assert.deepEqual(state,{tone,role:tone==='error'?'alert':'status',live:tone==='error'?'assertive':'polite',icons:[tone]});
  }
  await evaluate(`window.fixtureNoticeRoot=document.querySelector('${root('light')}');window.fixtureNoticeAnnouncements=0;
    window.fixtureAnnouncerObserver=new MutationObserver(records=>window.fixtureNoticeAnnouncements+=records.length);
    window.fixtureAnnouncerObserver.observe(document.querySelector('${message('light')}'),{childList:true,characterData:true,attributes:true,attributeFilter:['role','aria-live']})`);
  await notify('timed');
  const announcements=await evaluate('window.fixtureNoticeAnnouncements');
  await pause(900);
  await click('[data-update-notice=light]');
  await until('document.querySelector("[data-notice-updates=light]").textContent.includes("Updates 1")');
  assert.ok(await evaluate(`window.fixtureNoticeRoot===document.querySelector('${root('light')}') && (${shown('light')})`),'An active notice survives an unrelated island replacement');
  assert.equal(await evaluate('window.fixtureNoticeAnnouncements'),announcements,'Unrelated updates do not announce the same notice again');
  await pause(1250);
  assert.equal(Boolean(await evaluate(shown('light'))),false,'Unrelated updates do not restart the expiry timer');
  await click('[data-repeat-notice=light]');
  await until('document.querySelector("[data-notice-updates=light]").textContent.includes("Updates 2")');
  assert.equal(Boolean(await evaluate(shown('light'))),false,'An unchanged sequence does not replay a notice');
  await click('[data-older-notice=light]');
  await until(`document.querySelector('${root('light')}').dataset.sequence==='0'`);
  assert.equal(Boolean(await evaluate(shown('light'))),false,'An older sequence does not replay a notice');
  await click('[data-empty-notice=light]');
  await until(`document.querySelector('${root('light')}').dataset.message===''`);
  assert.equal(Boolean(await evaluate(shown('light'))),false,'An empty message is not a new notification');
  await click('[data-fail-notice=light]');
  await until('document.querySelector("[data-notice-error=light]")?.textContent.includes("Save rejected")');
  assert.equal(Boolean(await evaluate(shown('light'))),false,'A failed caller action does not fabricate a success notice');
  await notify('success');
  await click(dismiss('light'));
  await until(`!(${shown('light')})`);
  const repeated=await evaluate('window.fixtureNoticeAnnouncements');
  await notify('success');
  assert.equal(await evaluate(`document.querySelector('${message('light')}').textContent`),'Saved light');
  assert.ok(await evaluate('window.fixtureNoticeAnnouncements')>repeated,'A new sequence can announce the same text again');
  await click(dismiss('light'));
  await until(`!(${shown('light')})`);
  await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:2,y:2});
  await notify('brief');
  const point=await evaluate(`(() => {const box=document.querySelector('${root('light')}').getBoundingClientRect();return {x:box.left+20,y:box.top+20}})()`);
  await call('Input.dispatchMouseEvent',{type:'mouseMoved',...point});
  assert.ok(await evaluate(`document.querySelector('${root('light')}').contains(document.elementFromPoint(${point.x},${point.y}))`),'The pointer hits the rendered notification');
  await click('[data-update-notice=light]');
  await until('document.querySelector("[data-notice-updates=light]").textContent.includes("Updates 5")');
  await pause(950);
  assert.ok(await evaluate(shown('light')),'Hover pauses expiry through unrelated island updates');
  await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:2,y:2});
  await until(`!(${shown('light')})`);
  await notify('brief');
  await focus(dismiss('light'));
  await click('[data-update-notice=light]');
  await until('document.querySelector("[data-notice-updates=light]").textContent.includes("Updates 6")');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${dismiss('light')}')`),'A focused notice retains focus through an unrelated patch');
  await pause(950);
  assert.ok(await evaluate(shown('light')),'Focus pauses expiry through island updates');
  await escape();
  await until(`!(${shown('light')})`);
  assert.ok(await evaluate('document.activeElement===document.querySelector("[data-notice-focus=light]")'),'Keyboard dismissal returns focus to the caller');
  await notify('brief');
  await focus(dismiss('light'));
  await focus('[data-notice-focus=light]');
  await until(`!(${shown('light')})`);
  assert.ok(await evaluate('document.activeElement===document.querySelector("[data-notice-focus=light]")'),'Leaving the notice resumes expiry without taking focus back');
  await notify('brief');
  await evaluate('window.fixtureNoticeHidden=true;Object.defineProperty(document,"hidden",{configurable:true,get:()=>window.fixtureNoticeHidden});document.dispatchEvent(new Event("visibilitychange"))');
  await pause(950);
  assert.ok(await evaluate(shown('light')),'Hidden documents pause expiry');
  await evaluate('window.fixtureNoticeHidden=false;document.dispatchEvent(new Event("visibilitychange"))');
  await until(`!(${shown('light')})`);
  await evaluate('delete document.hidden');
  await notify('brief');
  await pause(400);
  await notify('brief');
  await pause(500);
  assert.ok(await evaluate(shown('light')),'A new sequence restarts its own expiry time');
  await until(`!(${shown('light')})`);
  await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await notify('success');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('${root('light')} .toast')).animationName`),'none');
  await click(dismiss('light'));
  await until(`!(${shown('light')})`);
  await call('Emulation.setEmulatedMedia',{features:[]});
  for(const [width,height] of [[1440,1000],[390,640],[390,320]]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
    await notify('long');
    await pause(280);
    const geometry=await evaluate(`(() => {const root=document.querySelector('${root('light')}');const box=root.getBoundingClientRect();const text=root.querySelector('.toast-message');const close=root.querySelector('[data-bml-ref=dismiss]').getBoundingClientRect();
      return {left:box.left,right:box.right,top:box.top,bottom:box.bottom,viewport:innerHeight,width:document.documentElement.clientWidth,scrollable:text.scrollHeight>text.clientHeight,tabIndex:text.tabIndex,closeVisible:close.top>=box.top&&close.bottom<=box.bottom} })()`);
    assert.ok(geometry.left>=16 && geometry.right<=geometry.width-16 && geometry.top>=16 && geometry.bottom<=height-16 && geometry.closeVisible,JSON.stringify(geometry));
    assert.equal(geometry.scrollable,true,'Long message text scrolls while dismissal stays visible');
    assert.equal(geometry.tabIndex,0,'Overflowing text can receive keyboard focus');
    await focus(message('light'));
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'PageDown',code:'PageDown',windowsVirtualKeyCode:34});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'PageDown',code:'PageDown',windowsVirtualKeyCode:34});
    await until(`document.querySelector('${message('light')}').scrollTop>0`);
    shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`notification-long-${mode}-${width}-${height}.png`),Buffer.from(shot.data,'base64'));
    await escape();
    await until(`!(${shown('light')})`);
  }
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await evaluate('window.fixtureAnnouncerObserver.disconnect()');
  for(let repeat=0;repeat<2;repeat++) {
    await focus('[data-notice-focus=light]');
    await notify('info');
    assert.equal(await evaluate('window.fixtureNoticeTimers.size'),1,'One native expiry timer is active');
    await evaluate(`window.fixtureRemovedNotice=document.querySelector('${root('light')}')`);
    await click('[data-toggle-notice=light]');
    await until(`!document.querySelector('${root('light')}')`);
    assert.equal(await evaluate('window.fixtureNoticeTimers.size'),0,'Unmount cancels the expiry timer');
    assert.ok(await evaluate('!window.fixtureRemovedNotice.isConnected && !window.fixtureRemovedNotice.matches(":popover-open")'));
    await click('[data-toggle-notice=light]');
    await until(`document.querySelector('${root('light')}') && !document.querySelector('${dismiss('light')}').hasAttribute('title')`);
    assert.ok(await evaluate(`document.querySelector('${root('light')}').dataset.message==='Information for light'`),'Remount retains the caller message rather than clearing saved state');
    assert.equal(Boolean(await evaluate(shown('light'))),false,'A remounted toast treats the retained sequence as its baseline');
  }
  assert.deepEqual(await listeners(),baseline,'Notice disposal removes document/window listeners');
  assert.equal(await evaluate('window.fixtureNoticeObservers.size'),observers,'Notice disposal disconnects ResizeObservers');
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:instrumentation.identifier});
}
