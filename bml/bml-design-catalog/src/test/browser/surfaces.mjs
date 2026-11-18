import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Real local actions and native layout, disclosure, focus and scrolling in consumer palettes. */
export async function checkSurfaces({navigate,evaluate,until:poll,call,key,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const node=(part,id='dark')=>`document.querySelector('[data-surface-${part}=${id}]')`;
  const action=async(part,done,id='dark')=>{await evaluate(`window.surfaceAction=${node(part,id)};window.surfaceAction.click()`);await until(`!window.surfaceAction.isConnected&&(${done})`);};
  const load=async(id='dark')=>{await evaluate(`window.surfaceSequence=Number(${node('toast',id)}.dataset.sequence);${node('form',id)}.requestSubmit()`);await until(`Number(${node('toast',id)}.dataset.sequence)===window.surfaceSequence+1&&!${node('form',id)}.hasAttribute('data-bml-action-pending')`);};
  const states=async(id='dark')=>evaluate(`document.querySelector('[data-catalog-surface=${id}] [data-example-states]').open=true`);
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-surfaces-${name}.png`),Buffer.from(capture.data,'base64'));};
  const settle=()=>evaluate('Promise.all(document.getAnimations().filter(animation=>animation instanceof CSSTransition).map(animation=>animation.finished)).then(()=>true)');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/surfaces');
  await until(`${node('form')}?.dataset.bmlActionBound==='true'`);await evaluate('document.fonts.ready');
  assert.ok(await truth(`${node('empty')}.checkVisibility()&&!${node('loading')}.checkVisibility()&&${node('clear')}.disabled&&${node('toast')}.dataset.sequence==='0'&&document.querySelectorAll('main').length===1`));
  await evaluate(`${node('disclosure')}.querySelector('summary').focus()`);await key('Enter');await until(`${node('disclosure')}.open`);
  await evaluate(`window.surfaceDisclosure=${node('disclosure')};window.surfaceNote=${node('note')};window.surfaceNote.value='A browser-owned <note> & draft';window.surfaceNote.focus();window.surfaceNote.setSelectionRange(3,11)`);
  await states();await action('refresh',`${node('toast')}.dataset.sequence==='1'`);
  assert.ok(await truth(`${node('disclosure')}===window.surfaceDisclosure&&${node('note')}===window.surfaceNote&&${node('disclosure')}.open&&document.activeElement===window.surfaceNote&&window.surfaceNote.selectionStart===3&&window.surfaceNote.selectionEnd===11&&window.surfaceNote.value==='A browser-owned <note> & draft'&&document.querySelector('[data-catalog-surface=dark] [data-example-states]').open`));
  await evaluate(`(()=>{window.surfaceStage=${node('stage')}.getBoundingClientRect().toJSON();window.surfaceButton=${node('load')}.getBoundingClientRect().toJSON();const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseSurfaceLoad=resolve);}return original(...args);};${node('form')}.requestSubmit();})()`);
  await until(`typeof window.releaseSurfaceLoad==='function'&&${node('form')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${node('loading')}.checkVisibility()&&!${node('results')}.checkVisibility()&&${node('loading')}.getAttribute('role')==='status'&&getComputedStyle(${node('load')}.querySelector('.button-progress')).display==='flex'&&${node('form')}.getAttribute('aria-busy')==='true'`));
  assert.ok(await truth(`(()=>{const stage=${node('stage')}.getBoundingClientRect(),button=${node('load')}.getBoundingClientRect();return stage.height===window.surfaceStage.height&&button.height===window.surfaceButton.height&&button.width===window.surfaceButton.width;})()`),'Loading keeps the data area and action footprint');
  await evaluate('window.releaseSurfaceLoad()');await until(`${node('table')}&&${node('toast')}.dataset.sequence==='2'`);
  assert.ok(await truth(`${node('table')}.getAttribute('role')==='region'&&${node('table')}.getAttribute('aria-label')==='Local component examples'&&${node('table')}.tabIndex===0&&${node('table')}.querySelectorAll('tbody tr').length===15&&${node('table')}.querySelectorAll('th[scope=col]').length===3&&${node('empty','light')}.checkVisibility()&&!${node('table','light')}`));
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:1000,deviceScaleFactor:1,mobile:false});
  await until(`${node('table')}.scrollWidth>${node('table')}.clientWidth&&${node('table')}.scrollHeight>${node('table')}.clientHeight`);
  // Register the future event without returning its Promise to the awaiting CDP evaluation.
  await evaluate(`(()=>{${node('table')}.scrollIntoView({block:'center',behavior:'instant'});${node('table')}.focus({preventScroll:true});window.surfaceScrollFinished=new Promise(resolve=>${node('table')}.addEventListener('scrollend',resolve,{once:true}));})()`);
  await key('ArrowRight');await evaluate('window.surfaceScrollFinished.then(()=>true)');
  const horizontal=await evaluate(`({left:${node('table')}.scrollLeft,top:${node('table')}.scrollTop})`);
  assert.ok(horizontal.left>0,`Arrow Right scrolls the native table: ${JSON.stringify(horizontal)}`);
  await evaluate(`(()=>{window.surfaceScrollFinished=new Promise(resolve=>${node('table')}.addEventListener('scrollend',resolve,{once:true}));})()`);
  await key('PageDown');await evaluate('window.surfaceScrollFinished.then(()=>true)');
  const vertical=await evaluate(`({left:${node('table')}.scrollLeft,top:${node('table')}.scrollTop})`);
  assert.ok(vertical.top>0,`Page Down scrolls the native table: ${JSON.stringify(vertical)}`);
  await evaluate(`${node('table')}.scrollTo({left:80,top:120,behavior:'instant'})`);await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  await evaluate(`window.surfaceTable=${node('table')};window.surfaceScroll=[window.surfaceTable.scrollLeft,window.surfaceTable.scrollTop]`);
  await action('refresh',`${node('toast')}.dataset.sequence==='3'`);
  const retained=await evaluate(`({same:${node('table')}===window.surfaceTable,focus:document.activeElement===window.surfaceTable,scroll:[${node('table')}.scrollLeft,${node('table')}.scrollTop],previous:window.surfaceScroll})`);
  assert.ok(retained.same&&retained.focus&&retained.scroll.every((value,index)=>Math.abs(value-retained.previous[index])<=1),JSON.stringify(retained));
  await action('reject',`${node('reject')}.checked`);await load();
  assert.ok(await truth(`${node('toast')}.dataset.tone==='error'&&${node('table')}===window.surfaceTable&&${node('table')}.querySelectorAll('tbody tr').length===15`));
  await action('clear',`${node('empty')}&&!${node('table')}`);await load();
  assert.ok(await truth(`${node('toast')}.dataset.tone==='error'&&${node('empty')}.checkVisibility()`));
  await action('reject',`!${node('reject')}.checked`);await action('compact',`${node('loading')}.classList.contains('compact')`);await load();
  assert.ok(await truth(`${node('toast')}.dataset.tone==='success'&&${node('table')}.querySelectorAll('tbody tr').length===15&&${node('note')}.value==='A browser-owned <note> & draft'`));
  await evaluate(`window.surfaceShortTable=${node('table')}`);await action('long',`${node('table')}!==window.surfaceShortTable&&${node('table')}.querySelector('tbody th').textContent.includes('<section>')`);
  assert.ok(await truth(`!window.surfaceShortTable.isConnected&&!${node('table')}.querySelector('tbody section')&&${node('disclosure')}.open&&${node('note')}===window.surfaceNote`));
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/surfaces');await until(`${node('form','light')}?.dataset.bmlActionBound==='true'`);await evaluate('document.fonts.ready');
    await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click()`);await settle();
    for(const id of ['dark','light']) {
      if(await truth(`!${node('table',id)}`))await load(id);
      await states(id);
      if(await truth(`!${node('long',id)}.checked`))await action('long',`${node('long',id)}.checked`,id);
      await evaluate(`${node('disclosure',id)}.open=true;document.querySelector('[data-catalog-surface=${id}] [data-example-states]').open=false`);
    }
    await settle();
    const geometry=await evaluate(`(()=>{const inset=selector=>{const style=getComputedStyle(document.querySelector(selector));return [style.paddingTop,style.paddingRight,style.paddingBottom,style.paddingLeft];};const groups=['dark','light'].map(id=>{const root=document.querySelector('[data-catalog-surface='+id+']'),panel=root.querySelector('[data-surface-panel]'),header=root.querySelector('[data-surface-heading]'),table=root.querySelector('[data-surface-table]'),cards=[...root.querySelector('[data-surface-cases]').children].map(node=>node.getBoundingClientRect()),body=panel.firstElementChild,form=root.querySelector('form'),stage=root.querySelector('[data-surface-stage]'),section=root.querySelector('[data-surface-section]');return {id,panel:inset('[data-surface-panel='+id+']'),padded:inset('[data-surface-padded='+id+'] .card-body'),unpadded:inset('[data-surface-unpadded='+id+'] .card-content'),contentGap:section.getBoundingClientRect().top-form.getBoundingClientRect().bottom,stageHeight:stage.getBoundingClientRect().height,tableFits:table.getBoundingClientRect().right<=panel.getBoundingClientRect().right,headerFits:[...header.querySelectorAll('h2,p,button')].every(node=>node.getBoundingClientRect().right<=header.getBoundingClientRect().right+1),rowsGap:cards[2].top-cards[innerWidth===390?1:0].bottom,columnsGap:innerWidth===390?null:cards[1].left-cards[0].right,disclosure:${node('disclosure')}.tagName,emptyMinimal:!root.querySelector('[data-surface-minimal] h3')&&!root.querySelector('[data-surface-minimal] .empty-actions').checkVisibility(),untitled:!root.querySelector('[data-surface-untitled] .card-header'),nativeRows:table.querySelectorAll('tbody th[scope=row]').length};});return {groups,overflow:document.documentElement.scrollWidth-innerWidth};})()`);
    assert.ok(geometry.overflow<=0&&geometry.groups.every(group=>group.panel.every(value=>value==='20px')&&group.padded.join(',')==='16px,18px,16px,18px'&&group.unpadded.every(value=>value==='0px')&&group.contentGap===16&&group.stageHeight===280&&group.tableFits&&group.headerFits&&Math.abs(group.rowsGap-24)<=1&&(group.columnsGap===null||group.columnsGap===24)&&group.emptyMinimal&&group.untitled&&group.nativeRows===15),JSON.stringify(geometry));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-surface-palette] .section-heading h2, [data-surface-palette] .section-heading p, [data-surface-palette] .catalog-description, [data-surface-palette] .example-note, [data-surface-palette] .disclosure-label, [data-surface-palette] .badge, [data-surface-palette] .field > span, [data-surface-palette] input, [data-surface-palette] .empty-state p, [data-surface-palette] caption, [data-surface-palette] th, [data-surface-palette] td')`);
    for(const sample of colors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    for(const id of ['dark','light']) {
      await evaluate(`${node('panel',id)}.scrollIntoView({block:'start'})`);await shot(`${id}-${width}`);
      await evaluate(`${node('cases',id)}.scrollIntoView({block:'start'})`);await shot(`cards-${id}-${width}`);
    }
    measurements.push({width,...geometry,colors:colors.text});
  }
  await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseSurfaceCompact=resolve);}return original(...args);};${node('form')}.requestSubmit();})()`);
  await until(`typeof window.releaseSurfaceCompact==='function'&&${node('loading')}.checkVisibility()`);
  assert.equal(await evaluate(`getComputedStyle(${node('loading')}.querySelector('.spinner')).animationName`),'none');
  await evaluate(`${node('stage')}.scrollIntoView({block:'center'})`);await shot('compact-pending-390');await evaluate('window.releaseSurfaceCompact()');await until(`!${node('form')}.hasAttribute('data-bml-action-pending')`);
  await call('Emulation.setEmulatedMedia',{features:[]});
  await call('Emulation.setScriptExecutionDisabled',{value:true});await navigate('/surfaces');
  assert.ok(await truth(`${node('table','light')}.tabIndex===0&&${node('table','light')}.querySelectorAll('tbody tr').length===15&&${node('note','light')}.labels.length===1&&!${node('loading','light')}.checkVisibility()&&document.querySelectorAll('main').length===1&&document.documentElement.scrollWidth<=innerWidth`));
  await evaluate(`${node('disclosure','light')}.querySelector('summary').scrollIntoView({block:'center'});${node('disclosure','light')}.querySelector('summary').focus()`);await key('Enter');await until(`${node('disclosure','light')}.open`);
  await evaluate(`${node('note','light')}.value='Native local note';${node('note','light')}.focus()`);assert.equal(await evaluate(`${node('note','light')}.value`),'Native local note');
  await evaluate(`${node('table','light')}.scrollIntoView({block:'center'});${node('table','light')}.focus()`);await key('ArrowRight');await until(`${node('table','light')}.scrollLeft>0`);await shot('native-390');
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-surfaces.json`),JSON.stringify(measurements,null,2));
}
