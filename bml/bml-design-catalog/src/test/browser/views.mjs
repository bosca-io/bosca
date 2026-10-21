import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Real native queries, local choices and caller-owned panel relationships in both palettes. */
export async function checkSearchViews({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const node=(part,id='dark')=>`document.querySelector('[data-views-${part}=${id}]')`;
  const owner=id=>`document.querySelector('[data-search-views=${id}]')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async value=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,windowsVirtualKeyCode:{Enter:13,Tab:9,ArrowLeft:37,ArrowRight:39,Home:36,End:35}[value],...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});};
  const type=value=>evaluate(`${node('query')}.focus();${node('query')}.value=${JSON.stringify(value)};${node('query')}.dispatchEvent(new Event('input',{bubbles:true}))`);
  const action=async(name,done)=>{await evaluate(`window.viewsAction=${node(name)};window.viewsAction.click()`);await until(`!window.viewsAction.isConnected&&(${done})`);};
  const submit=async()=>{
    await evaluate(`window.viewsSequence=Number(${node('toast')}.dataset.sequence);${node('form')}.requestSubmit()`);
    await until(`Number(${node('toast')}.dataset.sequence)===window.viewsSequence+1&&!${node('form')}.hasAttribute('data-bml-action-pending')`);
  };
  const selectView=async view=>{await click(`[data-views-tabs=dark] [data-tab="${view}"]`);await until(`${node('tabs')}.dataset.selected===${JSON.stringify(view)}&&!document.querySelector('[data-views-panel=dark][data-view="${view}"]').hidden`);};
  const listeners=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    const found=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    return found.listeners.filter(item=>['click','input','keydown','bml:update','viewchange','toggle'].includes(item.type)).length;
  };
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-views-${name}.png`),Buffer.from(capture.data,'base64'));};
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/views');
  await until(`${node('form')}.dataset.bmlActionBound==='true'&&${node('tabs')}.querySelector('[role=tab]').hasAttribute('aria-controls')`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`),'/views');
  assert.deepEqual(await evaluate(`Array.from(new FormData(${node('form')}))`),[['q',''],['category','all']]);
  assert.ok(await truth(`!${node('query')}.autofocus&&${node('query')}.maxLength===120&&${node('query')}.getAttribute('aria-describedby')==='views-hint-dark'&&document.querySelector('[data-views-readonly]').readOnly&&document.querySelector('[data-views-readonly]').closest('.search-input').querySelector('[data-bml-ref=clear]').disabled&&new FormData(document.querySelector('[data-views-readonly-form]')).get('q')==='A retained query'&&!new FormData(document.querySelector('[data-views-disabled-form]')).has('q')`));
  assert.ok(await truth(`!document.querySelector('[data-views-empty-segments] [role=radio]')&&!document.querySelector('[data-views-empty-tabs] [role=tab]')&&document.querySelector('[data-views-unmatched-segments] [role=radio]').tabIndex===0&&!document.querySelector('[data-views-unmatched-segments] [aria-checked=true]')&&document.querySelector('[data-views-unmatched-tabs] [role=tab]').tabIndex===0&&!document.querySelector('[data-views-unmatched-tabs] [aria-selected=true]')`));
  await evaluate(`window.viewEvents=[];window.viewsEventsController=new AbortController();for(const type of ['input','segmentchange','viewchange'])document.addEventListener(type,event=>window.viewEvents.push({type,detail:event.detail??null}),{signal:window.viewsEventsController.signal})`);
  const baseline=await listeners('document');
  await type('Clear this');
  await evaluate(`${node('query')}.closest('.search-input').querySelector('[data-bml-ref=clear]').click()`);
  assert.ok(await truth(`${node('query')}.value===''&&document.activeElement===${node('query')}&&${node('query')}.closest('.search-input').querySelector('[data-bml-ref=clearAction]').hidden&&window.viewEvents.filter(event=>event.type==='input').length===2`));
  await evaluate(`${node('category')}.querySelector('[role=radio]').focus()`);
  for(const [keyName,value] of [['ArrowLeft','overlay'],['Home','all'],['End','overlay'],['ArrowRight','all'],['ArrowRight','control']]) {
    await key(keyName);
    assert.ok(await truth(`${node('category')}.querySelector('input').value==='${value}'&&${node('category')}.querySelector('[aria-checked=true]').dataset.value==='${value}'&&document.activeElement===${node('category')}.querySelector('[aria-checked=true]')`));
  }
  assert.equal(await evaluate('window.viewEvents.filter(event=>event.type==="segmentchange").length'),5);
  assert.equal(await evaluate(`${node('category','light')}.querySelector('input').value`),'all');
  await type('Draft & <retained>');
  await evaluate(`window.viewsQuery=${node('query')};window.viewsCategory=${node('category')};window.viewsTabs=${node('tabs')};window.viewsQuery.setSelectionRange(2,8);${owner('dark')}.querySelector('[data-example-states]').open=true`);
  await action('refresh',`${node('toast')}.matches(':popover-open')`);
  assert.deepEqual(await evaluate(`[${node('query')}===window.viewsQuery,${node('category')}===window.viewsCategory,${node('tabs')}===window.viewsTabs,${node('query')}.value,${node('query')}.selectionStart,${node('query')}.selectionEnd,document.activeElement===window.viewsQuery,${node('category')}.querySelector('input').value,${owner('dark')}.querySelector('details').open]`),[true,true,true,'Draft & <retained>',2,8,true,'control',true]);
  await evaluate(`${node('toast')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await type('Search');await submit();
  assert.equal(await evaluate(`${node('toast')}.querySelector('[role=status]').textContent`),'Search updated.');
  assert.deepEqual(await evaluate(`[...${owner('dark')}.querySelectorAll('.catalog-result-list a')].map(link=>link.getAttribute('href'))`),['/components/controls#ref-select','/components/controls#ref-search-input']);
  await action('reject',`${node('reject')}.checked`);
  await type('Rejected & exact');await submit();
  assert.ok(await truth(`${node('query')}.value==='Rejected & exact'&&${node('toast')}.querySelector('[role=alert]').textContent==='Search rejected. Your draft and previous results were kept.'&&${owner('dark')}.querySelectorAll('.catalog-result-list a').length===2&&${node('toast','light')}.dataset.sequence==='0'`));
  await action('reject',`!${node('reject')}.checked`);
  await type('not-present');
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseViewsSearch=resolve;});}return original(...args);};window.viewsButtonSize=${node('submit')}.getBoundingClientRect().toJSON();${node('form')}.requestSubmit()})()`);
  await until(`typeof window.releaseViewsSearch==='function'&&${node('form')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`(()=>{const button=${node('submit')},box=button.getBoundingClientRect();return box.height===window.viewsButtonSize.height&&box.width===window.viewsButtonSize.width&&getComputedStyle(button.querySelector('.button-progress')).display==='flex';})()`));
  await evaluate('window.releaseViewsSearch()');
  await until(`${owner('dark')}.querySelector('.empty-state')&&!${node('form')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${owner('dark')}.querySelector('.empty-state p')?.checkVisibility()`), 'Empty search guidance is visible');
  assert.ok((await call('Accessibility.getFullAXTree')).nodes.some(node => node.role?.value === 'StaticText'
    && node.name?.value === 'Try another query or choose All categories.'), 'Empty search guidance reaches the accessibility tree');
  await selectView('Source');
  await evaluate(`${node('tabs')}.querySelector('[aria-selected=true]').focus()`);
  for(const [keyName,value] of [['ArrowRight','Preview'],['End','Source'],['Home','Preview'],['ArrowLeft','Source']]) {
    await key(keyName); await until(`${node('tabs')}.dataset.selected==='${value}'&&!document.querySelector('[data-views-panel=dark][data-view=${value}]').hidden`);
    assert.ok(await truth(`document.activeElement===${node('tabs')}.querySelector('[aria-selected=true]')`));
  }
  assert.deepEqual(await evaluate('window.viewEvents.filter(event=>event.type==="viewchange").map(event=>event.detail)'),['Source','Preview','Source','Preview','Source']);
  await action('refresh',`${node('tabs')}.dataset.selected==='Source'`);
  assert.ok(await truth(`!document.querySelector('[data-views-panel=dark][data-view=Source]').hidden&&${node('tabs','light')}.dataset.selected==='Preview'`));
  await action('replace',`${node('query')}.value==='layout'&&${node('tabs')}.dataset.selected==='Details'`);
  assert.ok(await truth(`${node('query')}===window.viewsQuery&&${node('category')}===window.viewsCategory&&${node('tabs')}===window.viewsTabs&&${node('category')}.querySelector('input').value==='layout'&&${node('category')}.querySelector('[aria-checked=true]').dataset.value==='layout'`));
  await action('disabled',`${node('query')}.disabled`);
  assert.ok(await truth(`${node('query')}.closest('.search-input').querySelector('[data-bml-ref=clear]').disabled&&!new FormData(${node('form')}).has('q')&&new FormData(${node('form')}).get('category')==='layout'`));
  await action('disabled',`!${node('query')}.disabled`);
  await action('list',`${node('tabs')}.querySelector('[data-tab=Notes]')`);
  assert.ok(await truth(`${node('tabs')}!==window.viewsTabs&&!window.viewsTabs.isConnected&&${node('query')}===window.viewsQuery&&${node('tabs')}.querySelectorAll('[role=tab]').length===2&&${node('tabs')}.querySelector('[aria-selected=true]').dataset.tab==='Preview'`));
  await selectView('Notes');
  assert.ok(await truth(`(()=>{const tabs=[...document.querySelectorAll('[data-views-tabs] [role=tab]')];return new Set(tabs.map(tab=>tab.id)).size===5&&tabs.every(tab=>{const panel=document.getElementById(tab.getAttribute('aria-controls'));return panel?.role==='tabpanel'&&panel.getAttribute('aria-labelledby')===tab.id;})})()`));
  await evaluate(`window.removedViews=[${node('query')}.closest('.search-input'),${node('category')},${node('tabs')}];window.removedCategory=${node('category')}.querySelector('input')`);
  await action('visible',`!${node('query')}`);
  for(let index=0;index<3;index++)assert.equal(await listeners(`window.removedViews[${index}]`),0);
  await evaluate(`window.removedViews[1].querySelector('[data-value=all]').click()`);
  assert.equal(await evaluate('window.removedCategory.value'),'layout','Removed groups no longer handle choices');
  for(let cycle=0;cycle<3;cycle++) {
    await action('visible',`${node('query')}&&${node('tabs')}.querySelector('[role=tab]').hasAttribute('aria-controls')`);
    assert.equal(await listeners('document'),baseline,'Repeated mounting restores document listener counts');
    if(cycle<2)await action('visible',`!${node('query')}`);
  }
  await evaluate('window.viewsEventsController.abort()');
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await navigate('/views'); await until(`${node('tabs')}.querySelector('[role=tab]').hasAttribute('aria-controls')`);
    await evaluate(`document.fonts.ready;document.querySelector('[data-catalog-light]').click();document.querySelectorAll('[data-example-states]').forEach(details=>details.open=false)`);
    const geometry=await evaluate(`(()=>{const groups=[...document.querySelectorAll('[data-search-views]')].map(owner=>{const query=owner.querySelector('.search-input'),category=owner.querySelector('[data-views-category]'),form=owner.querySelector('form'),panels=owner.querySelector('[data-views-panels]'),tabs=owner.querySelector('[data-views-tabs]'),panel=owner.querySelector('[role=tabpanel]:not([hidden])');return {id:owner.dataset.searchViews,searchHeight:query.getBoundingClientRect().height,categoryHeight:category.getBoundingClientRect().height,formGap:panels.getBoundingClientRect().top-form.getBoundingClientRect().bottom,panelGap:panel.getBoundingClientRect().top-tabs.getBoundingClientRect().bottom};});return {groups,overflow:document.documentElement.scrollWidth-innerWidth};})()`);
    assert.ok(geometry.overflow<=0);
    for(const group of geometry.groups)assert.ok(group.searchHeight===40&&group.categoryHeight>=30&&Math.abs(group.formGap-16)<0.5&&Math.abs(group.panelGap-16)<0.5,JSON.stringify(group));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-views-palette] input[type=search], [data-views-palette] [role=radio], [data-views-palette] [role=tab], [data-views-palette] .example-note, [data-views-palette] .catalog-link, [data-views-palette] .catalog-description', null, '[data-views-palette] .search-input')`);
    const placeholders=await evaluate(`(${contrastSamples.toString()})('[data-views-palette] input[type=search]', '::placeholder')`);
    for(const sample of colors.controls)assert.ok(sample.ratio>=3,JSON.stringify(sample));
    for(const sample of [...colors.text,...placeholders.text])assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    await evaluate(`document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);await shot(`page-${width}`);
    for(const id of ['dark','light']) {
      await evaluate(`${node('query',id)}.scrollIntoView({block:'center'});${node('query',id)}.focus()`);await shot(`${id}-${width}`);
    }
    for(const index of width===390?[0,1,2,3,4,5]:[0,2,4]) {
      await evaluate(`document.querySelectorAll('[data-views-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`);await shot(`cases-${width}-${index+1}`);
    }
    await evaluate(`document.querySelector('[data-views-long-tabs] [role=tab]').focus()`);await key('End');
    const longSegments=await evaluate(`(()=>{const root=document.querySelector('[data-views-long-segments]'),button=root.querySelector('[role=radio]');return {root:root.getBoundingClientRect().toJSON(),button:button.getBoundingClientRect().toJSON(),clientHeight:root.clientHeight};})()`);
    assert.ok(longSegments.clientHeight>=28&&longSegments.button.top>=longSegments.root.top&&longSegments.button.bottom<=longSegments.root.top+longSegments.clientHeight+1,JSON.stringify(longSegments));
    const longTab=await evaluate(`(()=>{const root=document.querySelector('[data-views-long-tabs]'),tab=root.querySelector('[aria-selected=true]');return {root:root.getBoundingClientRect().toJSON(),tab:tab.getBoundingClientRect().toJSON(),width:innerWidth,scroll:root.scrollLeft,focused:document.activeElement===tab};})()`);
    assert.ok(longTab.root.right<=width&&longTab.tab.right<=longTab.root.right+1&&longTab.focused,JSON.stringify(longTab));
    measurements.push({width,...geometry,colors:colors.text,controls:colors.controls,placeholders:placeholders.text});
  }
  await writeFile(join(artifacts,`catalog-${mode}-views.json`),JSON.stringify(measurements,null,2));
  await call('Emulation.setScriptExecutionDisabled',{value:true});await navigate('/views');
  assert.ok(await truth(`${node('query','light')} instanceof HTMLInputElement&&!${node('query','light')}.autofocus&&new FormData(${node('form','light')}).get('category')==='all'&&${owner('light')}.querySelectorAll('.catalog-result-list a').length===15&&!document.querySelector('[data-views-panel=light][data-view=Preview]').hidden&&document.querySelector('[data-views-readonly]').readOnly&&document.querySelector('[data-views-disabled-case]').disabled&&document.documentElement.scrollWidth<=innerWidth`));
  await shot('native-390');await call('Emulation.setScriptExecutionDisabled',{value:false});
}
