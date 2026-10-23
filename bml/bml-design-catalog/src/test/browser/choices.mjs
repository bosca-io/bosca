import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Local native selections, controlled defaults and multiple/custom values in real compiled controls. */
export async function checkLocalChoices({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const native=(kind,id='dark')=>`document.querySelector('[data-local-${kind}=${id}]')`;
  const root=(kind,id='dark')=>`${native(kind,id)}.closest('[data-bml-design-select]')`;
  const part=(kind,name,id='dark')=>`${root(kind,id)}.querySelector('[data-bml-ref=${name}]')`;
  const owner=id=>`document.querySelector('[data-local-choices=${id}]')`;
  const form=id=>`document.querySelector('[data-local-choice-form=${id}]')`;
  const tagsForm=id=>`document.querySelector('[data-local-tags-form=${id}]')`;
  const toast=id=>`document.querySelector('[data-local-toast=${id}]')`;
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key=async (value,modifiers=0)=>{
    for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:value,modifiers,
      windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35,Backspace:8}[value],
      ...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
  };
  const open=async(kind,id='dark')=>{await evaluate(`${part(kind,'trigger',id)}.click()`);await until(`${part(kind,'popup',id)}.matches(':popover-open')`);};
  const type=(kind,value,id='dark')=>evaluate(`${part(kind,'query',id)}.focus();${part(kind,'query',id)}.value=${JSON.stringify(value)};${part(kind,'query',id)}.dispatchEvent(new Event('input',{bubbles:true}))`);
  const choose=async(kind,text,id='dark')=>{
    await evaluate(`[...${part(kind,'options',id)}.children].find(node=>node.textContent===${JSON.stringify(text)}).dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true}))`);
  };
  const action=async(name,done,id='dark')=>{
    await evaluate(`window.localChoiceAction=document.querySelector('[data-local-${name}=${id}]');window.localChoiceAction.click()`);
    await until(`!window.localChoiceAction.isConnected&&(${done})`);
  };
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-choices-${name}.png`),Buffer.from(capture.data,'base64'));};
  const listeners=async()=>{
    const result={};
    for(const expression of ['document','window']) {
      const ref=await call('Runtime.evaluate',{expression});
      const found=await call('DOMDebugger.getEventListeners',{objectId:ref.result.objectId});
      result[expression]=found.listeners.filter(item=>['pointerdown','reset','resize','scroll','bml:update'].includes(item.type)).length;
      await call('Runtime.releaseObject',{objectId:ref.result.objectId});
    }
    return result;
  };
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/choices');
  await until(`document.querySelectorAll('select.enhanced').length===12&&${form('dark')}.dataset.bmlActionBound==='true'`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`),'/choices');
  const baseline=await listeners();
  assert.ok(await truth(`document.querySelector('[data-local-compact]').closest('[data-bml-design-select]').querySelector('.select-trigger').getBoundingClientRect().height===30&&document.querySelector('[data-local-tag-limit]').selectedOptions.length===4&&document.querySelector('[data-local-tag-limit]').closest('[data-bml-design-select]').querySelector('.select-tags').textContent.includes('+3')`));
  await evaluate(`document.querySelector('[data-local-empty]').closest('[data-bml-design-select]').querySelector('[data-bml-ref=trigger]').click()`);
  await until(`document.querySelector('[data-local-empty]').closest('[data-bml-design-select]').querySelector('[data-bml-ref=status]').textContent==='No options found.'`);
  await key('Escape');
  assert.ok(await truth(`${native('single')}.required&&${native('single')}.value===''&&${part('single','trigger')}.getAttribute('aria-describedby')==='local-choice-hint-dark'&&${part('single','trigger')}.getAttribute('aria-controls')===${part('single','options')}.id&&${part('multiple','options')}.getAttribute('aria-multiselectable')==='true'`));
  await evaluate(`window.localChoiceRequests=[];const original=window.fetch.bind(window);window.fetch=(...args)=>{window.localChoiceRequests.push(String(args[0]));return original(...args);};document.querySelector('[data-local-save=dark]').click()`);
  assert.ok(await truth(`${part('single','trigger')}.getAttribute('aria-invalid')==='true'&&document.activeElement===${part('single','trigger')}&&window.localChoiceRequests.length===0`));
  await key('Home'); await key('ArrowDown'); await key('ArrowDown'); await key('ArrowDown');
  assert.equal(await evaluate(`document.getElementById(${part('single','trigger')}.getAttribute('aria-activedescendant')).textContent`),'Checkbox choices · control');
  await key('Enter');
  assert.ok(await truth(`${native('single')}.value==='checkbox'&&!${part('single','trigger')}.hasAttribute('aria-invalid')`));
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['button','input','card']);
  assert.ok(await truth(`${part('multiple','tags')}.querySelectorAll('.select-tag').length===2&&${part('multiple','tags')}.textContent.includes('+1')`));
  await open('multiple'); await type('multiple','overlay');
  await choose('multiple','Dialogs · overlay');
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['button','input','card','dialog']);
  assert.ok(await truth(`${part('multiple','popup')}.matches(':popover-open')`));
  await type('multiple','Release candidate'); await key('End'); await key('Enter');
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['button','input','card','dialog','Release candidate']);
  await type('multiple',''); await key('Backspace');
  await evaluate(`${part('multiple','tags')}.querySelector('[data-remove=button]').focus()`);await key('Enter');
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['input','card','dialog']);
  assert.equal(await evaluate(`document.activeElement===${part('multiple','trigger')}`),true,'Keyboard tag removal returns focus to its combobox');
  assert.equal(await truth(`${part('multiple','popup')}.matches(':popover-open')`),false,'Moving keyboard focus to a tag leaves the popup');
  await open('multiple');
  await type('multiple','Unavailable');
  assert.ok(await truth(`[...${part('multiple','options')}.children].every(node=>node.getAttribute('aria-disabled')==='true'||node.textContent.startsWith('Use '))&&[...${part('multiple','options')}.children].some(node=>node.textContent==='A disabled group choice'&&node.getAttribute('aria-disabled')==='true')`));
  await type('multiple','overlay');
  await evaluate(`window.localTagsRoot=${root('multiple')};window.localTagsNative=${native('multiple')};window.localTagsQuery=${part('multiple','query')};window.localTagsQuery.focus();window.localTagsQuery.setSelectionRange(1,4)`);
  await evaluate(`${owner('dark')}.querySelector('[data-example-states]').open=true`);
  await action('refresh',`${toast('dark')}.dataset.sequence==='1'&&${toast('dark')}.matches(':popover-open')`);
  assert.ok(await truth(`${root('multiple')}===window.localTagsRoot&&${native('multiple')}===window.localTagsNative&&${part('multiple','query')}===window.localTagsQuery&&${part('multiple','popup')}.matches(':popover-open')&&document.activeElement===window.localTagsQuery&&window.localTagsQuery.value==='overlay'&&window.localTagsQuery.selectionStart===1&&window.localTagsQuery.selectionEnd===4&&${owner('dark')}.querySelector('details[data-example-states]').open`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`); await key('Escape');
  await open('custom'); await type('custom','Release <preview>'); await key('End'); await key('Enter');
  assert.equal(await evaluate(`${native('custom')}.value`),'Release <preview>');
  await action('reject',`${owner('dark')}.querySelector('[data-local-reject]').checked`);
  await evaluate(`window.localRejectedSingle=${root('single')};window.localRejectedCustom=${root('custom')};${form('dark')}.requestSubmit()`);
  await until(`${toast('dark')}.dataset.sequence==='2'&&${toast('dark')}.matches(':popover-open')`);
  assert.ok(await truth(`${root('single')}===window.localRejectedSingle&&${root('custom')}===window.localRejectedCustom&&${native('single')}.value==='checkbox'&&${native('custom')}.value==='Release <preview>'&&${toast('dark')}.querySelector('[role=alert]').textContent==='Save rejected. Your choices were kept.'`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await action('reject',`!${owner('dark')}.querySelector('[data-local-reject]').checked`);
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseLocalChoiceSave=resolve;});}return original(...args);};window.localSaveSize=document.querySelector('[data-local-save=dark]').getBoundingClientRect().toJSON();${form('dark')}.requestSubmit();})()`);
  await until(`typeof window.releaseLocalChoiceSave==='function'&&${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`(()=>{const button=document.querySelector('[data-local-save=dark]'),box=button.getBoundingClientRect();return box.width===window.localSaveSize.width&&box.height===window.localSaveSize.height&&getComputedStyle(button.querySelector('.button-progress')).display==='flex';})()`));
  assert.deepEqual(await evaluate(`Array.from(new FormData(${form('dark')}))`),[['component','checkbox'],['label','Release <preview>']]);
  await evaluate('window.releaseLocalChoiceSave()');
  await until(`${toast('dark')}.dataset.sequence==='3'&&${root('single')}!==window.localRejectedSingle`);
  assert.ok(await truth(`${native('single')}.value==='checkbox'&&${native('custom')}.value==='Release <preview>'&&${root('multiple')}===window.localTagsRoot&&${native('single','light')}.value===''&&${toast('light')}.dataset.sequence==='0'`));
  await open('single'); await choose('single','Button states · control'); await click('[data-local-reset=dark]');
  await until(`${native('single')}.value==='checkbox'&&${part('single','selection')}.textContent==='Checkbox choices · control'`);
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await action('replace',`${native('single')}.value==='card'&&${native('custom')}.value==='Imported sample'`);
  assert.ok(await truth(`${root('multiple')}!==window.localTagsRoot&&!window.localTagsRoot.isConnected&&!${toast('dark')}.matches(':popover-open')`));
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['checkbox','switch']);
  await open('multiple'); await choose('multiple','Button states · control'); await click('[data-local-tags-reset=dark]');
  await until(`${part('multiple','selection')}.textContent==='2 selected'`);
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['checkbox','switch']);
  await action('limit',`${root('multiple')}.dataset.maxTags==='1'&&${part('multiple','tags')}.querySelectorAll('.select-tag').length===1`);
  await action('disabled',`${native('single')}.disabled&&${part('multiple','trigger')}.disabled`);
  assert.ok(await truth(`!new FormData(${form('dark')}).has('component')&&!new FormData(${tagsForm('dark')}).has('tags')&&[...${part('multiple','tags')}.querySelectorAll('button')].every(button=>button.disabled)&&!${native('single','light')}.disabled`));
  await action('disabled',`!${native('single')}.disabled`);
  await action('loading',`${part('single','trigger')}.getAttribute('aria-busy')==='true'&&${part('multiple','trigger')}.disabled`);
  assert.equal(await evaluate(`new FormData(${form('dark')}).get('component')`),'card');
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('dark')}).getAll('tags')`),['checkbox','switch']);
  await action('loading',`!${part('single','trigger')}.disabled`);
  await action('required',`!${native('single')}.required`);
  await evaluate(`${part('single','trigger')}.focus()`); await open('single'); await key('Home'); await key('Enter');
  assert.deepEqual(await evaluate(`({value:${native('single')}.value,required:${native('single')}.required,valid:${form('dark')}.checkValidity()})`),{value:'',required:false,valid:true});
  await open('custom'); await type('custom','A new custom label');
  assert.ok(await truth(`${part('custom','options')}.textContent.includes('Use “A new custom label”')`));
  await action('allow-custom',`!${root('custom')}.hasAttribute('data-allow-custom')`);
  assert.ok(await truth(`!${part('custom','options')}.textContent.includes('Use “A new custom label”')`),'Changing custom-choice availability reconciles an already-open list');
  await evaluate(`${part('custom','query')}.focus()`); await key('Escape');
  await action('allow-custom',`${root('custom')}.hasAttribute('data-allow-custom')`);
  assert.ok(await truth(`!window.localChoiceRequests.some(url=>url.includes('/_bml/contract/'))`),'Local filtering never requests server search');
  for(let cycle=0;cycle<2;cycle++) {
    await evaluate(`window.removedLocalSelect=${root('multiple')}`);
    await action('visible',`!document.querySelector('[data-local-multiple=dark]')&&!window.removedLocalSelect.isConnected`);
    const removed=await listeners(); assert.ok(removed.document<baseline.document&&removed.window<baseline.window);
    await action('visible',`${native('multiple')}.classList.contains('enhanced')`);
    assert.deepEqual(await listeners(),baseline);
  }
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click();document.querySelectorAll('[data-example-states]').forEach(details=>{details.open=false;});document.fonts.ready`);
    const geometry=await evaluate(`(()=>{const groups=[...document.querySelectorAll('[data-local-choices]')].map(owner=>{const rows=[...owner.querySelectorAll('form')].map(form=>form.getBoundingClientRect());return {id:owner.dataset.localChoices,formGap:rows[1].top-rows[0].bottom,singleHeight:owner.querySelector('.select-trigger').getBoundingClientRect().height,multipleHeight:owner.querySelector('[data-local-multiple]').closest('[data-bml-design-select]').querySelector('.select-trigger').getBoundingClientRect().height};});const cards=[...document.querySelectorAll('.catalog-grid .ui-card')].map(card=>card.getBoundingClientRect());return {groups,overflow:document.documentElement.scrollWidth-innerWidth,cardGaps:cards.slice(1).map((card,i)=>card.top-cards[i].bottom)};})()`);
    assert.ok(geometry.overflow<=0);
    assert.ok(await truth(`(()=>{const widget=document.querySelector('[data-local-tag-limit]').closest('[data-bml-design-select]'),tag=widget.querySelector('.select-tag');return tag.getBoundingClientRect().right<=widget.getBoundingClientRect().right+1;})()`),'A long unbroken tag label remains inside its control');
    for(const group of geometry.groups) assert.ok(group.formGap>=15.5&&group.singleHeight===32&&group.multipleHeight===30,JSON.stringify(group));
    if(width===390) assert.ok(geometry.cardGaps.every(gap=>gap>=23.5),JSON.stringify(geometry.cardGaps));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-local-choice-palette] .field > span, [data-local-choice-palette] .field small, [data-local-choice-palette] .select-selection, [data-local-choice-palette] .select-tag > span, [data-local-choice-palette] .example-note')`);
    for(const sample of colors.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    await evaluate(`document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`); await shot(`page-${width}`);
    const palettes=[];
    for(const id of ['dark','light']) {
      await evaluate(`${part('multiple','trigger',id)}.scrollIntoView({block:'center'})`); await open('multiple',id); await type('multiple','control',id);
      const popupBox=await evaluate(`(()=>{const node=${part('multiple','popup',id)},box=node.getBoundingClientRect();return {left:box.left,right:box.right,top:box.top,bottom:box.bottom,background:getComputedStyle(node).backgroundColor};})()`);
      assert.ok(popupBox.left>=12&&popupBox.right<=width-12&&popupBox.top>=12&&popupBox.bottom<=1000-12,JSON.stringify(popupBox));
      const optionColors=await evaluate(`(${contrastSamples.toString()})('[data-local-choice-palette=${id}] .select-options [role=option]:not([aria-disabled=true]), [data-local-choice-palette=${id}] .select-query')`);
      for(const sample of optionColors.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      palettes.push({id,...popupBox,colors:optionColors.text}); await shot(`popup-${id}-${width}`); await key('Escape');
    }
    for(const index of width>700?[0,2,4]:[0,1,2,3,4,5]) {
      await evaluate(`document.querySelectorAll('[data-local-choice-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`); await shot(`cases-${width}-${index+1}`);
    }
    measurements.push({width,...geometry,colors:colors.text,palettes});
  }
  await writeFile(join(artifacts,`catalog-${mode}-choices.json`),JSON.stringify(measurements,null,2));
  await call('Emulation.setScriptExecutionDisabled',{value:true}); await navigate('/choices');
  assert.ok(await truth(`!document.querySelector('select.enhanced')&&${native('single','light')}.required&&${native('single','light')}.getAttribute('aria-label')==='Component'&&${native('multiple','light')}.multiple&&${native('multiple','light')}.querySelector('optgroup').disabled&&new FormData(document.querySelector('[data-local-loading-case]')).get('loading')==='card'&&!new FormData(document.querySelector('[data-local-disabled-case]')).has('disabled')&&document.documentElement.scrollWidth<=innerWidth`));
  assert.deepEqual(await evaluate(`new FormData(${tagsForm('light')}).getAll('tags')`),['button','input','card']);
  assert.ok(await truth(`${native('multiple','light')}.getBoundingClientRect().height>=64&&getComputedStyle(${native('multiple','light')}.nextElementSibling).display==='none'`),'The native multiple fallback is a listbox without a dropdown chevron');
  await evaluate(`${native('multiple','light')}.scrollIntoView({block:'center'})`); await shot('native-390');
  await call('Emulation.setScriptExecutionDisabled',{value:false});
}
