import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Native route destinations, retained overflow and consumer-owned layout in both palettes. */
export async function checkRouteNavigation({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const node=(part,id='dark')=>`document.querySelector('[data-navigation-${part}=${id}]')`;
  const ref=(name,id='dark')=>`${node('tabs',id)}.querySelector('[data-bml-ref=${name}]')`;
  const open=id=>`${ref('menu',id)}.matches(':popover-open')`;
  const key=async (value,modifiers=0)=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,modifiers,windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35}[value],...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});};
  const action=async(name,done)=>{await evaluate(`window.navigationAction=${node(name)};window.navigationAction.click()`);await until(`!window.navigationAction.isConnected&&(${done})`);};
  const show=async(id='dark')=>{await evaluate(`${ref('more',id)}.scrollIntoView({block:'center'});${ref('more',id)}.click()`);await until(open(id));};
  const fit=id=>`(()=>{const root=${node('tabs',id)},row=${ref('row',id)},active=row.querySelector('[aria-current=page]'),button=${ref('more',id)},bounds=row.getBoundingClientRect();return (!active||!active.hidden&&active.getBoundingClientRect().right<=bounds.right+1&&active.getBoundingClientRect().left>=bounds.left-1)&&(button.hidden||button.getBoundingClientRect().right<=bounds.right+1)&&root.getBoundingClientRect().right<=innerWidth;})()`;
  const popupFit=expression=>`(()=>{const box=${expression}.getBoundingClientRect();return box.left>=11&&box.top>=11&&box.right<=innerWidth-11&&box.bottom<=innerHeight-11;})()`;
  const listeners=async()=>{
    const counts={};
    for(const expression of ['document','window','document.fonts']) {
      const handle=await call('Runtime.evaluate',{expression});const found=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
      await call('Runtime.releaseObject',{objectId:handle.result.objectId});
      counts[expression]=found.listeners.filter(item=>['pointerdown','resize','scroll','bml:update','loadingdone'].includes(item.type)).length;
    }
    return counts;
  };
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-navigation-${name}.png`),Buffer.from(capture.data,'base64'));};
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  const typography=[];
  for(const path of ['/components','/selection']) {
    await navigate(path);await evaluate('document.fonts.ready');
    typography.push(await evaluate(`(()=>{const style=selector=>{const css=getComputedStyle(document.querySelector(selector));return {fontFamily:css.fontFamily,fontSize:css.fontSize,lineHeight:css.lineHeight,fontWeight:css.fontWeight,color:css.color};};return {description:style('.catalog-title p'),intro:style('.catalog-sections > p')};})()`));
  }
  assert.deepEqual(typography[0],typography[1],'Catalog page descriptions and intros share their computed typography');
  assert.equal(typography[0].intro.fontSize,'14px');
  const probe=await call('Page.addScriptToEvaluateOnNewDocument',{source:`window.navigationObservers=new Set();const NativeResizeObserver=window.ResizeObserver;window.ResizeObserver=class extends NativeResizeObserver {observe(...args){super.observe(...args);window.navigationObservers.add(this);}disconnect(){super.disconnect();window.navigationObservers.delete(this);}};`});
  await navigate('/navigation');await until(`${node('refresh')}?.dataset.bmlActionBound==='true'&&${node('tabs')}.hasAttribute('data-tabs-enhanced')`);await evaluate('document.fonts.ready');
  const baseline=await listeners(),observers=await evaluate('window.navigationObservers.size');
  assert.ok(await truth(`${node('tabs')}.tagName==='NAV'&&${ref('row')}.querySelectorAll(':scope > a').length===8&&!${ref('more')}.hidden&&${ref('more')}.getAttribute('aria-controls')===${ref('menu')}.id&&${ref('menu')}.hidden&&${ref('row')}.querySelector('[aria-current=page]').getAttribute('href')==='/navigation'`));
  assert.ok(await truth(`document.querySelector('[data-navigation-empty] [data-bml-ref=more]').hidden&&!document.querySelector('[data-navigation-empty] a')&&document.querySelector('[data-navigation-short] [data-bml-ref=more]').hidden&&!document.querySelector('[data-navigation-unselected] [aria-current=page]')`));
  const allMenus=await evaluate(`[...document.querySelectorAll('.tabs-menu')].map(menu=>menu.id)`);assert.equal(new Set(allMenus).size,allMenus.length);
  await until(fit('dark'));
  await evaluate(`${ref('more')}.focus()`);await key('Enter');await until(open('dark'));await until(popupFit(ref('menu')));
  assert.ok(await truth(`document.activeElement===${ref('menu')}.firstElementChild`));
  await key('Tab');assert.ok(await truth(`document.activeElement===${ref('menu')}.children[1]&&${open('dark')}`));
  await key('Tab',8);assert.ok(await truth(`document.activeElement===${ref('menu')}.firstElementChild&&${open('dark')}`));
  for(const [value,last] of [['End',true],['ArrowDown',false],['ArrowUp',true],['Home',false]]) {
    await key(value);assert.ok(await truth(`document.activeElement===${ref('menu')}.${last?'lastElementChild':'firstElementChild'}`));
  }
  await key('Escape');await until(`!${open('dark')}`);assert.ok(await truth(`document.activeElement===${ref('more')}`));
  await key('ArrowDown');await until(open('dark'));await key('End');await key('Tab');await until(`!${open('dark')}`);
  assert.ok(await truth(`!${ref('menu')}.contains(document.activeElement)&&document.activeElement!==${ref('more')}`),'Tab can leave overflow navigation');
  await show();await evaluate(`${node('action','light')}.focus();${node('action','light')}.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))`);await until(`!${open('dark')}`);
  assert.ok(await truth(`document.activeElement===${node('action','light')}`));
  await show();await evaluate(`window.navigationRoot=${node('tabs')};window.navigationMenu=${ref('menu')};window.navigationEntry=document.activeElement;window.navigationHref=document.activeElement.href;document.querySelector('[data-catalog-navigation=dark] details').open=true`);
  await action('refresh',`${node('toast')}.matches(':popover-open')&&${open('dark')}`);
  assert.ok(await truth(`${node('tabs')}===window.navigationRoot&&${ref('menu')}===window.navigationMenu&&document.activeElement===window.navigationEntry&&${node('toast','light')}.dataset.sequence==='0'`));
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseNavigationRefresh=resolve;});}return original(...args);};window.navigationButtonSize=${node('action')}.getBoundingClientRect().toJSON();${node('action')}.click()})()`);
  await until(`typeof window.releaseNavigationRefresh==='function'&&${node('action')}.disabled`);
  assert.ok(await truth(`(()=>{const box=${node('action')}.getBoundingClientRect();return box.height===window.navigationButtonSize.height&&box.width===window.navigationButtonSize.width;})()`));
  await evaluate('window.releaseNavigationRefresh()');await until(`${node('toast')}.dataset.sequence==='2'&&!${node('action')}.disabled&&${open('dark')}`);
  await action('narrow',`!${node('host')}.hasAttribute('data-narrow')&&${ref('more')}.hidden`);
  assert.ok(await truth(`${node('tabs')}===window.navigationRoot&&!${open('dark')}&&document.activeElement.href===window.navigationHref&&${ref('row')}.contains(document.activeElement)`));
  await action('narrow',`${node('host')}.hasAttribute('data-narrow')&&!${ref('more')}.hidden`);await until(fit('dark'));
  await action('current',`${ref('row')}.querySelector('[aria-current=page]').getAttribute('href')==='/components/layout'`);
  assert.ok(await truth(`${node('tabs')}!==window.navigationRoot&&!window.navigationRoot.isConnected&&!window.navigationMenu.matches(':popover-open')`));
  await action('long',`${ref('row')}.querySelector('[aria-current=page]').textContent.includes('long current')`);await until(fit('dark'));
  assert.ok(await truth(`(()=>{const label=${ref('row')}.querySelector('[aria-current=page] .tab-label');return label.scrollWidth>label.clientWidth;})()`));
  await action('replace',`${ref('row')}.querySelectorAll(':scope > a').length===4`);
  assert.equal(await evaluate(`${ref('row','light')}.querySelectorAll(':scope > a').length`),8);
  await show();await evaluate(`window.removedNavigation=${node('tabs')};window.removedNavigationMenu=${ref('menu')};window.removedNavigationTrigger=${ref('more')}`);
  await action('visible',`!${node('tabs')}&&window.navigationObservers.size===${observers-1}`);
  assert.ok(await truth(`window.removedNavigationMenu.hidden&&!window.removedNavigationMenu.matches(':popover-open')`));
  for(const expression of ['window.removedNavigation','window.removedNavigationMenu','window.removedNavigationTrigger']) {
    const handle=await call('Runtime.evaluate',{expression});const found=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});await call('Runtime.releaseObject',{objectId:handle.result.objectId});assert.equal(found.listeners.length,0);
  }
  for(let cycle=0;cycle<3;cycle++) {
    await action('visible',`${node('tabs')}?.hasAttribute('data-tabs-enhanced')&&window.navigationObservers.size===${observers}`);
    assert.deepEqual(await listeners(),baseline);
    if(cycle<2)await action('visible',`!${node('tabs')}`);
  }
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/navigation');await until(`${node('tabs')}.hasAttribute('data-tabs-enhanced')`);
    await evaluate(`document.fonts.ready;if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click();document.querySelectorAll('[data-example-states]').forEach(details=>details.open=false)`);
    await evaluate(`Promise.all(document.getAnimations().filter(animation=>animation instanceof CSSTransition).map(animation=>animation.finished)).then(()=>true)`);
    for(const id of ['dark','light'])await until(fit(id));
    const geometry=await evaluate(`(()=>{const groups=[...document.querySelectorAll('[data-catalog-navigation]')].map(owner=>{const host=owner.querySelector('[data-navigation-host]'),note=owner.querySelector('.example-note');return {id:owner.dataset.catalogNavigation,gap:note.getBoundingClientRect().top-host.getBoundingClientRect().bottom};});const style=getComputedStyle(document.querySelector('[data-navigation-header]'));return {groups,overflow:document.documentElement.scrollWidth-innerWidth,header:{margin:style.marginLeft,padding:style.paddingLeft}};})()`);
    assert.ok(geometry.overflow<=0&&geometry.groups.every(group=>group.gap===16),JSON.stringify(geometry));
    assert.deepEqual(geometry.header,{margin:width===390?'-16px':'-22px',padding:width===390?'16px':'22px'});
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-navigation-palette] .tab-row a:not([hidden]), [data-navigation-palette] .more, [data-navigation-palette] .example-note, [data-navigation-cases] .tab-row a:not([hidden]), [data-navigation-cases] .more, [data-navigation-cases] .example-note, [data-navigation-cases] .catalog-description')`);
    for(const sample of colors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    await evaluate(`document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);await shot(`page-${width}`);
    const menus=[];
    for(const id of ['dark','light']) {
      await show(id);await until(popupFit(ref('menu',id)));
      const menuColors=await evaluate(`(${contrastSamples.toString()})('[data-navigation-tabs=${id}] .tabs-menu a')`);for(const sample of menuColors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      await evaluate(`Promise.all(${ref('more',id)}.querySelector('.tab-chevron').getAnimations().map(animation=>animation.finished)).then(()=>true)`);
      menus.push({id,background:await evaluate(`getComputedStyle(${ref('menu',id)}).backgroundColor`),colors:menuColors.text});await shot(`menu-${id}-${width}`);await key('Escape');
    }
    assert.notEqual(menus[0].background,menus[1].background);
    for(const index of width===390?[0,1,2,3,4,5]:[0,2,4]) {await evaluate(`document.querySelectorAll('[data-navigation-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`);await shot(`cases-${width}-${index+1}`);}
    measurements.push({width,...geometry,colors:colors.text,menus});
  }
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:320,deviceScaleFactor:1,mobile:false});
  const clipped=`document.querySelector('[data-navigation-clipped] [data-bml-ref=menu]')`;
  await evaluate(`const button=document.querySelector('[data-navigation-clipped] [data-bml-ref=more]');button.scrollIntoView({block:'center'});button.click()`);
  await until(`${clipped}.matches(':popover-open')&&${popupFit(clipped)}`);await key('End');
  assert.ok(await truth(`(()=>{const menu=${clipped},box=menu.getBoundingClientRect(),entry=menu.lastElementChild.getBoundingClientRect();return menu.scrollTop>0&&entry.bottom<=box.bottom+1&&document.elementFromPoint(box.left+10,box.top+10)?.closest('.tabs-menu')===menu;})()`));
  await shot('short-clipped');await key('Escape');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/navigation');await until(`${node('tabs','light')}.hasAttribute('data-tabs-enhanced')`);await show('light');
  const destination=await evaluate(`${ref('menu','light')}.firstElementChild.getAttribute('href')`);await evaluate(`${ref('menu','light')}.firstElementChild.focus()`);await key('Enter');await until(`document.readyState==='complete'&&location.pathname===${JSON.stringify(destination)}`);
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:probe.identifier});
  await call('Emulation.setScriptExecutionDisabled',{value:true});await call('Emulation.setDeviceMetricsOverride',{width:390,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/navigation');
  assert.ok(await truth(`(()=>{const root=${node('tabs','light')},row=${ref('row','light')};return !root.hasAttribute('data-tabs-enhanced')&&${ref('more','light')}.hidden&&row.querySelectorAll(':scope > a').length===8&&[...row.querySelectorAll(':scope > a')].every(link=>link.tabIndex===0&&!link.hidden)&&getComputedStyle(row).overflowX==='auto'&&row.scrollWidth>row.clientWidth&&document.documentElement.scrollWidth<=innerWidth;})()`));
  await shot('native-390');await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-navigation.json`),JSON.stringify({typography,measurements},null,2));
}
