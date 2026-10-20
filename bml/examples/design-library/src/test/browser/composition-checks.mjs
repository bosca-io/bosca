import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Compiled tree navigation, native comments, server actions and timestamp presentation. */
export async function checkComposition({ call, evaluate, until, base, mode, artifacts }) {
  const field='document.querySelector("[data-comment=light]")';
  const local='document.querySelector("[data-local-tree=light]")';
  const row=path=>`${local}.querySelector('[data-path=${JSON.stringify(path)}]')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async (value,modifiers=0)=>{
    const windowsVirtualKeyCode={Enter:13,ArrowDown:40,ArrowUp:38,ArrowRight:39,ArrowLeft:37,Home:36,End:35}[value];
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:value,modifiers,windowsVirtualKeyCode});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:value,modifiers,windowsVirtualKeyCode});
  };
  const type=value=>evaluate(`(() => {const field=${field};field.focus();field.value=${JSON.stringify(value)};field.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const focused=path=>evaluate(`document.activeElement===${row(path)}.querySelector('.ui-tree-row')`);
  const listeners=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    return result.listeners.length;
  };
  const probe=await call('Page.addScriptToEvaluateOnNewDocument',{source:`(() => {
    window.compositionObservers=new Set();const Base=MutationObserver;
    window.MutationObserver=class extends Base {observe(...args){super.observe(...args);window.compositionObservers.add(this);}disconnect(){super.disconnect();window.compositionObservers.delete(this);}};
  })()`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/composition'});
  await until(`document.readyState==='complete'&&${field}?.value==='Seed'&&document.querySelector('[data-readonly-comment]').readOnly&&${row('docs/guide.md')}.hidden`);
  assert.deepEqual(await evaluate(`(() => {const field=${field},root=${local},link=root.querySelector('[data-file-link]');return {
    label:root.querySelector('[role=tree]').getAttribute('aria-label'),level:root.querySelector('[data-path="src/lib/helper.ts"]').getAttribute('aria-level'),
    tabStops:[...root.querySelectorAll('.ui-tree-row')].filter(row=>row.tabIndex===0).length,selected:root.querySelector('[aria-selected=true]').dataset.path,
    rowHeight:root.querySelector('.ui-tree-row').getBoundingClientRect().height,depthPadding:getComputedStyle(root.querySelector('[data-path="src/lib/helper.ts"] .ui-tree-row')).paddingInlineStart,
    native:link instanceof HTMLAnchorElement&&link.classList.contains('file-link')&&link.getAttribute('href')==='/composition-target',
    field:[field.name,field.required,field.getAttribute('aria-label'),field.spellcheck,field.id,field.getAttribute('aria-describedby')],
    times:[document.querySelector('[data-default-time]').textContent,document.querySelector('[data-custom-time]').textContent,document.querySelector('[data-invalid-time]').textContent],
    inherited:[getComputedStyle(document.querySelector('[data-inherited-time]')).fontSize,getComputedStyle(document.querySelector('[data-inherited-time]')).color],
    nativeTime:!document.querySelector('#native-time').hasAttribute('data-bml-c'),empty:!document.querySelector('[data-empty-tree] [role=treeitem]'),
  };})()`),{label:'Local files light',level:'3',tabStops:1,selected:'src/main.kt',rowHeight:30,depthPadding:'34px',native:true,
    field:['body',true,'Comment light',false,'comment-light-0','comment-context comment-light-0-hint'],
    times:['Oct 18, 13:15 UTC','2026-10-18 08:15 -05:00','not a timestamp'],inherited:['17px','rgb(21, 128, 61)'],nativeTime:true,empty:true});
  assert.ok(await evaluate(`document.querySelector('[data-disabled-tree] [tabindex="0"]').closest('[role=treeitem]').dataset.path==='ready'`));
  await evaluate(`document.querySelector('[data-disabled-tree] [tabindex="0"]').focus()`);
  await key('End');await key('ArrowUp');
  assert.equal(await evaluate(`document.activeElement.closest('[role=treeitem]').dataset.path`),'ready/file','Keyboard traversal skips unavailable native buttons');
  await key('Home');
  assert.equal(await evaluate(`document.activeElement.closest('[role=treeitem]').dataset.path`),'ready');
  await evaluate(`${local}.querySelector('[tabindex="0"]').focus()`);await key('Home');assert.ok(await focused('src'));
  await key('ArrowRight');assert.ok(await focused('src/lib'));
  await key('ArrowRight');assert.ok(await focused('src/lib/helper.ts'));
  await key('ArrowLeft');assert.ok(await focused('src/lib'));
  await key('ArrowLeft');assert.ok(await evaluate(`${row('src/lib/helper.ts')}.hidden&&${row('src/lib')}.getAttribute('aria-expanded')==='false'`));
  await key('ArrowRight');assert.ok(await focused('src/lib/helper.ts'));
  await key('End');await key('ArrowDown');assert.ok(await focused('long.txt'));
  await key('Home');await key('ArrowUp');assert.ok(await focused('src'));
  await key('d');assert.ok(await focused('docs'));await key('ArrowRight');assert.ok(await focused('docs/guide.md'));
  await key('ArrowLeft');await key('ArrowLeft');assert.ok(await evaluate(`${row('docs/guide.md')}.hidden`));
  await key('Home');await key('l');assert.ok(await focused('src/lib'));await key('l');assert.ok(await focused('LICENSE'));
  await key('Home');await key('ArrowRight');await key('ArrowLeft');
  await evaluate(`window.savedLocalTree=${local};${row('long.txt')}.querySelector('a').focus();${local}.scrollTop=${local}.scrollHeight;window.savedTreeScroll=${local}.scrollTop`);
  assert.ok(await evaluate('window.savedTreeScroll>0'));await click('[data-composition-update=light]');
  await until(`document.querySelector('[data-composition-update=light]').textContent.includes('Update 1')`);
  assert.ok(await evaluate(`${local}===window.savedLocalTree&&${row('src/lib/helper.ts')}.hidden&&document.activeElement===${row('long.txt')}.querySelector('a')&&${local}.scrollTop===window.savedTreeScroll`),'Local collapse state, scroll and focus survive an unrelated server update');
  assert.ok(await evaluate(`!document.querySelector('[data-local-tree=purple] [data-path="src/lib/helper.ts"]').hidden`),'Separate tree instances retain independent state');
  await evaluate(`window.remoteDirectory=document.querySelector('[data-remote-directory=light]');window.remoteDirectory.focus()`);
  await key('ArrowRight');
  await until(`document.activeElement===document.querySelector('[data-server-tree=light] [data-path="remote/loaded.txt"] a')`);
  assert.ok(await evaluate(`window.remoteDirectory===document.querySelector('[data-remote-directory=light]')`),'Async child focus follows the preserved native disclosure control');
  await key('ArrowLeft');await key('ArrowLeft');
  await until(`!document.querySelector('[data-server-tree=light] [data-path="remote/loaded.txt"]')`);
  assert.ok(await evaluate(`document.activeElement===window.remoteDirectory&&window.remoteDirectory.closest('[role=treeitem]').getAttribute('aria-expanded')==='false'`));
  await type('Draft & <body>');await evaluate(`window.savedComment=${field};${field}.setSelectionRange(3,7)`);
  await click('[data-composition-update=light]');await until(`document.querySelector('[data-composition-update=light]').textContent.includes('Update 2')`);
  assert.ok(await evaluate(`${field}===window.savedComment&&${field}.value==='Draft & <body>'&&${field}.selectionStart===3&&${field}.selectionEnd===7&&document.activeElement===${field}`),'Native comment identity, draft, caret and focus survive replacement of its form');
  assert.equal(await evaluate('document.querySelector("[data-comment=purple]").value'),'Seed');
  await type('   ');assert.ok(await evaluate('document.querySelector("[data-comment-submit=light]").disabled'));await key('Enter',2);
  assert.match(await evaluate('document.querySelector("[data-comment-result=light]").textContent'),/\(0\)/);
  await type(Array(30).fill('A long comment line').join('\n'));
  assert.ok(await evaluate(`${field}.getBoundingClientRect().height===320`),'Comment growth stops at the owned CSS maximum');
  await type('Posted draft');
  const hold=async ()=>evaluate(`(() => {const original=window.fetch.bind(window);window.fetch=async(...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseCompositionAction=resolve);}return original(...args);};})()`);
  await hold();await click('[data-comment-submit=light]');
  await until(`${field}.readOnly&&document.querySelector('[data-comment-form=light]').hasAttribute('data-bml-action-pending')`);
  assert.ok(await evaluate(`document.querySelector('[data-comment-submit=light]').disabled&&document.querySelector('[data-comment-aux=light]').disabled`));
  await key('Enter',2);await evaluate('window.releaseCompositionAction()');
  await until(`document.querySelector('[data-comment-result=light]').textContent.includes('Posted draft (1)')&&${field}.value===''`);
  assert.ok(await evaluate(`${field}.id==='comment-light-1'&&!${field}.readOnly&&document.querySelector('[data-comment-submit=light]').disabled`));
  await click('[data-comment-fail=light]');await until('document.querySelector("[data-composition=light]").hasAttribute("data-fail-next")');
  await type('Rejected draft');await evaluate(`${field}.setSelectionRange(2,5)`);await key('Enter',2);
  await until(`document.querySelector('[data-comment-form=light] [role=alert]')?.textContent.includes('Comment rejected')&&!${field}.readOnly`);
  assert.ok(await evaluate(`(() => {const field=${field};return field.value==='Rejected draft'&&field.selectionStart===2&&field.selectionEnd===5&&field.getAttribute('aria-invalid')==='true'&&
    field.getAttribute('aria-describedby')==='comment-context comment-light-1-hint comment-light-1-error'&&new FormData(field.form).get('body')==='Rejected draft'&&
    !document.querySelector('[data-comment-aux=light]').disabled&&[...field.closest('.comment-editor').querySelectorAll('button')].find(button=>button.textContent==='Unavailable').disabled;})()`),'Failed submission retains the native draft, caret and current error description');
  await hold();await key('Enter',2);await until(`${field}.readOnly`);
  assert.ok(await evaluate(`${field}.getAttribute('aria-invalid')==='false'&&document.querySelector('[data-comment-form=light] [role=alert]').hidden&&!${field}.getAttribute('aria-describedby').includes('-error')`));
  await evaluate('window.releaseCompositionAction()');await until(`document.querySelector('[data-comment-result=light]').textContent.includes('Rejected draft (2)')`);
  await click('[data-comment-lock=light]');await until('document.querySelector("[data-comment-form=light] .comment-editor").hasAttribute("data-submit-disabled")');
  await type('Permission draft');await key('Enter',2);
  assert.ok(await evaluate(`document.querySelector('[data-comment-submit=light]').disabled&&${field}.closest('.comment-editor').querySelector('small').textContent.trim()==='Comments are locked'`));
  await click('[data-comment-lock=light]');await until(`!document.querySelector('[data-comment-submit=light]').disabled`);
  await click('[data-comment-reset=light]');await until(`${field}.value===''&&${field}.getBoundingClientRect().height===78`);
  await click('[data-composition-fill=light]');await until(`${field}.value==='Server draft 3'`);
  await evaluate(`${field}.focus();document.querySelector('[data-palette=light]').classList.add('alternate')`);
  assert.equal(await evaluate(`getComputedStyle(${field}).outlineColor`),'rgb(37, 99, 235)');
  assert.deepEqual(await evaluate(`[getComputedStyle(${field}).backgroundColor,getComputedStyle(document.querySelector('[data-comment=purple]')).backgroundColor]`),['rgb(255, 255, 255)','rgb(16, 19, 28)']);
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:false});
    await evaluate('window.scrollTo(0,0)');await until('document.documentElement.scrollWidth<=innerWidth');
    const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`composition-${mode}-${width}.png`),Buffer.from(shot.data,'base64'));
  }
  const baseline=await listeners('document'),observers=await evaluate('window.compositionObservers.size');
  await evaluate(`window.removedComment=${field};window.removedTree=${local};window.removedServerTree=document.querySelector('[data-server-tree=light]')`);
  await click('[data-composition-toggle=light]');await until(`!${field}&&window.compositionObservers.size===${observers-1}`);
  for(const expression of ['window.removedComment','window.removedTree','window.removedServerTree']) assert.equal(await listeners(expression),0,'Unmount removes field and tree helper listeners');
  for(let cycle=0;cycle<3;cycle++) {
    await click('[data-composition-toggle=light]');await until(`${field}&&window.compositionObservers.size===${observers}`);
    assert.equal(await listeners('document'),baseline,'Repeated remounts do not accumulate document listeners');
    if(cycle<2){await click('[data-composition-toggle=light]');await until(`!${field}&&window.compositionObservers.size===${observers-1}`);}
  }
  await evaluate(`${local}.querySelector('[data-file-link]').focus()`);await key('Enter');
  await until('location.pathname==="/composition-target"&&document.querySelector("[data-file-destination]")');
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:probe.identifier});
}
