import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Read-only CodeMirror against binary library assets and application-owned viewer options. */
export async function checkSource({ call, evaluate, until: poll, base, mode, artifacts }) {
  const until=expression=>poll(`Boolean(${expression})`);
  const root='document.querySelector("[data-source=light]")';
  const content=`${root}.querySelector('.cm-content')`;
  const active=`${root}.querySelector('.cm-activeLine')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async (value,modifiers=0)=>{
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:value,modifiers,windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,Backspace:8}[value]});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:value,modifiers,windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,Backspace:8}[value]});
  };
  const listeners=async (expression,filter)=>{
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    return result.listeners.filter(listener=>!filter||filter.includes(listener.type)).length;
  };
  const probe=await call('Page.addScriptToEvaluateOnNewDocument',{source:`(() => {
    window.sourceObservers=new Set();const Base=MutationObserver;
    window.MutationObserver=class extends Base {observe(...args){super.observe(...args);window.sourceObservers.add(this);}disconnect(){super.disconnect();window.sourceObservers.delete(this);}};
  })()`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/source?line=70'});
  await until(`document.readyState==='complete'&&${root}?.hasAttribute('data-source-ready')&&document.querySelector('[data-source-plain][data-source-ready]')&&${active}?.textContent.includes('return')`);
  assert.deepEqual(await evaluate(`(() => {const root=${root},content=${content},wrap=root.querySelector('[data-bml-ref=wrap]');return {
    readonly:content.getAttribute('aria-readonly'),editable:content.isContentEditable,label:content.getAttribute('aria-label'),tabindex:content.tabIndex,
    forwarded:root.title==='Application source'&&root.classList.contains('source-example'),fallback:!!root.querySelector('[data-bml-ref=fallback]'),
    line:root.querySelector('.cm-activeLine').textContent.trim(),wrapping:wrap.getAttribute('aria-pressed'),
    sizes:['short','medium','long'].map(name=>document.querySelector('[data-source-'+name+']').getBoundingClientRect().height),
    bare:!document.querySelector('[data-source-bare] .source-toolbar')&&document.querySelector('[data-source-bare] .cm-editor').getBoundingClientRect().height<=800,
    inferred:!!document.querySelector('[data-source-short] .source-keyword'),plain:!document.querySelector('[data-source-plain] .source-keyword'),
    empty:document.querySelector('[data-source-empty] .cm-line').textContent,
    colors:[getComputedStyle(root.querySelector('.cm-editor')).backgroundColor,getComputedStyle(root.querySelector('.source-keyword')).color,
      getComputedStyle(document.querySelector('[data-source=purple] .cm-editor')).backgroundColor,getComputedStyle(document.querySelector('[data-source=purple] .source-keyword')).color],
  };})()`),{readonly:'true',editable:false,label:'File source: Example.txt',tabindex:0,forwarded:true,fallback:false,
    line:'return "Hello, " + name;',wrapping:'false',sizes:[110,180,240],bare:true,inferred:true,plain:true,empty:'',
    colors:['rgb(255, 255, 255)','rgb(126, 34, 206)','rgb(16, 19, 28)','rgb(233, 213, 255)']});
  await until('document.querySelector("[data-source-literals] .source-constant")&&document.querySelector("[data-source-markup] .source-link")');
  assert.equal(await evaluate('document.querySelector("[data-source-literals] .source-constant").textContent'),'true','Boolean literals retain their syntax category');
  assert.deepEqual(await evaluate(`(() => {const root=document.querySelector('[data-source-markup]'),link=[...root.querySelectorAll('.source-link')].find(e=>e.textContent.includes('Guide')),marker=root.querySelector('.source-string');return [[...root.querySelectorAll('.source-link')].some(e=>e.textContent.includes('Guide')),getComputedStyle(link).textDecorationLine,marker.textContent];})()`),[true,'underline','#'],'Markdown links retain underlines and marker highlighting');
  const colors=await evaluate(`(() => {const style=document.createElement('style');style.textContent='[data-source-literals], [data-source-markup] {--source-constant:#6b3e8f;--source-link:#145c72;--source-string:#377548;}';document.head.append(style);try{return [getComputedStyle(document.querySelector('[data-source-literals] .source-constant')).color,getComputedStyle([...document.querySelectorAll('[data-source-markup] .source-link')].find(e=>e.textContent.includes('Guide'))).color,getComputedStyle(document.querySelector('[data-source-markup] .source-string')).color];}finally{style.remove();}})()`);
  assert.deepEqual(colors,['rgb(107, 62, 143)','rgb(20, 92, 114)','rgb(55, 117, 72)'],'Consumer CSS controls literal and link syntax colors');
  assert.equal(await listeners('window',['beforeunload']),0,'A source viewer adds no dirty-navigation handler');
  await evaluate(`window.sourceTextBefore=${content}.textContent;${content}.focus()`);
  await call('Input.insertText',{text:'SHOULD NOT EDIT'});await key('Backspace');
  assert.ok(await evaluate(`${content}.textContent===window.sourceTextBefore`),'Native typing and deletion cannot mutate the read-only source');
  const mod=await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2');
  await key('f',mod);await until(`${root}.querySelector('.cm-search input')`);
  await evaluate(`(() => {const input=${root}.querySelector('.cm-search input');input.value='row50';input.dispatchEvent(new Event('change',{bubbles:true}));})()`);
  await key('Enter');await until(`${root}.querySelector('.cm-searchMatch-selected')?.textContent==='row50'`);
  assert.equal(await evaluate(`getComputedStyle(${root}.querySelector('.cm-searchMatch-selected')).backgroundColor`),'rgb(191, 219, 254)');
  await key('Escape');await until(`!${root}.querySelector('.cm-search')`);
  await evaluate(`${root}.querySelector('[data-bml-ref=wrap]').click()`);
  assert.ok(await evaluate(`${root}.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')==='true'&&!!${root}.querySelector('.cm-lineWrapping')`));
  assert.equal(await evaluate('document.querySelector("[data-source=purple] [data-bml-ref=wrap]").getAttribute("aria-pressed")'),'false');
  await evaluate(`window.savedSource=${root};window.savedCodeMirror=${root}.querySelector('.cm-editor');${content}.focus()`);
  await click('[data-source-update=light]');await until('document.querySelector("[data-source-update=light]").textContent.includes("Update 1")');
  assert.ok(await evaluate(`${root}===window.savedSource&&${root}.querySelector('.cm-editor')===window.savedCodeMirror&&document.activeElement===${content}&&${root}.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')==='true'`),'Unrelated patches retain the viewer, keyboard focus and local wrap choice');
  await click('[data-source-line=light]');await until(`${root}.dataset.line==='50'&&${active}.textContent.includes('row50')`);
  await click('[data-source-wrap=light]');await until(`${root}.hasAttribute('data-wrapped')`);
  await click('[data-source-wrap=light]');await until(`!${root}.querySelector('.cm-lineWrapping')&&${root}.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')==='false'`);
  await click('[data-source-language=light]');await until(`${root}.dataset.language==='text'&&!${root}.querySelector('.source-keyword')`);
  await click('[data-source-language=light]');await until(`${root}.dataset.language==='typescript'&&${root}.querySelector('.source-keyword')`);
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('alternate');${content}.focus()`);
  assert.deepEqual(await evaluate(`(() => {const root=${root};return [getComputedStyle(root.querySelector('.cm-editor')).backgroundColor,
    getComputedStyle(root.querySelector('.cm-editor')).color,getComputedStyle(root.querySelector('.cm-gutters')).color,
    getComputedStyle(root.querySelector('.source-keyword')).color,getComputedStyle(root.querySelector('.cm-activeLine')).backgroundColor];})()`),
    ['rgb(239, 246, 255)','rgb(30, 58, 138)','rgb(30, 64, 175)','rgb(29, 78, 216)','rgba(0, 0, 0, 0)']);
  await key('Escape');await key('Tab');
  assert.ok(await evaluate(`!${root}.contains(document.activeElement)`),'Escape then Tab restores native focus navigation');
  await evaluate(`document.querySelector('[data-source-bare] .cm-content').focus()`);await key('Escape');await key('Tab');
  assert.ok(await evaluate(`!document.querySelector('[data-source-bare]').contains(document.activeElement)`),'Bare viewers retain native Tab exit');
  await evaluate(`document.querySelector('[data-source-update=light]').focus()`);
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await evaluate('window.scrollTo(0,0)');await until('document.documentElement.scrollWidth<=innerWidth');
    const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`source-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  const baseline=await listeners('document'),observers=await evaluate('window.sourceObservers.size');
  await evaluate(`window.removedViewer=${root};window.removedCodeMirror=${root}.querySelector('.cm-editor')`);
  await click('[data-source-toggle=light]');await until(`!${root}&&window.sourceObservers.size<${observers}`);
  assert.equal(await listeners('window.removedViewer'),0,'Unmount removes scoped viewer listeners');
  assert.ok(await evaluate('!window.removedCodeMirror.isConnected&&!window.removedViewer.querySelector(".cm-editor")'),'CodeMirror destroy removes its editor DOM');
  for(let cycle=0;cycle<3;cycle++) {
    await click('[data-source-toggle=light]');await until(`${root}?.hasAttribute('data-source-ready')&&window.sourceObservers.size===${observers}`);
    assert.equal(await listeners('document'),baseline,'Remount does not accumulate document listeners');
    if(cycle<2){await click('[data-source-toggle=light]');await until(`!${root}&&window.sourceObservers.size<${observers}`);}
  }
  await evaluate(`window.oldSource=${root}`);await click('[data-source-replace=light]');
  await until(`${root}?.hasAttribute('data-source-ready')&&${content}.textContent.includes('replacement = 1')`);
  assert.ok(await evaluate(`${root}!==window.oldSource&&!window.oldSource.isConnected`),'A caller content revision resets the preserved viewer');
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:probe.identifier});
  const searchReports=[],panels=[],cookies=(await call('Network.getAllCookies')).cookies;
  const panelGeometry=viewer=>evaluate(`(()=>{const root=${viewer},panel=root.querySelector('.cm-search');return {
    height:root.offsetHeight,panel:panel.getBoundingClientRect().toJSON(),viewer:root.getBoundingClientRect().toJSON(),
    viewport:root.querySelector('.cm-scroller').clientHeight,font:getComputedStyle(panel).fontFamily,
    targets:[...panel.children].filter(el=>el.matches('input,button,label')).map(el=>({
      kind:el.tagName,box:el.getBoundingClientRect().toJSON(),fontSize:parseFloat(getComputedStyle(el).fontSize)}))};})()`);
  const checkPanel=(state,height=30)=>{
    assert.equal(state.targets.length,8);assert.ok(state.viewport>=44&&state.panel.bottom<=state.viewer.bottom+0.5);
    for(const target of state.targets)assert.ok(target.box.height>=(target.kind==='BUTTON'&&height===30?24:height)-0.5&&target.fontSize>=12&&
      target.box.left>=state.panel.left+7.5&&target.box.right<=state.panel.right-7.5,JSON.stringify(target));
  };
  try {
    for(const width of [1440,390])for(const palette of ['light','purple']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
      await call('Page.navigate',{url:base+'/source'});
      const viewer=`document.querySelector('[data-source=${palette}]')`;
      await until(`${viewer}?.hasAttribute('data-source-ready')`);
      const snapshot=()=>evaluate(`(()=>{const root=${viewer},box=root.getBoundingClientRect();return {
        source:root.querySelector('textarea').value,width:box.width,height:box.height,
        foreground:getComputedStyle(root.querySelector('.cm-editor')).color,
        keyword:getComputedStyle(root.querySelector('.source-keyword')).color,
        sibling:getComputedStyle(document.querySelector('[data-source=${palette==='light'?'purple':'light'}] .source-keyword')).color};})()`);
      const before=await snapshot();
      await evaluate(`${viewer}.scrollIntoView({block:'center'});${viewer}.querySelector('.cm-content').focus()`);
      for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'f',windowsVirtualKeyCode:70,
        modifiers:await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2')});
      await until(`${viewer}.querySelector('input[name=search]')===document.activeElement`);
      const panel=await panelGeometry(viewer);checkPanel(panel);
      for(const character of 'source')for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{
        type,key:character,windowsVirtualKeyCode:character.toUpperCase().charCodeAt(0),...(type==='keyDown'?{text:character}:{})});
      await until(`${viewer}.querySelectorAll('.cm-searchMatch').length>1`);await key('Enter');
      await until(`${viewer}.querySelector('.cm-searchMatch-selected')`);
      const colors=()=>evaluate(`Array.from(${viewer}.querySelectorAll('.cm-searchMatch,.cm-searchMatch span')).map(el=>getComputedStyle(el).color)`);
      const normal=await colors();
      assert.ok(normal.length>2&&normal.every(color=>color===before.foreground),'Highlighted syntax uses the consuming viewer foreground');
      await evaluate(`window.consumerSearchStyle=document.createElement('style');window.consumerSearchStyle.textContent='[data-search-colors] {--source-search-fg:#fff;--source-search-match:#281250;--source-selection:#481475;--control-height-sm:36px;--control-height-xs:36px;--font-sans:system-ui,sans-serif;}';document.head.append(window.consumerSearchStyle);${viewer}.setAttribute('data-search-colors','')`);
      const overridden=await colors();assert.ok(overridden.every(color=>color==='rgb(255, 255, 255)'));
      const sized=await panelGeometry(viewer);checkPanel(sized,36);assert.ok(sized.font.includes('system-ui'));
      assert.deepEqual(await snapshot(),before,'Search overrides preserve source, geometry and outside/sibling syntax');
      await evaluate(`${viewer}.removeAttribute('data-search-colors');window.consumerSearchStyle.remove()`);
      await key('Escape');await until(`!${viewer}.querySelector('.cm-search')&&!${viewer}.querySelector('.cm-searchMatch')`);
      assert.deepEqual(await snapshot(),before);
      searchReports.push({width,palette,foreground:before.foreground,normal,overridden});
      panels.push({width,palette,panel,sized});
    }
    for(const width of [1440,390])for(const kind of ['short','medium','long','compact-bare']) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
      const viewer=`document.querySelector('[data-source-${kind}]')`;
      const before=await evaluate(`({height:${viewer}.offsetHeight,source:${viewer}.querySelector('textarea').value})`);
      await evaluate(`${viewer}.scrollIntoView({block:'center'});${viewer}.querySelector('.cm-content').focus()`);
      for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'f',windowsVirtualKeyCode:70,
        modifiers:await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2')});
      await until(`${viewer}.querySelector('input[name=search]')===document.activeElement`);
      const panel=await panelGeometry(viewer);checkPanel(panel);assert.ok(panel.height>=240);
      await key('Escape');await until(`!${viewer}.querySelector('.cm-search')`);
      assert.deepEqual(await evaluate(`({height:${viewer}.offsetHeight,source:${viewer}.querySelector('textarea').value})`),before);
      panels.push({width,kind,before,panel});
    }
  } finally {
    await call('Network.clearBrowserCookies');if(cookies.length)await call('Network.setCookies',{cookies});
    await writeFile(join(artifacts,`source-search-${mode}.json`),JSON.stringify(searchReports,null,2));
    await writeFile(join(artifacts,`source-panels-${mode}.json`),JSON.stringify(panels,null,2));
  }
}
