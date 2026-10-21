import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real component events, caller actions and retained DOM against the compiled dependency. */
export async function checkSelection({ call, evaluate, until, base, mode, artifacts }) {
  const query='[data-search-query=light]';
  const group='[data-mode=light]';
  const views='[data-views=light]';
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async value=>{
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:value});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:value});
  };
  const listeners=async (expression,types)=>{
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    return result.listeners.filter(listener=>!types||types.includes(listener.type)).length;
  };
  const type=async value=>evaluate(`(() => { const input=document.querySelector('${query}');
    input.focus(); input.value=${JSON.stringify(value)}; input.dispatchEvent(new Event('input',{bubbles:true})); })()`);
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/selection'});
  await until(`document.readyState==='complete' && document.querySelector('[data-selection-update=light]')?.dataset.bmlActionBound==='true' && !document.querySelector('${query}').closest('.search-input').querySelector('[data-bml-ref=clear]').hasAttribute('title')`);
  assert.deepEqual(await evaluate(`(() => { const input=document.querySelector('${query}'), root=input.closest('.search-input');
    const radio=document.querySelector('${group}'); const tabs=document.querySelector('${views}');
    return {height:root.getBoundingClientRect().height,groupHeight:radio.getBoundingClientRect().height,
      name:input.name,value:input.value,label:input.getAttribute('aria-label'),placeholder:input.placeholder,
      autofocus:input.autofocus,attrs:input.classList.contains('fixture-query')&&input.maxLength===120,
      native:input instanceof HTMLInputElement&&input.type==='search'&&!input.disabled,
      roles:[radio.role,tabs.role],radios:[...radio.querySelectorAll('[role=radio]')].map(button=>[button.getAttribute('aria-checked'),button.tabIndex]),
      selected:tabs.querySelector('[aria-selected=true]').textContent.trim(),
      panels:[...tabs.querySelectorAll('[role=tab]')].every(tab=>{const panel=document.getElementById(tab.getAttribute('aria-controls'));return panel?.role==='tabpanel'&&panel.getAttribute('aria-labelledby')===tab.id}),
      classes:radio.classList.contains('fixture-mode')&&tabs.classList.contains('fixture-views'),
      values:[...new FormData(document.querySelector('[data-selection-form=light]'))]}; })()`),
    {height:40,groupHeight:30,name:'q',value:'Initial light',label:'Search components',placeholder:'Find components…',autofocus:false,attrs:true,native:true,
      roles:['radiogroup','tablist'],radios:[['true',0],['false',-1]],selected:'Preview',panels:true,classes:true,values:[['q','Initial light'],['mode','content']]});
  assert.ok(await evaluate(`(() => { const empty=document.querySelector('[data-empty-search]').closest('.search-input');
    return getComputedStyle(empty.querySelector('[data-bml-ref=clearAction]')).display==='none' &&
      document.querySelector('[data-readonly-search]').readOnly && document.querySelector('[data-readonly-search]').closest('.search-input').querySelector('button').disabled &&
      !document.querySelector('[data-empty-segments] [role=radio]')&&!document.querySelector('[data-empty-views] [role=tab]') &&
      document.querySelector('[data-unmatched-segments] [role=radio]').tabIndex===0 && document.querySelector('[data-unmatched-views] [role=tab]').tabIndex===0; })()`));
  await evaluate(`(() => { window.selectionEvents=[]; window.selectionEventController=new AbortController();
    for(const type of ['input','segmentchange','viewchange']) document.addEventListener(type,event=>{
      window.selectionEvents.push({type,detail:event.detail??null});
    },{signal:window.selectionEventController.signal}); })()`);
  const baseline=await listeners('document');
  await type('A query & value');
  const beforeClear=await evaluate('window.selectionEvents.filter(event=>event.type==="input").length');
  await evaluate(`document.querySelector('${query}').closest('.search-input').querySelector('[data-bml-ref=clear]').click()`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${query}') && document.querySelector('${query}').value==='' && document.querySelector('${query}').closest('.search-input').querySelector('[data-bml-ref=clearAction]').hidden`));
  assert.equal(await evaluate('window.selectionEvents.filter(event=>event.type==="input").length'),beforeClear+1,'Clear emits one ordinary input event');
  await type('Draft query');
  await evaluate(`window.selectionInput=document.querySelector('${query}'); window.selectionGroup=document.querySelector('${group}');
    window.selectionViews=document.querySelector('${views}'); document.querySelector('${query}').setSelectionRange(2,5)`);
  await click('[data-selection-update=light]');
  await until(`document.querySelector('[data-selection-updates=light]').textContent.includes('Updates 1')`);
  assert.deepEqual(await evaluate(`(() => {const input=document.querySelector('${query}');return [input===window.selectionInput,input.value,input.selectionStart,input.selectionEnd,document.activeElement===input]})()`),
    [true,'Draft query',2,5,true],'Unrelated action retains query DOM, draft, selection and focus');
  await click(`${group} [data-value=paths]`);
  await until(`document.querySelector('${group}').dataset.value==='paths'`);
  assert.equal(await evaluate(`document.querySelector('${query}').value`),'Draft query');
  assert.equal(await evaluate(`document.querySelector('${group} input').value`),'paths');
  assert.equal(await evaluate(`document.querySelector('[data-mode=purple] input').value`),'content');
  await evaluate(`document.querySelector('${group} [aria-checked=true]').focus()`);
  await key('ArrowRight');
  await until(`document.querySelector('${group}').dataset.value==='content'`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${group} [data-value=content]')`));
  await key('End');
  await until(`document.querySelector('${group}').dataset.value==='paths'`);
  await key('Home');
  await until(`document.querySelector('${group}').dataset.value==='content'`);
  await key('ArrowLeft');
  await until(`document.querySelector('${group}').dataset.value==='paths'`);
  assert.equal(await evaluate('window.selectionEvents.filter(event=>event.type==="segmentchange").length'),5,'Each activation emits exactly one source-compatible event');
  await evaluate(`document.querySelector('[data-draft-mode=light] [data-value=Two]').click()`);
  await click('[data-selection-update=light]');
  await until(`document.querySelector('[data-selection-updates=light]').textContent.includes('Updates 2')`);
  assert.ok(await evaluate(`document.querySelector('[data-draft-mode=light] input').value==='Two' && document.querySelector('[data-draft-mode=light] [data-value=Two]').getAttribute('aria-checked')==='true'`),'A preserved unsaved segment choice survives unchanged server props');
  await evaluate(`document.querySelector('[data-selection-form=light]').requestSubmit()`);
  await until(`document.querySelector('[data-search-result=light]').textContent.includes('Submitted 1: Draft query / paths')`);
  await type('Rejected & retained');
  await click('[data-search-fail=light]');
  await until(`document.querySelector('[data-search-error=light]')?.textContent.includes('Search rejected')`);
  assert.equal(await evaluate(`document.querySelector('${query}').value`),'Rejected & retained');
  await click('[data-selection-disable=light]');
  await until(`document.querySelector('${query}').disabled`);
  assert.ok(await evaluate(`document.querySelector('${query}').closest('.search-input').querySelector('[data-bml-ref=clear]').disabled`));
  await click('[data-selection-disable=light]');
  await until(`!document.querySelector('${query}').disabled`);
  await click(`${views} [data-tab=Source]`);
  await until(`document.querySelector('${views}').dataset.selected==='Source' && !document.querySelector('[data-view-panel=light][data-view=Source]').hidden`);
  await evaluate(`document.querySelector('${views} [data-tab=Source]').focus()`);
  await key('ArrowRight');
  await until(`document.querySelector('${views}').dataset.selected==='Details'`);
  await key('ArrowRight');
  await until(`document.querySelector('${views}').dataset.selected==='Preview'`);
  await key('End');
  await until(`document.querySelector('${views}').dataset.selected==='Details'`);
  await key('Home');
  await until(`document.querySelector('${views}').dataset.selected==='Preview'`);
  await key('ArrowLeft');
  await until(`document.querySelector('${views}').dataset.selected==='Details'`);
  assert.deepEqual(await evaluate('window.selectionEvents.filter(event=>event.type==="viewchange").map(event=>event.detail)'),['Source','Details','Preview','Details','Preview','Details']);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${views} [data-tab=Details]') && document.querySelector('[data-views=purple] [aria-selected=true]').dataset.tab==='Preview'`));
  await click('[data-selection-change=light]');
  await until(`document.querySelector('${query}').value==='Changed light' && document.querySelector('${views} [aria-selected=true]').dataset.tab==='Source'`);
  assert.ok(await evaluate(`document.querySelector('${query}').placeholder==='Changed placeholder' && document.querySelector('${query}').getAttribute('aria-label')==='Changed search label' && document.querySelector('${group} [aria-checked=true]').dataset.value==='paths' && document.querySelector('${group}')===window.selectionGroup && document.querySelector('${views}')===window.selectionViews`),'Changed controlled props update retained component state');
  assert.ok(await evaluate(`(() => { const tabs=[...document.querySelectorAll('[role=tab]')];const ids=tabs.filter(tab=>tab.id).map(tab=>tab.id);
    return ids.length===6&&new Set(ids).size===6&&tabs.filter(tab=>tab.id).every(tab=>document.getElementById(tab.getAttribute('aria-controls'))?.getAttribute('aria-labelledby')===tab.id); })()`),'Caller panel relationships survive patches without duplicate IDs');
  assert.deepEqual(await evaluate(`['light','purple'].map(palette=>{const input=document.querySelector('[data-search-query='+palette+']');const radio=document.querySelector('[data-mode='+palette+'] [aria-checked=true]');
    return [getComputedStyle(input.closest('.search-input')).backgroundColor,getComputedStyle(radio).backgroundColor];})`),
    [['rgb(248, 250, 252)','rgb(226, 232, 240)'],['rgb(10, 12, 18)','rgb(29, 34, 48)']]);
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('alternate')`);
  assert.deepEqual(await evaluate(`(() => { const input=document.querySelector('${query}');input.focus();return [getComputedStyle(input.closest('.search-input')).backgroundColor,getComputedStyle(input).color,
    getComputedStyle(input.closest('.search-input')).borderTopColor,getComputedStyle(document.querySelector('${views} [aria-selected=true]'),'::after').backgroundColor,
    getComputedStyle(document.querySelector('[data-views=purple] [aria-selected=true]'),'::after').backgroundColor]; })()`),
    ['rgb(239, 246, 255)','rgb(30, 58, 138)','rgb(37, 99, 235)','rgb(37, 99, 235)','rgb(192, 132, 252)']);
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await until(`document.documentElement.scrollWidth<=innerWidth && document.querySelector('${query}').closest('.search-input').getBoundingClientRect().right<=innerWidth`);
    for(const [selector,role,selected] of [['[data-long-segments]','radio','aria-checked'],['[data-long-views]','tab','aria-selected']]) {
      await evaluate(`document.querySelector('${selector} [role=${role}]').focus()`);
      await key('End');
      const geometry=await evaluate(`(()=>{const root=document.querySelector('${selector}'),button=root.querySelector('[${selected}=true]');return {root:root.getBoundingClientRect().toJSON(),button:button.getBoundingClientRect().toJSON(),clientHeight:root.clientHeight,focused:document.activeElement===button};})()`);
      assert.ok(geometry.focused&&geometry.button.right<=geometry.root.right+1&&geometry.button.left>=geometry.root.left-1,JSON.stringify(geometry));
      if(role==='radio')assert.ok(geometry.clientHeight>=28&&geometry.button.top>=geometry.root.top&&geometry.button.bottom<=geometry.root.top+geometry.clientHeight+1,JSON.stringify(geometry));
    }
    const shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`selection-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  await evaluate(`window.removedSelectionRoots=[document.querySelector('${query}').closest('.search-input'),document.querySelector('${group}'),document.querySelector('${views}')]; window.removedSelectionValue=document.querySelector('${group} input')`);
  await click('[data-selection-toggle=light]');
  await until(`!document.querySelector('${query}')`);
  for(const index of [0,1,2]) assert.equal(await listeners(`window.removedSelectionRoots[${index}]`,['click','keydown','bml:update']),0,'Unmount removes helper-owned root event handlers');
  await evaluate(`window.removedSelectionRoots[1].querySelector('[data-value=content]').click()`);
  assert.equal(await evaluate('window.removedSelectionValue.value'),'paths','Detached controls no longer handle choices');
  for(let cycle=0;cycle<3;cycle++) {
    await click('[data-selection-toggle=light]');
    await until(`document.querySelector('${query}') && !document.querySelector('${query}').closest('.search-input').querySelector('[data-bml-ref=clear]').hasAttribute('title')`);
    assert.equal(await listeners('document'),baseline,'Remount does not accumulate document listeners');
    if(cycle<2){await click('[data-selection-toggle=light]');await until(`!document.querySelector('${query}')`);}
  }
  await evaluate('window.selectionEventController.abort()');
  const pendingFocus=[];
  const originalCookies=(await call('Network.getCookies',{urls:[base]})).cookies.map(cookie=>({
    name:cookie.name,value:cookie.value,domain:cookie.domain,path:cookie.path,
    secure:cookie.secure,httpOnly:cookie.httpOnly,
    ...(cookie.sameSite?{sameSite:cookie.sameSite}:{}),
    ...(cookie.expires>0?{expires:cookie.expires}:{}),
  }));
  try {
  for(const width of [1440,390])for(const palette of ['light','purple']) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await call('Network.clearBrowserCookies');
    await call('Page.navigate',{url:base+'/selection'});
    const tabs=`document.querySelector('[data-views=${palette}]')`;
    const panel=`document.querySelector('[data-view-panel=${palette}][data-view=Source]')`;
    await until(`document.readyState==='complete'&&${tabs}?.querySelector('[role=tab]')?.hasAttribute('aria-controls')`);
    await evaluate(`(()=>{
      const original=window.fetch.bind(window);
      window.fetch=async(...args)=>{
        const response=await original(...args);
        if(String(args[0]).includes('/_bml/action')){
          window.fetch=original;
          await new Promise(resolve=>window.releaseSelectionTab=resolve);
        }
        return response;
      };
      ${tabs}.querySelector('[data-tab=Preview]').focus();
    })()`);
    await key('ArrowRight');
    await until(`typeof window.releaseSelectionTab==='function'`);
    assert.equal(await evaluate(`!${panel}.hidden`),true,'Panel visibility follows local selection before the response');
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'Tab',windowsVirtualKeyCode:9});
    assert.equal(await evaluate(`document.activeElement===${panel}`),true,'Plain panel text is keyboard reachable while the update is pending');
    await evaluate(`window.selectionPanel=${panel};window.releaseSelectionTab()`);
    await until(`${tabs}.dataset.selected==='Source'&&${panel}.hasAttribute('aria-labelledby')`);
    const result=await evaluate(`({focused:document.activeElement===${panel},retained:window.selectionPanel===${panel},visible:!${panel}.hidden})`);
    pendingFocus.push({width,palette,...result});
    await writeFile(join(artifacts,`${mode}-selection-pending-focus.json`),JSON.stringify(pendingFocus,null,2));
    assert.ok(result.focused&&result.retained&&result.visible,JSON.stringify(result));
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'Tab',windowsVirtualKeyCode:9,modifiers:8});
    assert.equal(await evaluate(`document.activeElement===${tabs}.querySelector('[data-tab=Source]')`),true,'Shift+Tab returns to the selected tab after the patch');
  }
  } finally {
    await call('Network.clearBrowserCookies');
    await call('Network.setCookies',{cookies:originalCookies});
  }
}
