import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Native values and real application-owned HTTP search through the compiled producer. */
export async function checkSelects({ call, evaluate, until, base, mode, artifacts }) {
  const native=(kind,palette='light')=>`[data-select-${kind}=${palette}]`;
  const root=(kind,palette='light')=>`document.querySelector('${native(kind,palette)}')?.closest('[data-bml-design-select]')`;
  const part=(kind,ref,palette='light')=>`${root(kind,palette)}?.querySelector('[data-bml-ref=${ref}]')`;
  const opened=kind=>`${part(kind,'popup')}?.matches(':popover-open')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const open=async kind=>{await evaluate(`${part(kind,'trigger')}.click()`);await until(opened(kind));};
  const key=async value=>{
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,code:value,
      windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35,Backspace:8}[value],
      ...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
  };
  const type=async (kind,value)=>evaluate(`(() => {const input=${part(kind,'query')};input.focus();input.value=${JSON.stringify(value)};input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const choose=async (kind,index)=>evaluate(`${part(kind,'options')}.children[${index}].dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true}))`);
  const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const listeners=async()=>{
    const counts={};for(const expression of ['document','window']){
      const handle=await call('Runtime.evaluate',{expression});const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
      await call('Runtime.releaseObject',{objectId:handle.result.objectId});
      counts[expression]=result.listeners.filter(listener=>['pointerdown','reset','bml:update','resize','scroll'].includes(listener.type)).length;
    }return counts;
  };
  const instrumentation=await call('Page.addScriptToEvaluateOnNewDocument',{source:`
    window.selectObservers=new Set();const Original=window.ResizeObserver;
    window.ResizeObserver=class extends Original{observe(...args){super.observe(...args);window.selectObservers.add(this);}disconnect(){super.disconnect();window.selectObservers.delete(this);}};
  `});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/selects'});
  await until(`document.readyState==='complete'&&document.querySelector('[data-select-update=light]')?.dataset.bmlActionBound==='true'&&${part('single','trigger')}?.hasAttribute('aria-controls')`);
  const baseline=await listeners();const observers=await evaluate('window.selectObservers.size');assert.equal(observers,12);
  assert.deepEqual(await evaluate(`(() => {const n=document.querySelector('${native('single')}'),button=${part('single','trigger')},multi=${part('multiple','trigger')};
    return {native:n instanceof HTMLSelectElement,value:n.value,name:n.name,required:n.required,attrs:n.classList.contains('fixture-native-select'),hidden:n.getAttribute('aria-hidden'),tab:n.tabIndex,
      heights:[button.getBoundingClientRect().height,multi.getBoundingClientRect().height],role:button.role,label:button.getAttribute('aria-label'),hint:button.getAttribute('aria-describedby'),
      requiredAria:button.getAttribute('aria-required'),multiple:${part('multiple','options')}.getAttribute('aria-multiselectable'),
      tags:${part('multiple','tags')}.querySelectorAll('.select-tag').length,values:new FormData(document.querySelector('[data-select-form=light]')).getAll('toppings'),
      ids:[...document.querySelectorAll('.select-options')].map(list=>list.id),escape:!document.querySelector('#native-select').hasAttribute('data-bml-c')};})()`),
    {native:true,value:'b',name:'flavor',required:true,attrs:true,hidden:'true',tab:-1,heights:[32,30],role:'combobox',label:'Flavor',hint:'select-hint-light',requiredAria:'true',multiple:'true',tags:2,values:['a','b'],
      ids:await evaluate('[...document.querySelectorAll(".select-options")].map(list=>list.id)'),escape:true});
  assert.ok(await evaluate(`new Set([...document.querySelectorAll('.select-options')].map(list=>list.id)).size===12`));
  await evaluate(`window.selectChanges=[];window.selectRequests=[];window.selectProbe=new AbortController();
    document.addEventListener('change',event=>{if(event.target instanceof HTMLSelectElement)window.selectChanges.push({name:event.target.name,value:event.target.value});},{capture:true,signal:window.selectProbe.signal});
    document.addEventListener('selectsearch',event=>window.selectRequests.push({query:event.detail.query,offset:event.detail.offset}),{capture:true,signal:window.selectProbe.signal});`);
  await evaluate(`${part('single','trigger')}.focus()`);await key('Enter');await until(opened('single'));
  assert.equal(await evaluate(`${part('single','options')}.querySelector('[aria-disabled=true]').textContent`),'Unavailable');
  await choose('single',3);assert.equal(await evaluate(`document.querySelector('${native('single')}').value`),'b');
  await key('End');assert.equal(await evaluate(`document.getElementById(${part('single','trigger')}.getAttribute('aria-activedescendant')).textContent`),'Citrus');
  await key('ArrowUp');assert.equal(await evaluate(`document.getElementById(${part('single','trigger')}.getAttribute('aria-activedescendant')).textContent`),'Berry');
  await key('Home');await key('ArrowDown');assert.equal(await evaluate(`document.getElementById(${part('single','trigger')}.getAttribute('aria-activedescendant')).textContent`),'Apple');
  await key('End');await key('Enter');await until(`!(${opened('single')})`);
  assert.ok(await evaluate(`document.querySelector('${native('single')}').value==='c'&&document.activeElement===${part('single','trigger')}`));
  assert.equal(await evaluate('window.selectChanges.length'),1,'Choosing emits one ordinary native change');
  await open('single');
  await evaluate(`${part('single','options')}.focus()`);await key('Home');await key('ArrowDown');
  assert.ok(await evaluate(`(() => {const list=${part('single','options')},active=document.getElementById(list.getAttribute('aria-activedescendant'));
    return document.activeElement===list&&active?.role==='option'&&active.textContent==='Apple'&&active.getAttribute('aria-disabled')!=='true';})()`),
    'The scrollable listbox supports direct keyboard focus and announces its active option');
  await key('End');await key('Enter');await until(`!(${opened('single')})`);
  assert.ok(await evaluate(`document.querySelector('${native('single')}').value==='c'&&document.activeElement===${part('single','trigger')}&&
    !${part('single','options')}.hasAttribute('aria-activedescendant')`),'Selection from the listbox returns focus and clears its closed active descendant');
  await evaluate('document.querySelector("[data-select-form=light]").requestSubmit()');
  await until('document.querySelector("[data-select-result=light]").textContent.includes("Saved c (1)")');
  await click('[data-select-reject=light]');await until('document.querySelector("[data-select-error=light]")?.textContent.includes("Choice rejected")');
  assert.equal(await evaluate(`document.querySelector('${native('single')}').value`),'c');
  await click('[data-select-reset=light]');await until(`document.querySelector('${native('single')}').value==='b'&&${part('single','selection')}.textContent==='Berry'`);
  assert.equal(await evaluate('document.querySelector("[data-required-form]").reportValidity()'),false);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-required-select]').closest('[data-bml-design-select]').querySelector('[data-bml-ref=trigger]')&&document.activeElement.getAttribute('aria-invalid')==='true'`));
  await evaluate(`document.activeElement.click()`);await key('End');await key('Enter');assert.equal(await evaluate('document.querySelector("[data-required-form]").checkValidity()'),true);
  await open('multiple');await choose('multiple',2);
  assert.ok(await evaluate(opened('multiple')),'Replacing a selected option does not make the original pointer event an outside click');
  assert.deepEqual(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).getAll('toppings')`),['a','b','c']);
  assert.ok(await evaluate(`${part('multiple','tags')}.querySelectorAll('.select-tag').length===2&&${part('multiple','tags')}.textContent.includes('+1')`));
  assert.equal(await evaluate(`${part('multiple','options')}.lastElementChild.getAttribute('aria-disabled')`),'true','Disabled optgroups retain native semantics');
  await evaluate(`${part('multiple','tags')}.querySelector('[data-remove=a]').click()`);await key('Backspace');
  assert.deepEqual(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).getAll('toppings')`),['b']);
  await open('custom');await type('custom','a new value');await key('End');await key('Enter');
  assert.ok(await evaluate(`document.querySelector('${native('custom')}').value==='a new value'&&${part('custom','selection')}.textContent==='a new value'`));
  await open('custom');await type('custom','Another custom value');
  for(const allowed of [false,true]) {
    await evaluate(`window.customToggle=document.querySelector('[data-select-custom-toggle=light]');window.customToggle.click()`);
    await until(`!window.customToggle.isConnected&&${root('custom')}.hasAttribute('data-allow-custom')===${allowed}&&${part('custom','options')}.textContent.includes('Use “Another custom value”')===${allowed}`);
    assert.equal(await evaluate(`${part('custom','query')}.value`),'Another custom value');
  }
  await key('Escape');
  await click('[data-select-disable=light]');await until(`document.querySelector('${native('single')}').disabled&&${part('multiple','trigger')}.disabled`);
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).get('flavor')`),null);
  assert.ok(await evaluate(`[...${part('multiple','tags')}.querySelectorAll('button')].every(button=>button.disabled)`));
  await click('[data-select-disable=light]');await until(`!document.querySelector('${native('single')}').disabled`);
  await click('[data-select-loading=light]');await until(`${part('single','trigger')}.disabled&&${part('single','trigger')}.getAttribute('aria-busy')==='true'`);
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).get('flavor')`),'b','Loading retains the native submission value');
  await click('[data-select-loading=light]');await until(`!${part('single','trigger')}.disabled`);
  await open('async');assert.equal(await evaluate(`${part('async','options')}.textContent`),'Any option');
  assert.match(await evaluate(`${part('async','status')}.textContent`),/at least 2/);
  await type('async','x');await pause(130);assert.equal(await evaluate('window.selectRequests.length'),0,'Minimum query suppresses requests and value all has no special behavior');
  await type('async','ab');await type('async','alpha');
  assert.ok(await evaluate(`${part('async','popup')}.getAttribute('aria-busy')==='true'&&getComputedStyle(${root('async')}.querySelector('.select-progress')).display==='flex'`));
  await until(`${part('async','options')}.textContent.includes('alpha choice 0')`);
  assert.deepEqual(await evaluate('window.selectRequests'),[{query:'alpha',offset:0}],'Debounce dispatches the newest query once');
  assert.equal(await evaluate(`${part('async','previous')}.disabled`),true);
  await key('Tab');
  assert.ok(await evaluate(`document.activeElement===${part('async','next')}&&${opened('async')}`),
    'Tab reaches the enabled pagination action without closing its popup');
  await key('Escape');
  assert.ok(await evaluate(`!(${opened('async')})&&document.activeElement===${part('async','trigger')}`),
    'Escape from pagination returns focus to the select trigger');
  await open('async');await type('async','alpha');await until(`${part('async','options')}.textContent.includes('alpha choice 0')`);
  await key('End');await key('Enter');await until(`!(${opened('async')})`);
  assert.equal(await evaluate(`document.querySelector('${native('async')}').value`),'alpha-0');
  await open('async');await type('async','alpha');await until(`${part('async','options')}.textContent.includes('alpha choice 0')`);
  await evaluate(`${part('async','next')}.click()`);await until(`${part('async','options')}.textContent.includes('alpha choice 2')`);
  assert.equal(await evaluate(`document.querySelector('${native('async')}').value`),'alpha-0','Selected remote native values survive other pages');
  await evaluate(`${part('async','next')}.click()`);await until(`${part('async','options')}.textContent.includes('alpha choice 4')`);
  assert.ok(await evaluate(`${part('async','next')}.disabled&&!${part('async','previous')}.disabled`));
  await evaluate(`${part('async','previous')}.click()`);await until(`${part('async','options')}.textContent.includes('alpha choice 2')`);
  assert.deepEqual(await evaluate('window.selectRequests.slice(-3).map(request=>request.offset)'),[2,4,2]);
  await type('async','none');await until(`${part('async','status')}.textContent==='No options found.'`);
  assert.equal(await evaluate(`${part('async','options')}.textContent`),'Any option');
  await type('async','fail');await until(`${part('async','status')}.textContent.includes('Search failed')`);
  assert.ok(await evaluate(`!${part('async','popup')}.hasAttribute('aria-busy')&&${part('async','options')}.textContent==='Any option'`));
  await type('async','retry');await until(`${part('async','options')}.textContent.includes('retry choice 0')`);
  await type('async','slow');await until('window.selectRequests.at(-1)?.query==="slow"');
  await type('async','fast');await until(`${part('async','options')}.textContent.includes('fast choice 0')`);await pause(750);
  assert.ok(await evaluate(`${part('async','options')}.textContent.includes('fast choice 0')&&!${part('async','options')}.textContent.includes('slow choice')`),'Stale successful HTTP results cannot replace the current query');
  await type('async','latefail');await until('window.selectRequests.at(-1)?.query==="latefail"');
  await type('async','latest');await until(`${part('async','options')}.textContent.includes('latest choice 0')`);await pause(750);
  assert.equal(await evaluate(`${part('async','status')}.textContent`),'','Stale failed requests do not overwrite successful recovery');
  await evaluate(`window.selectRoot=${root('async')};window.selectQuery=${part('async','query')};window.selectQuery.setSelectionRange(1,3)`);
  await click('[data-select-update=light]');await until('document.querySelector("[data-select-updates=light]").textContent.includes("Updates 1")');
  assert.ok(await evaluate(`${root('async')}===window.selectRoot&&${opened('async')}&&document.activeElement===window.selectQuery&&window.selectQuery.value==='latest'&&window.selectQuery.selectionStart===1&&window.selectQuery.selectionEnd===3`),'An unrelated island patch retains the select popup, draft, caret and focus');
  assert.equal(await evaluate(`document.querySelector('${native('async','purple')}').value`),'saved');
  assert.deepEqual(await evaluate(`['light','purple'].map(palette=>getComputedStyle(document.querySelector('[data-select-async='+palette+']').closest('[data-bml-design-select]').querySelector('.select-popup')).backgroundColor)`),['rgb(248, 250, 252)','rgb(22, 26, 37)']);
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('alternate')`);
  await until(`getComputedStyle(${part('async','trigger')}).borderTopColor==='rgb(37, 99, 235)'`);
  assert.deepEqual(await evaluate(`[getComputedStyle(${part('async','popup')}).backgroundColor,getComputedStyle(${part('async','options')}.firstElementChild).color,getComputedStyle(${part('async','trigger')}).borderTopColor]`),['rgb(219, 234, 254)','rgb(30, 58, 138)','rgb(37, 99, 235)']);
  await key('Escape');
  await open('remote-multiple');await type('remote-multiple','tags');await until(`${part('remote-multiple','options')}.textContent.includes('tags choice 0')`);
  await choose('remote-multiple',1);
  assert.ok(await evaluate(opened('remote-multiple')),'Multiple async choices keep the popup open for paging');
  await evaluate(`${part('remote-multiple','next')}.click()`);
  await until(`${part('remote-multiple','options')}.textContent.includes('tags choice 2')`);
  await choose('remote-multiple',1);await key('End');await key('Enter');
  assert.deepEqual(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).getAll('remoteTags')`),['saved','tags-0','tags-2','tags']);
  assert.ok(await evaluate(`${part('remote-multiple','tags')}.querySelectorAll('.select-tag').length===2&&${part('remote-multiple','tags')}.textContent.includes('+2')`),'Multiple remote/native choices and custom values survive pagination with tag limits');
  const requestCount=await evaluate('window.selectRequests.length');
  for(const allowed of [false,true]) {
    await evaluate(`window.customToggle=document.querySelector('[data-select-custom-toggle=light]');window.customToggle.click()`);
    await until(`!window.customToggle.isConnected&&${root('remote-multiple')}.hasAttribute('data-allow-custom')===${allowed}&&${part('remote-multiple','options')}.textContent.includes('Use “tags”')===${allowed}`);
    assert.deepEqual(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).getAll('remoteTags')`),['saved','tags-0','tags-2','tags']);
  }
  assert.equal(await evaluate('window.selectRequests.length'),requestCount,'Changing custom choices reconciles current results without another search');
  await type('remote-multiple','fail');
  await until(`${part('remote-multiple','status')}.textContent==='Search failed. Please try again.'`);
  const failedRequestCount=await evaluate('window.selectRequests.length');
  for(const allowed of [false,true]) {
    await evaluate(`window.customToggle=document.querySelector('[data-select-custom-toggle=light]');window.customToggle.click()`);
    await until(`!window.customToggle.isConnected&&${root('remote-multiple')}.hasAttribute('data-allow-custom')===${allowed}`);
    assert.equal(await evaluate(`${part('remote-multiple','status')}.textContent`),'Search failed. Please try again.');
    assert.deepEqual(await evaluate(`new FormData(document.querySelector('[data-select-form=light]')).getAll('remoteTags')`),['saved','tags-0','tags-2','tags']);
  }
  assert.equal(await evaluate('window.selectRequests.length'),failedRequestCount,'Custom-choice updates retain failed-search feedback without issuing a retry');
  await key('Escape');
  for(const width of [1440,390]){
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await evaluate('window.scrollTo(0,0)');await until('document.documentElement.scrollWidth<=innerWidth');
    const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`select-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  await key('Escape');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:320,deviceScaleFactor:1,mobile:false});
  await evaluate(`document.querySelector('[data-clipped-select]').closest('[data-bml-design-select]').querySelector('[data-bml-ref=trigger]').scrollIntoView({block:'center'})`);
  await evaluate(`document.querySelector('[data-clipped-select]').closest('[data-bml-design-select]').querySelector('[data-bml-ref=trigger]').focus()`);await key('Enter');await key('End');
  assert.ok(await evaluate(`(() => {const root=document.querySelector('[data-clipped-select]').closest('[data-bml-design-select]'),popup=root.querySelector('.select-popup'),r=popup.getBoundingClientRect();
    return popup.matches(':popover-open')&&r.left>=11&&r.top>=11&&r.right<=innerWidth-11&&r.bottom<=innerHeight-11&&popup.scrollTop>0&&document.elementFromPoint(r.left+10,r.top+10)?.closest('.select-popup')===popup;})()`),'Native popup escapes clipping, fits the short screen and scrolls its keyboard choice');
  const short=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`select-short-${mode}.png`),Buffer.from(short.data,'base64'));
  await key('Escape');await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});await evaluate('window.scrollTo(0,0)');
  await open('async');await type('async','slow');await until('window.selectRequests.at(-1)?.query==="slow"');
  await evaluate(`window.removedSelect=${root('async')};window.removedSelectOptions=${part('async','options')};window.removedSelectText=window.removedSelectOptions.textContent`);
  await click('[data-select-toggle=light]');await until(`!document.querySelector('${native('async')}')&&window.selectObservers.size===${observers-5}`);await pause(750);
  assert.ok(await evaluate('!window.removedSelect.querySelector(".select-popup").matches(":popover-open")&&window.removedSelectOptions.textContent===window.removedSelectText'),'Disposal closes the popup and rejects late HTTP results');
  for(const expression of ['window.removedSelect','window.removedSelect.querySelector("select")','window.removedSelect.querySelector("[data-bml-ref=trigger]")','window.removedSelect.querySelector("[data-bml-ref=query]")','window.removedSelectOptions']){
    const handle=await call('Runtime.evaluate',{expression});const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});assert.equal(result.listeners.length,0,'Disposal removes helper root/native/trigger/query listeners');
  }
  for(let cycle=0;cycle<3;cycle++){
    await click('[data-select-toggle=light]');await until(`${part('single','trigger')}?.hasAttribute('aria-controls')&&window.selectObservers.size===${observers}`);
    assert.deepEqual(await listeners(),baseline,'Remount does not accumulate scoped document/window listeners');
    if(cycle<2){await click('[data-select-toggle=light]');await until(`!document.querySelector('${native('single')}')&&window.selectObservers.size===${observers-5}`);}
  }
  await click('[data-select-change=light]');await until(`document.querySelector('${native('single')}').value==='a'&&${part('single','selection')}.textContent==='Apple'`);
  await evaluate('window.selectProbe.abort()');await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:instrumentation.identifier});
}
