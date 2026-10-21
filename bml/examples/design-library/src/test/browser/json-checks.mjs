import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Structured editing, exact invalid drafts and form lifecycle through a compiled library. */
export async function checkJson({ call, evaluate, until: poll, base, mode, artifacts }) {
  const until=expression=>poll(`Boolean(${expression})`);
  const root='document.querySelector("[data-json=light]")';
  const field=`${root}.querySelector('textarea')`;
  const surface=`${root}.querySelector('.jse-main')`;
  const text=`${root}.querySelector('.cm-content')`;
  const treeSemantics=`(() => {const tree=${root}.querySelector('[role=tree]');return tree?.getAttribute('aria-label')==='Settings light'&&
    [...tree.querySelectorAll('[role=treeitem]')].every(item=>['tree','group'].includes(item.parentElement.getAttribute('role')))&&
    [...tree.querySelectorAll('.jse-json-node.jse-expanded')].every(item=>item.getAttribute('aria-expanded')==='true');})()`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async (value,modifiers=0)=>{
    for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:value,modifiers,
      windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,Backspace:8,a:65}[value]});
  };
  const replace=async value=>{
    await evaluate(`${text}.focus()`);
    await key('a',await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
    await call('Input.insertText',{text:value});
  };
  const listeners=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});return result.listeners.length;
  };
  const probe=await call('Page.addScriptToEvaluateOnNewDocument',{source:`(() => {
    window.jsonObservers=new Set();const Base=MutationObserver;
    window.MutationObserver=class extends Base {observe(...args){super.observe(...args);window.jsonObservers.add(this);}disconnect(){super.disconnect();window.jsonObservers.delete(this);}};
  })()`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/json'});
  await until(`document.readyState==='complete'&&${root}?.hasAttribute('data-json-ready')&&document.querySelector('[data-json-table] .jse-main')`);
  await until(treeSemantics);
  await evaluate(`document.querySelector('[data-json-table]').scrollIntoView({block:'center'})`);
  await until(`document.querySelector('[data-json-table] th[scope=row]')`);
  assert.ok(await evaluate(`(() => {const table=document.querySelector('[data-json-table] table');return table.getAttribute('aria-label')==='Readonly table'&&
    [...table.rows[0].cells].every(header=>header.tagName==='TH'&&header.scope==='col'&&header.textContent.trim())&&
    [...table.rows].slice(1).flatMap(row=>[...row.querySelectorAll('th')]).every(header=>header.scope==='row')&&
    [...document.querySelectorAll('[data-bml-json-editor] .jse-navigation-bar-arrow')].every(button=>button.getAttribute('aria-label'))&&
    !document.querySelector('[data-bml-json-editor] svg[role=presentation][aria-label=""]');})()`),
    'The packaged JSON editor exposes named navigation and native table headers');
  assert.ok(await evaluate(`(() => {const r=${root},f=${field};return f.hidden&&f.name==='settings'&&f.value.includes('<script>unsafe()</script>')&&
    r.title==='Application settings'&&r.classList.contains('json-example')&&r.getAttribute('aria-label')==='Settings light'&&
    r.querySelector('.jse-key')&&r.querySelector('.jse-value')&&!window.unsafe&&
    document.querySelector('[data-json-invalid] textarea').value==='{invalid: '&&document.querySelector('[data-json-empty] textarea').value===''&&
    document.querySelector('[data-json-invalid]').getAttribute('aria-invalid')==='true'&&document.querySelector('[data-json-empty]').getAttribute('aria-invalid')==='true';})()`));
  assert.deepEqual(await evaluate(`['light','purple'].map(p=>{const r=document.querySelector('[data-json='+p+']');return [
    getComputedStyle(r.querySelector('.jse-main')).backgroundColor,getComputedStyle(r.querySelector('.jse-key')).color,
    getComputedStyle(r.querySelector('.jse-value.jse-string')).color];})`),
    [['rgb(255, 255, 255)','rgb(126, 34, 206)','rgb(21, 128, 61)'],['rgb(16, 19, 28)','rgb(233, 213, 255)','rgb(134, 239, 172)']]);
  await click('[data-json-mode=light]');await until(`${text}&&${root}.dataset.mode==='text'`);
  const invalid='{ "draft": ';await replace(invalid);
  await until(`${field}.value===${JSON.stringify(invalid)}&&${root}.getAttribute('aria-invalid')==='true'`);
  await evaluate(`window.savedJson=${root};window.savedJsonEngine=${surface};${text}.focus()`);
  await click('[data-json-update=light]');await until('document.querySelector("[data-json-update=light]").textContent.includes("Update 1")');
  assert.ok(await evaluate(`${root}===window.savedJson&&${surface}===window.savedJsonEngine&&${field}.value===${JSON.stringify(invalid)}&&document.activeElement===${text}`));
  await click('[data-json-save=light]');await until(`${root}.dataset.error==='Invalid JSON; keep editing'`);
  assert.ok(await evaluate(`${field}.value===${JSON.stringify(invalid)}&&${text}.textContent===${JSON.stringify(invalid)}&&${root}.querySelector('[data-bml-ref=feedback]').textContent.includes('Invalid JSON; keep editing')`));
  const valid='{ "saved": "current", "count": 42 }';
  await evaluate(`(() => {const original=window.fetch.bind(window);window.fetch=async (...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseJsonSave=resolve;});}
    return original(...args);};})()`);
  await replace(valid);
  // Submit immediately after input: the form must not read a debounced old value.
  await evaluate(`document.querySelector('[data-json-form=light]').requestSubmit()`);
  await until(`${field}.readOnly&&${text}.getAttribute('aria-readonly')==='true'`);
  await evaluate('window.releaseJsonSave()');
  await until('document.querySelector("[data-json-save=light]").textContent.includes("Save 1")');
  assert.equal(await evaluate('document.querySelector("[data-json-saved=light]").textContent'),valid);
  assert.equal(await evaluate(`${root}.getAttribute('aria-invalid')`),'false');
  assert.equal(await evaluate(`getComputedStyle([...${text}.querySelectorAll('.cm-line span')].find(s=>s.textContent.includes('saved'))).color`),'rgb(126, 34, 206)');
  assert.ok(await evaluate(`document.querySelector('[data-json=purple] textarea').value.includes('greeting')`));
  await replace('{"unsaved":true}');await click('[data-json-reset=light]');await until(`${field}.value===${JSON.stringify(valid)}&&${text}.textContent===${JSON.stringify(valid)}`);
  await click('[data-json-replace=light]');await until(`${field}.value==='{"replacement":2}'&&${text}.textContent.includes('replacement')`);
  await click('[data-json-readonly=light]');await until(`${root}.hasAttribute('data-readonly')&&${text}.getAttribute('aria-readonly')==='true'`);
  await evaluate(`${root}.querySelector('button[title^="Switch to tree"]').click()`);
  await until(`${root}.querySelector('.jse-tree-mode')`);
  await until(treeSemantics);
  await evaluate(`${root}.querySelector('button[title^="Switch to text"]').click()`);
  await until(`${text}?.getAttribute('aria-readonly')==='true'`);
  await evaluate(`window.readonlyJson=${field}.value;${text}.focus()`);await call('Input.insertText',{text:'FORBIDDEN'});
  assert.ok(await evaluate(`${field}.value===window.readonlyJson`));
  await click('[data-json-readonly=light]');await until(`${text}.getAttribute('aria-readonly')==='false'`);
  await click('[data-json-disabled=light]');await until(`${root}.hasAttribute('data-disabled')&&${field}.disabled`);
  assert.ok(await evaluate(`${root}.querySelector('[data-bml-ref=editor]').inert&&!new FormData(document.querySelector('[data-json-form=light]')).has('settings')`));
  await click('[data-json-disabled=light]');await until(`!${field}.disabled&&${text}.getAttribute('aria-readonly')==='false'`);
  await evaluate(`${text}.focus()`);await key('Escape');await key('Tab');
  assert.ok(await evaluate(`document.activeElement!==${text}&&!${text}.contains(document.activeElement)`),'Escape then Tab leaves editable content');
  for(let i=0;i<20&&await evaluate(`${root}.contains(document.activeElement)`);i++) await key('Tab');
  assert.ok(await evaluate(`!${root}.contains(document.activeElement)`),'Native Tab navigation leaves the remaining editor controls');
  await click('[data-json-mode=light]');await until(`${root}.dataset.mode==='tree'&&${root}.querySelector('.jse-key')`);
  const cell=await evaluate(`(() => {const cell=${root}.querySelector('.jse-value.jse-number');cell.scrollIntoView({block:'center'});const r=cell.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  await call('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',buttons:1,clickCount:2,...cell});
  await call('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:2,...cell});
  await until(`${root}.querySelector('.jse-editable-div')`);
  await evaluate(`(() => {const cell=${root}.querySelector('.jse-editable-div');cell.focus();const range=document.createRange();range.selectNodeContents(cell);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);})()`);
  await call('Input.insertText',{text:'99'});await key('Enter');
  await until(`JSON.parse(${field}.value).replacement===99`);
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('alternate')`);
  assert.deepEqual(await evaluate(`(() => {const r=${root};return [getComputedStyle(r.querySelector('.jse-main')).backgroundColor,
    getComputedStyle(r.querySelector('.jse-key')).color,getComputedStyle(r).getPropertyValue('--jse-selection-background-color').trim()];})()`),
    ['rgb(239, 246, 255)','rgb(29, 78, 216)','#93c5fd']);
  await evaluate(`document.querySelector('[data-palette=light]').classList.add('json-accent-only');
    ${root}.querySelector('.jse-menu button').focus()`);
  assert.equal(await evaluate(`getComputedStyle(${surface}).outlineColor`), 'rgb(15, 118, 110)');
  assert.deepEqual(await evaluate(`(() => {
    const r=${root}, probe=document.createElement('span');r.append(probe);
    const color=value=>{probe.style.color=value;return getComputedStyle(probe).color;};
    const result=[color('var(--jse-theme-color)'),
      color('var(--jse-theme-color-highlight)')===color('color-mix(in oklch, #0f766e 80%, var(--fg-0))'),
      color('var(--jse-selection-background-color)')===color('color-mix(in oklch, #0f766e 25%, var(--bg-1))'),
      getComputedStyle(document.querySelector('[data-json=purple]')).getPropertyValue('--jse-theme-color').trim()];
    probe.remove();return result;
  })()`), ['rgb(15, 118, 110)', true, true, '#c084fc']);
  await evaluate(`document.querySelector('[data-palette=light]').classList.remove('json-accent-only')`);
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await evaluate('window.scrollTo(0,0)');await until('document.documentElement.scrollWidth<=innerWidth');
    assert.ok(await evaluate(`(() => {const cards=[...document.querySelectorAll('.json-theme .ui-card')].map(c=>c.getBoundingClientRect());
      const cases=document.querySelector('.json-cases').getBoundingClientRect();return cards.every(c=>c.bottom<=cases.top)&&
      [...document.querySelectorAll('.json-example')].every(r=>{const engine=r.querySelector('.jse-main').getBoundingClientRect(),form=r.closest('form').getBoundingClientRect(),save=r.closest('form').querySelector('[type=submit]').getBoundingClientRect();
        return Math.abs(engine.height-340)<1&&engine.bottom<=save.top&&save.bottom<=form.bottom;})&&
      (innerWidth>700||cards[0].bottom<=cards[1].top);})()`),'Editor viewports, actions and adjacent cards never overlap');
    const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`json-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  const baseline=await listeners('document'),observers=await evaluate('window.jsonObservers.size');
  await evaluate(`window.removedJson=${root};window.removedJsonEngine=${surface}`);
  await click('[data-json-toggle=light]');await until(`!${root}&&!window.removedJson.querySelector('.jse-main')&&window.jsonObservers.size<${observers}`);
  assert.equal(await listeners('window.removedJson'),0);
  assert.ok(await evaluate('!window.removedJsonEngine.isConnected'));
  for(let cycle=0;cycle<3;cycle++) {
    await click('[data-json-toggle=light]');await until(`${root}?.hasAttribute('data-json-ready')&&window.jsonObservers.size===${observers}`);
    assert.equal(await listeners('document'),baseline,'Remount does not accumulate document listeners');
    if(cycle<2){await click('[data-json-toggle=light]');await until(`!${root}&&window.jsonObservers.size<${observers}`);}
  }
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:probe.identifier});
}
