import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Native comment fields in real caller forms, including pending and rejected actions. */
export async function checkComments({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const truth = expression => evaluate(`Boolean(${expression})`);
  const owner = id => `document.querySelector('[data-catalog-comment-owner=${id}]')`;
  const form = id => `${owner(id)}.querySelector('form')`;
  const field = id => `${form(id)}.querySelector('textarea')`;
  const toast = id => `${owner(id)}.querySelector('[data-catalog-comment-toast]')`;
  const post = id => `${form(id)}.querySelector('[type=submit]')`;
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key = async (value, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key: value, modifiers,
      windowsVirtualKeyCode: { Enter: 13, Escape: 27, Tab: 9 }[value] });
  };
  const edit = (id, value) => evaluate(`${field(id)}.value=${JSON.stringify(value)};${field(id)}.dispatchEvent(new Event('input',{bubbles:true}))`);
  const action = async (id, name, done) => {
    await evaluate(`window.commentAction=document.querySelector('[data-comment-${name}=${id}]');window.commentAction.click()`);
    await until(`!window.commentAction.isConnected&&(${done})`);
  };
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await navigate('/comments');
  await until(`${form('dark')}.dataset.bmlActionBound==='true'&&${post('dark')}.disabled`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`), '/comments');
  assert.ok(await truth(`${field('dark')}.required&&${field('dark')}.name==='body'&&${field('dark')}.getAttribute('aria-label')==='Example comment'&&${field('dark')}.id!==${field('light')}.id&&${field('dark')}.getAttribute('aria-describedby').split(' ').every(id=>document.getElementById(id))`));
  assert.ok(await truth(`document.getElementById('comment-readonly').readOnly&&document.getElementById('comment-unavailable').disabled&&!document.getElementById('comment-paused').readOnly&&document.getElementById('comment-invalid').getAttribute('aria-invalid')==='true'&&!window.catalogCommentExecuted&&!document.querySelector('.comment-editor script')&&document.getElementById('comment-escaped').value.includes('</textarea><script>window.catalogCommentExecuted = true;</script>')`));
  await evaluate(`document.querySelectorAll('[data-catalog-comment-owner] details').forEach(details=>{details.open=true;})`);
  await edit('dark', '   ');
  assert.ok(await truth(`${post('dark')}.disabled`));
  await edit('dark', 'A composing draft');
  await evaluate(`${field('dark')}.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',ctrlKey:true,isComposing:true,bubbles:true}))`);
  assert.ok(await truth(`!${form('dark')}.hasAttribute('data-bml-action-pending')&&${toast('dark')}.dataset.sequence==='0'`));
  const local = '  A locally edited comment.\nKeep the exact draft.  ';
  await edit('dark', local);
  await evaluate(`window.retainedComment=${field('dark')};${field('dark')}.focus();${field('dark')}.setSelectionRange(5,12)`);
  await action('dark', 'refresh', `${toast('dark')}.dataset.sequence==='1'&&${toast('dark')}.matches(':popover-open')`);
  assert.ok(await truth(`${field('dark')}===window.retainedComment&&document.activeElement===window.retainedComment&&${field('dark')}.selectionStart===5&&${field('dark')}.selectionEnd===12&&${field('dark')}.value===${JSON.stringify(local)}&&${owner('dark')}.querySelector('details').open`));
  assert.ok(await truth(`${field('light')}.value===''&&${toast('light')}.dataset.sequence==='0'`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await edit('dark', '  x  ');
  await evaluate(`${form('dark')}.requestSubmit()`);
  await until(`${field('dark')}.getAttribute('aria-invalid')==='true'&&!${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${field('dark')}.value==='  x  '&&!${toast('dark')}.matches(':popover-open')&&${toast('dark')}.dataset.sequence==='1'&&${field('dark')}.getAttribute('aria-describedby').includes(${field('dark')}.id+'-error')`));

  // Hold a real action so pending behavior and duplicate keyboard submission are observable.
  await action('dark', 'reject', `${owner('dark')}.querySelector('[data-comment-reject]').checked`);
  const rejected = '  Keep this rejected draft.\nIt can be retried.  ';
  await edit('dark', rejected);
  await evaluate(`(() => {const fetch=window.fetch.bind(window);window.commentRequests=0;window.fetch=async(...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.commentRequests++;if(window.commentRequests===1)await new Promise(resolve=>{window.releaseCommentPost=resolve;});}
    return fetch(...args);
  };window.restoreCommentFetch=()=>{window.fetch=fetch;};})()`);
  await evaluate(`${field('dark')}.focus();${field('dark')}.setSelectionRange(4,14)`);
  await key('Enter', await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
  await until(`typeof window.releaseCommentPost==='function'&&${form('dark')}.hasAttribute('data-bml-action-pending')&&${field('dark')}.readOnly`);
  assert.ok(await truth(`${post('dark')}.disabled&&${form('dark')}.querySelector('[type=reset]').disabled&&${field('dark')}.getAttribute('aria-invalid')==='false'&&${form('dark')}.querySelector('[data-bml-ref=commentError]').hidden&&!${field('dark')}.getAttribute('aria-describedby').includes(${field('dark')}.id+'-error')`));
  assert.equal(await evaluate(`new FormData(${form('dark')}).get('body')`), rejected);
  await key('Enter', 2); await key('Enter', 4);
  assert.equal(await evaluate('window.commentRequests'), 1);
  await evaluate('window.releaseCommentPost()');
  await until(`${toast('dark')}.dataset.sequence==='2'&&${toast('dark')}.matches(':popover-open')&&!${field('dark')}.readOnly&&!${form('dark')}.hasAttribute('data-bml-action-pending')`);
  await evaluate('window.restoreCommentFetch()');
  assert.ok(await truth(`${field('dark')}===window.retainedComment&&document.activeElement===window.retainedComment&&${field('dark')}.selectionStart===4&&${field('dark')}.selectionEnd===14&&${field('dark')}.value===${JSON.stringify(rejected)}&&${toast('dark')}.querySelector('[role=alert]').textContent==='Post rejected. Your draft was kept.'&&${field('dark')}.getAttribute('aria-invalid')==='false'`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await action('dark', 'reject', `!${owner('dark')}.querySelector('[data-comment-reject]').checked`);
  await evaluate(`${field('dark')}.focus()`); await key('Enter', 2);
  await until(`${toast('dark')}.dataset.sequence==='3'&&${toast('dark')}.matches(':popover-open')&&${field('dark')}.value===''&&${post('dark')}.disabled`);
  assert.ok(await truth(`${field('dark')}!==window.retainedComment&&!window.retainedComment.isConnected&&${toast('dark')}.querySelector('[role=status]').textContent==='Comment posted.'&&${field('light')}.value===''`));

  await action('dark', 'replace', `${field('dark')}.value==='Replacement draft 1 from this example.'`);
  await evaluate(`window.replacedComment=${field('dark')}`);
  await edit('dark', 'Local edits after a replacement');
  await click('[data-comment-reset=dark]');
  await until(`${field('dark')}.value==='Replacement draft 1 from this example.'`);
  await action('dark', 'replace', `${field('dark')}.value==='Replacement draft 2 from this example.'`);
  assert.ok(await truth(`${field('dark')}===window.replacedComment&&${field('dark')}.defaultValue===${field('dark')}.value`));
  await action('dark', 'locked', `${owner('dark')}.querySelector('[data-comment-locked]').checked`);
  await edit('dark', local);
  assert.ok(await truth(`!${field('dark')}.readOnly&&!${field('dark')}.disabled&&${post('dark')}.disabled&&${form('dark')}.querySelector('[data-bml-ref=shortcut]').textContent==='Posting is paused. You can keep editing your draft.'`));
  await evaluate(`${field('dark')}.focus()`); await key('Enter', 4);
  assert.ok(await truth(`!${form('dark')}.hasAttribute('data-bml-action-pending')&&${toast('dark')}.dataset.sequence==='3'`));
  await action('dark', 'locked', `!${post('dark')}.disabled`);
  await action('dark', 'readonly', `${field('dark')}.readOnly`);
  assert.ok(await truth(`${post('dark')}.disabled&&${form('dark')}.querySelector('[type=reset]').disabled&&new FormData(${form('dark')}).get('body')===${JSON.stringify(local)}`));
  await evaluate(`${field('dark')}.focus()`); await call('Input.insertText', { text: 'FORBIDDEN' });
  assert.equal(await evaluate(`${field('dark')}.value`), local);
  await action('dark', 'readonly', `!${field('dark')}.readOnly&&!${post('dark')}.disabled`);
  await action('dark', 'disabled', `${field('dark')}.disabled`);
  assert.ok(await truth(`${post('dark')}.disabled&&!new FormData(${form('dark')}).has('body')&&!${field('light')}.disabled`));
  await action('dark', 'disabled', `!${field('dark')}.disabled&&!${post('dark')}.disabled`);
  await action('dark', 'clear', `${field('dark')}.value===''&&${post('dark')}.disabled`);
  await edit('dark', 'A local draft while the supplied value is still empty');
  await evaluate(`window.clearedComment=${field('dark')}`);
  await action('dark', 'clear', `${field('dark')}.value===''&&${field('dark')}!==window.clearedComment`);
  for (let cycle=0;cycle<2;cycle++) {
    await evaluate(`window.removedComment=${field('dark')}`);
    const remote = await call('Runtime.evaluate', { expression: `${field('dark')}`, returnByValue: false });
    assert.equal((await call('DOMDebugger.getEventListeners', { objectId: remote.result.objectId })).listeners.filter(listener=>listener.type==='input'||listener.type==='keydown').length,2);
    await action('dark', 'visible', `!${field('dark')}&&!window.removedComment.isConnected`);
    assert.equal((await call('DOMDebugger.getEventListeners', { objectId: remote.result.objectId })).listeners.filter(listener=>listener.type==='input'||listener.type==='keydown').length,0);
    await call('Runtime.releaseObject', { objectId: remote.result.objectId });
    assert.ok(await truth(`!new FormData(${form('dark')}).has('body')`));
    await action('dark', 'visible', `${field('dark')}&&${post('dark')}.disabled`);
    assert.equal(await evaluate(`${form('dark')}.querySelectorAll('textarea').length`),1);
  }
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click();${owner('dark')}.querySelector('details').open=false`);
  await edit('dark', 'Another complete comment');
  await evaluate(`${field('dark')}.focus()`); await key('Enter',4);
  await until(`${toast('dark')}.dataset.sequence==='4'&&${field('dark')}.value===''&&${post('dark')}.disabled`);
  assert.ok(await truth(`!${owner('dark')}.querySelector('details').open`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);

  const measurements=[];
  for (const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await evaluate(`document.querySelector('[data-catalog-light]').click();document.fonts.ready`);
    await until(`document.documentElement.scrollWidth<=innerWidth`);
    assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-comment='+id+'] textarea')).backgroundColor)`),['rgb(16, 19, 28)','rgb(255, 255, 255)']);
    const geometry=await evaluate(`(() => {const groups=[...document.querySelectorAll('[data-catalog-comment-owner]')].map(owner=>{const field=owner.querySelector('textarea').getBoundingClientRect(),footer=owner.querySelector('.comment-footer').getBoundingClientRect(),note=owner.querySelector('form p').getBoundingClientRect();return {id:owner.dataset.catalogCommentOwner,height:field.height,gap:footer.top-field.bottom,contextGap:field.top-note.bottom};});const long=document.getElementById('comment-long').getBoundingClientRect();const cards=[...document.querySelectorAll('.catalog-grid .ui-card')].map(card=>card.getBoundingClientRect());const invalid=document.getElementById('comment-invalid').getBoundingClientRect(),error=document.getElementById('comment-invalid-error').getBoundingClientRect();return {groups,longHeight:long.height,errorGap:error.top-invalid.bottom,cards:cards.map(rect=>({top:rect.top,bottom:rect.bottom})),overflow:document.documentElement.scrollWidth-innerWidth};})()`);
    for (const group of geometry.groups) assert.ok(Math.abs(group.height-78)<1&&group.gap>=7.5&&group.contextGap>=15.5,JSON.stringify(group));
    assert.ok(Math.abs(geometry.longHeight-320)<1&&geometry.errorGap>=7.5&&geometry.overflow<=0,JSON.stringify(geometry));
    if(width===390) assert.ok(geometry.cards.slice(1).every((card,i)=>card.top-geometry.cards[i].bottom>=23.5),JSON.stringify(geometry.cards));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-comment-palette] textarea, [data-comment-palette] small, [data-comment-palette] .example-note, [data-comment-palette] .switch-copy strong, [data-comment-cases] textarea:not(:disabled), [data-comment-cases] small, [data-comment-cases] .alert, [data-comment-cases] .example-note')`);
    assert.ok(colors.text.length>=12);
    for(const sample of colors.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    const placeholders=await evaluate(`(${contrastSamples.toString()})('[data-comment-palette] textarea:placeholder-shown', '::placeholder')`);
    assert.equal(placeholders.text.length,2);
    assert.ok(await truth(`[...document.querySelectorAll('[data-comment-palette] textarea')].every(field=>getComputedStyle(field,'::placeholder').opacity==='1')`));
    for(const sample of placeholders.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    measurements.push({width,...geometry,colors:colors.text,placeholders:placeholders.text});
    await evaluate(`document.querySelectorAll('[data-catalog-comment-owner] details').forEach(details=>{details.open=false;});document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);
    const shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-comments-${width}.png`),Buffer.from(shot.data,'base64'));
    if(width===1440) {
      await evaluate(`document.querySelectorAll('[data-catalog-comment-owner] details').forEach(details=>{details.open=true;})`);
      const states=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-comments-states-${width}.png`),Buffer.from(states.data,'base64'));
      await evaluate(`document.querySelectorAll('[data-catalog-comment-owner] details').forEach(details=>{details.open=false;})`);
    }
    for(const index of width>700?[0,2,4]:[0,1,2,3,4,5]) {
      await evaluate(`document.querySelectorAll('[data-comment-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`);
      const cases=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-comments-cases-${width}-${index+1}.png`),Buffer.from(cases.data,'base64'));
    }
  }
  await writeFile(join(artifacts,`catalog-${mode}-comments.json`),JSON.stringify(measurements,null,2));
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await navigate('/comments');
  assert.ok(await truth(`${field('dark')}.required&&!${field('dark')}.hidden&&${field('dark')}.getAttribute('aria-label')==='Example comment'&&document.getElementById('comment-readonly').readOnly&&document.getElementById('comment-unavailable').disabled&&document.getElementById('comment-invalid').getAttribute('aria-describedby').includes('comment-invalid-error')&&!window.catalogCommentExecuted&&document.getElementById('comment-escaped').value.includes('</textarea><script>window.catalogCommentExecuted = true;</script>')&&document.documentElement.scrollWidth<=innerWidth`));
  await call('Emulation.setScriptExecutionDisabled',{value:false});
}
