import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Clipboard outcomes are isolated stubs; actions, preservation, focus and rendering use the real app. */
export async function checkFeedback({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const node=(part,id='dark')=>`document.querySelector('[data-feedback-${part}=${id}]')`;
  const copy=id=>`${node('copy',id)}.querySelector('button')`;
  const field=id=>`${node('field',id)}.querySelector('input')`;
  const tip=(placement='top',id='dark')=>`document.querySelector('[data-feedback-tooltip=${id}][data-placement=${placement}]')`;
  const popup=root=>`${root}.querySelector('.bosca-tooltip')`;
  const key=async value=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,End:35}[value],...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});};
  const action=async(part,done,id='dark')=>{await evaluate(`window.feedbackAction=${node(part,id)};window.feedbackAction.click()`);await until(`!window.feedbackAction.isConnected&&(${done})`);};
  const dismiss=async(id='dark')=>{await evaluate(`${node('toast',id)}.querySelector('[data-bml-ref=dismiss]').click()`);await until(`!${node('toast',id)}.matches(':popover-open')`);};
  const fit=root=>`(()=>{const box=${root}.getBoundingClientRect();return box.width>0&&box.left>=7&&box.top>=7&&box.right<=innerWidth-7&&box.bottom<=innerHeight-7;})()`;
  const focusTip=async root=>{await evaluate(`${root}.querySelector('button').scrollIntoView({block:'center'});${root}.querySelector('button').focus()`);await until(`${popup(root)}?.matches(':popover-open')`);};
  const listeners=async()=>{
    const counts={};
    for(const expression of ['document','window']) {
      const handle=await call('Runtime.evaluate',{expression});const found=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});await call('Runtime.releaseObject',{objectId:handle.result.objectId});
      counts[expression]=found.listeners.filter(item=>['keydown','scroll','resize','bml:update'].includes(item.type)).length;
    }
    return counts;
  };
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-feedback-${name}.png`),Buffer.from(capture.data,'base64'));};
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/feedback');await until(`${node('success')}?.dataset.bmlActionBound==='true'&&!${copy('dark')}.hasAttribute('title')`);await evaluate('document.fonts.ready');
  const baseline=await listeners();
  await evaluate(`(()=>{window.feedbackWrites=[];window.feedbackClipboardMode='success';window.feedbackPending=[];Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText(value){window.feedbackWrites.push(value);if(window.feedbackClipboardMode==='denied')return Promise.reject(new DOMException('Denied','NotAllowedError'));if(window.feedbackClipboardMode==='pending')return new Promise(resolve=>window.feedbackPending.push(resolve));return Promise.resolve();}}});document.querySelector('[data-catalog-feedback=dark] details').open=true;})()`);
  assert.ok(await truth(`${field('dark')}.readOnly&&${field('dark')}.labels.length===1&&${field('dark')}.value==='io.bosca:bml-design'&&${node('toast')}.dataset.sequence==='0'&&!${node('toast')}.matches(':popover-open')`));
  await evaluate(`${field('dark')}.focus()`);
  assert.ok(await truth(`${field('dark')}.selectionStart===0&&${field('dark')}.selectionEnd===${field('dark')}.value.length`));
  await evaluate(`window.feedbackCopy=${node('copy')};window.feedbackField=${node('field')};window.feedbackNeighbor=${node('copy-row')}.lastElementChild.getBoundingClientRect().left;${copy('dark')}.focus()`);await key('Enter');
  await until(`${node('toast')}.matches(':popover-open')&&${node('toast')}.dataset.sequence==='1'`);
  assert.equal(await evaluate('window.feedbackWrites.at(-1)'),'io.bosca:bml-design');
  assert.ok(await truth(`${node('copy')}===window.feedbackCopy&&${node('field')}===window.feedbackField&&document.activeElement===${copy('dark')}&&${node('toast','light')}.dataset.sequence==='0'&&getComputedStyle(${node('copy')}.querySelector('[role=status]')).position==='absolute'`));
  assert.equal(await evaluate(`${node('copy-row')}.lastElementChild.getBoundingClientRect().left`),await evaluate('window.feedbackNeighbor'),'Quiet copy feedback preserves adjacent spacing');
  await evaluate(`${copy('dark')}.click()`);await until(`${node('toast')}.dataset.sequence==='2'`);await dismiss();
  await evaluate(`window.feedbackClipboardMode='denied';${node('field')}.querySelector('button').click()`);
  await until(`${node('toast')}.dataset.sequence==='3'&&${node('toast')}.dataset.tone==='error'&&!${node('field')}.querySelector('[role=alert]').hidden`);
  assert.ok(await truth(`document.activeElement===${field('dark')}&&${field('dark')}.selectionStart===0&&${field('dark')}.selectionEnd===${field('dark')}.value.length&&${node('toast')}.querySelector('[role=alert]').getAttribute('aria-live')==='assertive'`));
  await evaluate(`window.feedbackClipboardMode='success';${node('field')}.querySelector('button').click()`);
  await until(`${node('toast')}.dataset.sequence==='4'&&${node('field')}.querySelector('[role=alert]').hidden`);await dismiss();
  await focusTip(tip());
  await evaluate(`window.feedbackTooltip=${tip()};window.feedbackPopup=${popup(tip())};window.feedbackTrigger=document.activeElement`);
  await action('refresh',`${node('toast')}.dataset.sequence==='5'`);
  assert.ok(await truth(`${tip()}===window.feedbackTooltip&&${popup(tip())}===window.feedbackPopup&&${popup(tip())}.matches(':popover-open')&&document.activeElement===window.feedbackTrigger`),'A focused description survives a surrounding update');
  await action('replace',`${field('dark')}.value.startsWith('Updated')`);
  assert.ok(await truth(`${node('copy')}===window.feedbackCopy&&${node('field')}!==window.feedbackField&&!window.feedbackField.isConnected&&${popup(tip())}.textContent.includes('<button>')&&!${popup(tip())}.querySelector('button')&&${popup(tip())}.matches(':popover-open')`));
  await key('Escape');assert.ok(await truth(`!${popup(tip())}.matches(':popover-open')&&document.activeElement===window.feedbackTrigger`));
  await evaluate(`${copy('dark')}.click()`);await until(`${node('toast')}.dataset.sequence==='6'`);
  assert.match(await evaluate('window.feedbackWrites.at(-1)'),/^Updated "value" & text/);
  assert.equal(await evaluate(`${field('light')}.value`),'io.bosca:bml-design');await dismiss();
  await action('refresh',`${node('toast')}.dataset.sequence==='7'`);
  assert.ok(await truth(`!${popup(tip())}.matches(':popover-open')`),'A dismissed description stays closed through a surrounding update');await dismiss();
  for(const placement of ['bottom','left','right']) {
    await focusTip(tip(placement));await until(fit(popup(tip(placement))));
    assert.ok(await truth(`(()=>{const root=${tip(placement)},tooltip=${popup(tip(placement))};return tooltip.getAttribute('role')==='tooltip'&&root.querySelector('button').getAttribute('aria-describedby').split(/\\s+/).includes(tooltip.id);})()`));
    await key('Escape');
  }
  await evaluate(`window.feedbackClipboardMode='pending';${copy('dark')}.click();window.feedbackRemovedCopy=${node('copy')}`);
  await action('visible',`!${node('utilities')}`);
  await evaluate('window.feedbackPending[0]()');
  assert.ok(await truth(`!window.feedbackRemovedCopy.isConnected&&${node('toast')}.dataset.sequence==='7'`));
  for(let cycle=0;cycle<3;cycle++) {
    await action('visible',`${node('utilities')}&&!${copy('dark')}.hasAttribute('title')`);
    assert.deepEqual(await listeners(),baseline,'Mounting and disposing controls cleans up global listeners');
    if(cycle<2)await action('visible',`!${node('utilities')}`);
  }
  await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await action('warning',`${node('toast')}.dataset.tone==='warning'&&${node('toast')}.matches(':popover-open')`);
  assert.equal(await evaluate(`getComputedStyle(${node('toast')}.firstElementChild).animationName`),'none');
  await call('Emulation.setEmulatedMedia',{features:[]});await dismiss();
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/feedback');await until(`${node('info')}?.dataset.bmlActionBound==='true'&&!${copy('dark')}.hasAttribute('title')`);
    await evaluate(`document.fonts.ready;if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click()`);
    await evaluate('Promise.all(document.getAnimations().filter(animation=>animation instanceof CSSTransition).map(animation=>animation.finished)).then(()=>true)');
    const geometry=await evaluate(`(()=>{const groups=[...document.querySelectorAll('[data-catalog-feedback]')].map(root=>{const field=root.querySelector('.copy-field-row'),input=field.querySelector('input').getBoundingClientRect(),button=field.querySelector('button').getBoundingClientRect(),utilities=root.querySelector('[data-feedback-utilities]'),notices=root.querySelector('[data-feedback-notifications]');return {id:root.dataset.catalogFeedback,inputHeight:input.height,buttonWidth:button.width,buttonHeight:button.height,fieldGap:button.left-input.right,contentGap:notices.getBoundingClientRect().top-utilities.getBoundingClientRect().bottom,fits:button.right<=field.getBoundingClientRect().right+1};});return {groups,overflow:document.documentElement.scrollWidth-innerWidth};})()`);
    assert.ok(geometry.overflow<=0&&geometry.groups.every(group=>group.inputHeight===30&&group.buttonWidth===30&&group.buttonHeight===30&&group.fieldGap===8&&group.contentGap===16&&group.fits),JSON.stringify(geometry));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-feedback-palette] .field > span, [data-feedback-palette] .example-note, [data-feedback-palette] .button-content, [data-feedback-palette] input')`);
    for(const sample of colors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    await evaluate(`document.querySelector('[data-feedback-palette=dark]').scrollIntoView({block:'start'})`);await shot(`page-${width}`);
    const overlays=[];
    for(const id of ['dark','light']) {
      await focusTip(tip('right',id));await until(fit(popup(tip('right',id))));
      const tooltipColors=await evaluate(`(${contrastSamples.toString()})('[data-feedback-tooltip=${id}] .bosca-tooltip:popover-open')`);for(const sample of tooltipColors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      await shot(`tooltip-${id}-${width}`);await key('Escape');
      const tones=[];
      for(const tone of ['info','success','warning','error']) {
        await action(tone,`${node('toast',id)}.dataset.tone==='${tone}'&&${node('toast',id)}.matches(':popover-open')`,id);
        await until(fit(node('toast',id)));
        const textColors=await evaluate(`(${contrastSamples.toString()})('[data-feedback-toast=${id}] .toast-message')`);for(const sample of textColors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
        const iconColors=await evaluate(`(${contrastSamples.toString()})('[data-feedback-toast=${id}] [data-tone-icon=${tone}]')`);for(const sample of iconColors.text)assert.ok(sample.ratio>=3,JSON.stringify(sample));
        assert.ok(await truth(`${node('toast',id)}.querySelector('[data-tone-icon=${tone}]').checkVisibility()&&${node('toast',id)}.querySelector('[data-bml-ref=message]').getAttribute('aria-live')==='${tone==='error'?'assertive':'polite'}'`));
        tones.push({tone,colors:textColors.text,icons:iconColors.text,color:await evaluate(`getComputedStyle(${node('toast',id)}).color`)});
        if(tone==='error') {await evaluate(`Promise.all(${node('toast',id)}.getAnimations({subtree:true}).map(animation=>animation.finished)).then(()=>true)`);await shot(`toast-${id}-${width}`);}
        await dismiss(id);
      }
      overlays.push({id,tooltip:tooltipColors.text,tones});
    }
    const clipped=`document.querySelector('[data-feedback-clipped]')`;await focusTip(clipped);await until(fit(popup(clipped)));
    assert.ok(await truth(`(()=>{const tooltip=${popup(clipped)},box=tooltip.getBoundingClientRect();return document.elementFromPoint(box.left+10,box.top+10)===tooltip;})()`));
    const point=await evaluate(`(()=>{const box=${popup(clipped)}.getBoundingClientRect();return {x:box.left+box.width/2,y:box.top+box.height/2};})()`);
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',...point});
    assert.ok(await truth(`${popup(clipped)}.matches(':popover-open')`));await shot(`clipped-${width}`);await key('Escape');
    measurements.push({width,...geometry,colors:colors.text,overlays});
  }
  await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:0,y:0});
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:320,deviceScaleFactor:1,mobile:false});
  await evaluate(`document.querySelector('[data-catalog-feedback=light] details').open=true`);
  await action('long',`${node('long','light')}.checked`,'light');await evaluate(`${field('light')}.focus()`);await action('error',`${node('toast','light')}.matches(':popover-open')&&${node('toast','light')}.dataset.tone==='error'`,'light');await until(fit(node('toast','light')));
  const longText=`${node('toast','light')}.querySelector('[data-bml-ref=message]')`;
  assert.ok(await truth(`${longText}.scrollHeight>${longText}.clientHeight&&${longText}.tabIndex===0`));
  await evaluate(`${longText}.focus()`);await key('End');await until(`${longText}.scrollTop>0`);await shot('long-short-viewport');await key('Escape');
  assert.ok(await truth(`!${node('toast','light')}.matches(':popover-open')&&document.activeElement===${field('light')}`),'Dismissal returns focus to the retained field');
  await navigate('/feedback');await until(`${node('info','light')}?.dataset.bmlActionBound==='true'`);
  assert.ok(await truth(`${node('toast','light')}.dataset.sequence!=='0'&&!${node('toast','light')}.matches(':popover-open')`),'A page revisit does not replay its notification');
  await call('Emulation.setScriptExecutionDisabled',{value:true});await call('Emulation.setDeviceMetricsOverride',{width:390,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/feedback');
  assert.ok(await truth(`${field('light')}.readOnly&&${field('light')}.value==='io.bosca:bml-design'&&${field('light')}.tabIndex===0&&${copy('light')}.getAttribute('aria-label')==='Copy library coordinate'&&!document.querySelector('.bosca-tooltip')&&document.documentElement.scrollWidth<=innerWidth`));
  await shot('native-390');await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-feedback.json`),JSON.stringify(measurements,null,2));
}
