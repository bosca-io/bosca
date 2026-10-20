import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real native navigation, overflow placement and lifecycle against the compiled library. */
export async function checkNavigation({ call, evaluate, until, base, mode, artifacts }) {
  const light='[data-routes=light]';
  const purple='[data-routes=purple]';
  const more=root=>`${root} [data-bml-ref=more]`;
  const menu=root=>`${root} [data-bml-ref=menu]`;
  const row=root=>`${root} [data-bml-ref=row]`;
  const open=root=>`document.querySelector(${JSON.stringify(menu(root))})?.matches(':popover-open')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async (value,modifiers=0)=>{
    const code={Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35}[value];
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,code:value,windowsVirtualKeyCode:code,modifiers,
      ...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
  };
  const listeners=async()=>{
    const counts={};
    for(const expression of ['document','window','document.fonts']) {
      const handle=await call('Runtime.evaluate',{expression});
      const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
      await call('Runtime.releaseObject',{objectId:handle.result.objectId});
      counts[expression]=result.listeners.filter(listener=>['pointerdown','resize','scroll','bml:update','loadingdone'].includes(listener.type)).length;
    }
    return counts;
  };
  const fit=root=>`(() => { const nav=document.querySelector(${JSON.stringify(root)}),row=nav.querySelector('[data-bml-ref=row]');
    const active=row.querySelector('[aria-current=page]'),more=row.querySelector('button'),r=row.getBoundingClientRect();
    return (!active||!active.hidden&&active.getBoundingClientRect().left>=r.left-1&&active.getBoundingClientRect().right<=r.right+1)&&
      (more.hidden||more.getBoundingClientRect().right<=r.right+1)&&nav.getBoundingClientRect().right<=innerWidth; })()`;
  const popupFit=root=>`(() => {const r=document.querySelector(${JSON.stringify(menu(root))}).getBoundingClientRect();return r.left>=11&&r.top>=11&&r.right<=innerWidth-11&&r.bottom<=innerHeight-11;})()`;
  const instrumentation=await call('Page.addScriptToEvaluateOnNewDocument',{source:`
    window.routeObservers=new Set();const Original=window.ResizeObserver;
    window.ResizeObserver=class extends Original {
      observe(...args){super.observe(...args);window.routeObservers.add(this);}
      disconnect(){super.disconnect();window.routeObservers.delete(this);}
    };`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/navigation'});
  await until(`document.readyState==='complete' && document.querySelector('[data-route-update=light]')?.dataset.bmlActionBound==='true' && document.querySelector('${light}')?.hasAttribute('data-tabs-enhanced') && document.querySelector('${more(light)}')?.hasAttribute('aria-controls')`);
  const baseline=await listeners();
  const observers=await evaluate('window.routeObservers.size');
  assert.equal(observers,7);
  assert.deepEqual(await evaluate(`(() => { const nav=document.querySelector('${light}'),links=[...nav.querySelectorAll('.tab-row > a')],more=nav.querySelector('button'),menu=nav.querySelector('[data-bml-ref=menu]');
    return {native:nav instanceof HTMLElement&&nav.tagName==='NAV'&&links.every(link=>link instanceof HTMLAnchorElement),label:nav.getAttribute('aria-label'),
      attrs:nav.classList.contains('fixture-tabs')&&!nav.hasAttribute('items')&&!nav.hasAttribute('header'),count:links.length,
      current:links.find(link=>link.getAttribute('aria-current')==='page').getAttribute('href'),
      overflow:links.some(link=>link.hidden)&&!more.hidden&&getComputedStyle(more).display!=='none',
      targets:[...menu.querySelectorAll('a')].map(link=>link.getAttribute('href')),hidden:menu.hidden&&getComputedStyle(menu).display==='none',
      controls:more.getAttribute('aria-controls')===menu.id,expanded:more.getAttribute('aria-expanded')}; })()`),
    {native:true,label:'Routes light 0',attrs:true,count:8,current:'/navigation-target?item=8',overflow:true,
      targets:await evaluate(`Array.from(document.querySelectorAll('${row(light)} > a[hidden]'),link=>link.getAttribute('href'))`),hidden:true,controls:true,expanded:'false'});
  assert.ok(await evaluate(`(() => {const menus=[...document.querySelectorAll('.tabs-menu')];return new Set(menus.map(menu=>menu.id)).size===7&&
    document.querySelector('[data-empty-routes] [data-bml-ref=more]').hidden&&document.querySelector('[data-short-routes] [data-bml-ref=more]').hidden&&
    [...document.querySelectorAll('[data-short-routes] .tab-row > a')].every(link=>!link.hidden);})()`));
  await until(fit(light));
  await evaluate(`document.querySelector('${more(light)}').focus()`);
  await key('Enter');
  await until(open(light));
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${menu(light)} > a') && document.querySelector('${more(light)}').getAttribute('aria-expanded')==='true'`));
  await until(popupFit(light));
  await key('Tab');
  assert.ok(await evaluate(`${open(light)}&&document.activeElement===document.querySelector('${menu(light)}').children[1]`),'Native Tab between overflow destinations retains the open menu');
  await key('Tab',8);
  assert.ok(await evaluate(`${open(light)}&&document.activeElement===document.querySelector('${menu(light)}').firstElementChild`),'Native Shift+Tab between overflow destinations retains the open menu');
  await key('End');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${menu(light)}').lastElementChild`));
  await key('ArrowDown');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${menu(light)}').firstElementChild`));
  await key('ArrowUp');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${menu(light)}').lastElementChild`));
  await key('Home');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${menu(light)}').firstElementChild`));
  await key('Escape');
  await until(`!(${open(light)})`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${more(light)}')`));
  await key('ArrowDown');
  await until(open(light));
  await key('End');
  await key('Tab');
  await until(`!(${open(light)})`);
  assert.ok(await evaluate(`document.activeElement!==document.querySelector('${more(light)}')&&!document.querySelector('${menu(light)}').contains(document.activeElement)`),'Overflow navigation does not trap Tab');
  await click(more(light));
  await evaluate(`document.querySelector('[data-route-update=purple]').focus();document.querySelector('[data-route-update=purple]').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))`);
  await until(`!(${open(light)})`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-route-update=purple]')`),'Outside dismissal keeps outside focus');
  await click(more(light));
  await evaluate(`window.routeRoot=document.querySelector('${light}');window.routeMenu=document.querySelector('${menu(light)}');window.routeEntry=document.activeElement;window.routeHref=document.activeElement.href`);
  await click('[data-route-update=light]');
  await until(`document.querySelector('[data-route-updates=light]').textContent.includes('Updates 1') && ${open(light)}`);
  assert.ok(await evaluate(`document.querySelector('${light}')===window.routeRoot&&document.querySelector('${menu(light)}')===window.routeMenu&&document.activeElement===window.routeEntry&&window.routeMenu.getAttribute('aria-label')==='Routes light 1 — More'`),'An unrelated action retains the native menu, entry focus and updated navigation label');
  await click('[data-route-action=light]');
  await until(`document.querySelector('[data-route-updates=light]').textContent.includes('Updates 2') && ${open(light)}`);
  assert.equal(await evaluate(`document.querySelector('[data-route-updates=purple]').textContent.trim()`),'Updates 0');
  await click('[data-route-resize=light]');
  await until(`document.querySelector('[data-route-host=light]').classList.contains('narrow') && ${fit(light)} && ${open(light)}`);
  assert.ok(await evaluate('document.activeElement.href===window.routeHref'),'Refitting overflow retains the chosen destination focus');
  await call('Emulation.setDeviceMetricsOverride',{width:2400,height:1000,deviceScaleFactor:1,mobile:false});
  await click('[data-route-resize=light]');
  await until(`document.querySelector('${more(light)}').hidden && !(${open(light)})`);
  assert.ok(await evaluate(`document.activeElement.href===window.routeHref&&document.querySelector('${row(light)}').contains(document.activeElement)`),'When links fit, focus follows the same native destination into the row');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await until(`!document.querySelector('${more(light)}').hidden`);
  await click('[data-route-change=light]');
  await until(`document.querySelector('${row(light)} > [aria-current=page]')?.getAttribute('href')==='/navigation-target?item=4' && document.querySelector('${more(light)}')?.hasAttribute('aria-controls')`);
  await click('[data-route-resize=light]');
  await until(`document.querySelector('[data-route-host=light]').classList.contains('narrow') && ${fit(light)}`);
  await click('[data-route-long=light]');
  await until(`document.querySelector('${row(light)} > [aria-current=page]')?.textContent.includes('A very long') && ${fit(light)}`);
  assert.ok(await evaluate(`(() => {const link=document.querySelector('${row(light)} > [aria-current=page]');const label=link.querySelector('.tab-label');return label.scrollWidth>label.clientWidth&&!link.hidden;})()`),'A long current label is truncated visually while its native destination and full name remain');
  await click(more(light));
  await until(open(light));
  await click(more(purple));
  await until(open(purple));
  assert.deepEqual(await evaluate(`['light','purple'].map(palette=>{const menu=document.querySelector('[data-routes='+palette+'] .tabs-menu');return [getComputedStyle(menu).backgroundColor,getComputedStyle(menu.querySelector('a')).color];})`),
    [['rgb(248, 250, 252)','rgb(30, 41, 59)'],['rgb(22, 26, 37)','rgb(205, 210, 226)']]);
  await click(more(light));
  await until(open(light));
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('alternate');document.querySelector('${menu(light)} > a').focus()`);
  assert.deepEqual(await evaluate(`(() => {const a=document.querySelector('${menu(light)} > a'),m=a.parentElement;return [getComputedStyle(m).backgroundColor,getComputedStyle(a).color,getComputedStyle(a).outlineColor,getComputedStyle(document.querySelector('${menu(purple)}')).backgroundColor]})()`),
    ['rgb(219, 234, 254)','rgb(30, 58, 138)','rgb(37, 99, 235)','rgb(22, 26, 37)']);
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await evaluate('window.scrollTo(0,0)');
    await until(fit(light));
    await until(fit('[data-header-routes]'));
    await until('document.documentElement.scrollWidth<=innerWidth');
    assert.deepEqual(await evaluate(`(() => {const header=document.querySelector('[data-header-routes]'),style=getComputedStyle(header);return [header.classList.contains('header-tabs'),style.marginLeft,style.paddingLeft]})()`),[true,width===390?'-16px':'-22px',width===390?'16px':'22px']);
    if(!await evaluate(open(light)))await click(more(light));
    await until(popupFit(light));
    await evaluate(`Promise.all(document.querySelector('${light} .tab-chevron').getAnimations().map(animation=>animation.finished))`);
    const shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`navigation-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  await key('Escape');
  const clip='[data-clipped-routes]';
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:320,deviceScaleFactor:1,mobile:false});
  await evaluate(`document.querySelector('${more(clip)}').scrollIntoView({block:'end'})`);
  await click(more(clip));
  await until(open(clip));
  await until(popupFit(clip));
  await evaluate(`document.querySelector('${menu(clip)} > a').focus()`);
  await key('End');
  assert.ok(await evaluate(`(() => {const menu=document.querySelector('${menu(clip)}'),last=menu.lastElementChild,r=menu.getBoundingClientRect(),a=last.getBoundingClientRect();return menu.scrollHeight>menu.clientHeight&&menu.scrollTop>0&&a.bottom<=r.bottom+1&&a.top>=r.top;})()`),'The clipped, short-screen popup scrolls to the focused destination');
  assert.ok(await evaluate(`(() => {const menu=document.querySelector('${menu(clip)}'),r=menu.getBoundingClientRect();return document.elementFromPoint(r.left+10,r.top+10)?.closest('.tabs-menu')===menu;})()`),'The native popover escapes the clipping ancestor');
  const shot=await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`navigation-short-${mode}.png`),Buffer.from(shot.data,'base64'));
  await key('Escape');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await evaluate('window.scrollTo(0,0)');
  await until(fit(light));
  await click(more(light));
  await until(open(light));
  await evaluate(`window.removedRouteRoot=document.querySelector('${light}');window.removedRouteMenu=document.querySelector('${menu(light)}');window.removedRouteTrigger=document.querySelector('${more(light)}')`);
  await click('[data-route-toggle=light]');
  await until(`!document.querySelector('${light}')&&window.routeObservers.size===${observers-1}`);
  assert.ok(await evaluate('!window.removedRouteMenu.matches(":popover-open")&&window.removedRouteMenu.hidden'));
  for(const expression of ['window.removedRouteRoot','window.removedRouteMenu','window.removedRouteTrigger']) {
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    assert.equal(result.listeners.length,0,'Unmount removes scoped root/trigger/popup listeners');
  }
  for(let cycle=0;cycle<3;cycle++) {
    await click('[data-route-toggle=light]');
    await until(`document.querySelector('${more(light)}')?.hasAttribute('aria-controls')&&window.routeObservers.size===${observers}`);
    assert.deepEqual(await listeners(),baseline,'Remount does not accumulate document, window or font listeners');
    if(cycle<2){await click('[data-route-toggle=light]');await until(`!document.querySelector('${light}')&&window.routeObservers.size===${observers-1}`);}
  }
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:instrumentation.identifier});
  await click(more(light));
  await until(open(light));
  const destination=await evaluate(`document.querySelector('${menu(light)} > a').getAttribute('href')`);
  await click(`${menu(light)} > a`);
  await until(`document.readyState==='complete'&&location.pathname==='/navigation-target'&&document.querySelector('[data-destination]')`);
  assert.equal(await evaluate('location.search'),new URL(destination,base).search,'Overflow items retain native link navigation');
  assert.match(await evaluate('document.querySelector("[data-destination]").textContent'),/Destination \d+/);
}
