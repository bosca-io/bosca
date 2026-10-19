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
    ['rgb(239, 246, 255)','rgb(30, 58, 138)','rgb(30, 64, 175)','rgb(29, 78, 216)','rgb(219, 234, 254)']);
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
}
