import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Real modal form outcomes, preserved drafts and independently themed top-layer popovers. */
export async function checkOverlays({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const truth = expression => evaluate(`Boolean(${expression})`);
  const root = id => `document.querySelector('[data-catalog-overlay=${id}]')`;
  const edit = id => `document.querySelector('[data-catalog-edit=${id}]')`;
  const draft = id => `document.querySelector('[data-catalog-draft=${id}]')`;
  const popup = id => `document.querySelector('[data-catalog-popover=${id}]')`;
  const panel = node => `${node}.querySelector('dialog')`;
  const trigger = node => `${node}.querySelector('[data-bml-ref=trigger]')`;
  const content = node => `${node}.querySelector('[data-bml-ref=content]')`;
  const form = id => `${edit(id)}.querySelector('form')`;
  const toast = id => `document.querySelector('[data-overlay-toast=${id}]')`;
  const rejection = id => `document.querySelector('[data-overlay-rejection=${id}]')`;
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key = async (value, modifiers=0) => {
    for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:value,modifiers,
      windowsVirtualKeyCode:{Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35}[value],
      ...(type==='keyDown'&&value==='Enter'?{text:'\r'}:{})});
  };
  const action = async (selector, done) => {
    await evaluate(`window.overlayAction=document.querySelector(${JSON.stringify(selector)});window.overlayAction.click()`);
    await until(`!window.overlayAction.isConnected&&(${done})`);
  };
  const open = async node => {
    await evaluate(`${trigger(node)}.click()`); await until(`${panel(node)}.matches(':modal')`);
  };
  const close = async node => { await key('Escape'); await until(`!${panel(node)}.open`); };
  const screenshot = async name => {
    const shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-overlays-${name}.png`),Buffer.from(shot.data,'base64'));
  };
  const listeners = async () => {
    const result={};
    for(const expression of ['document','window']) {
      const remote=await call('Runtime.evaluate',{expression});
      const found=await call('DOMDebugger.getEventListeners',{objectId:remote.result.objectId});
      result[expression]=found.listeners.filter(listener=>['keydown','pointerdown','resize','scroll','bml:update'].includes(listener.type)).length;
      await call('Runtime.releaseObject',{objectId:remote.result.objectId});
    }
    return result;
  };
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/overlays');
  await until(`${form('dark')}.dataset.bmlActionBound==='true'&&${popup('dark')}.querySelector('form').dataset.bmlActionBound==='true'`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`),'/overlays');
  assert.ok(await truth(`(()=>{const ids=[...document.querySelectorAll('dialog,[popover]')].map(node=>node.id).filter(Boolean);return ids.length===new Set(ids).size;})()`));
  assert.ok(await truth(`${trigger(edit('dark'))}.getAttribute('aria-controls')===${panel(edit('dark'))}.id`));
  const baseline=await listeners();
  await evaluate(`${root('dark')}.querySelector('details[data-example-states]').open=true`);
  await action('[data-overlay-disabled=dark]',`${trigger(edit('dark'))}.disabled`);
  await evaluate(`${trigger(edit('dark'))}.dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
  assert.ok(await truth(`!${panel(edit('dark'))}.open`));
  await action('[data-overlay-disabled=dark]',`!${trigger(edit('dark'))}.disabled`);
  await open(edit('dark'));
  assert.equal(await evaluate('document.activeElement.name'),'name');
  const ax=await call('Runtime.evaluate',{expression:panel(edit('dark'))});
  const tree=await call('Accessibility.getPartialAXTree',{objectId:ax.result.objectId,fetchRelatives:false});
  assert.equal(tree.nodes.find(node=>node.role?.value==='dialog').name.value,'Edit display name');
  await call('Runtime.releaseObject',{objectId:ax.result.objectId});
  await evaluate(`${edit('dark')}.querySelector('[data-bml-ref=close]').focus()`); await key('Tab',8);
  assert.equal(await evaluate('document.activeElement.type'),'submit');
  await key('Tab'); assert.equal(await evaluate('document.activeElement.dataset.bmlRef'),'close');
  await close(edit('dark'));
  assert.ok(await truth(`document.activeElement===${trigger(edit('dark'))}`));
  await open(edit('dark')); await evaluate(`${edit('dark')}.querySelector('[data-bml-ref=cancel]').click()`);
  await until(`!${panel(edit('dark'))}.open`);
  await open(edit('dark'));
  for(const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,x:3,y:3,button:'left',clickCount:1});
  await until(`!${panel(edit('dark'))}.open`);
  await open(edit('dark'));
  await evaluate(`${form('dark')}.elements.name.value=' x ';${form('dark')}.requestSubmit()`);
  await until(`${panel(edit('dark'))}.matches(':modal')&&${form('dark')}.querySelector('[role=alert]')?.textContent==='Use at least three characters.'`);
  assert.ok(await truth(`${form('dark')}.elements.name.value===' x '&&${form('dark')}.elements.name.getAttribute('aria-describedby')==='overlay-error-dark'&&!${toast('dark')}.matches(':popover-open')`));
  await close(edit('dark'));
  await action('[data-overlay-reject=dark]',`${root('dark')}.querySelector('[data-overlay-reject]').checked`);
  await open(edit('dark'));
  const rejected='  Keep this display name  ';
  await evaluate(`(() => {const original=window.fetch.bind(window);window.fetch=async(...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseOverlaySave=resolve;});}
    return original(...args);
  };window.overlaySaveSize=[${form('dark')}.querySelector('[type=submit]').getBoundingClientRect().width,${form('dark')}.querySelector('[type=submit]').getBoundingClientRect().height];${form('dark')}.elements.name.value=${JSON.stringify(rejected)};${form('dark')}.requestSubmit();})()`);
  await until(`typeof window.releaseOverlaySave==='function'&&${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.deepEqual(await evaluate(`(()=>{const node=${form('dark')}.querySelector('[type=submit]'),box=node.getBoundingClientRect();return [box.width,box.height];})()`),await evaluate('window.overlaySaveSize'));
  assert.equal(await evaluate(`getComputedStyle(${form('dark')}.querySelector('.button-progress')).display`),'none');
  assert.equal(await evaluate(`getComputedStyle(${form('dark')}.querySelector('[type=submit] .button-progress')).display`),'flex');
  await evaluate('window.releaseOverlaySave()');
  await until(`${rejection('dark')}.dataset.sequence==='1'&&${rejection('dark')}.matches(':popover-open')&&${panel(edit('dark'))}.matches(':modal')`);
  assert.ok(await truth(`${form('dark')}.elements.name.value===${JSON.stringify(rejected)}&&!${form('dark')}.querySelector('[role=alert]')&&${rejection('dark')}.querySelector('[role=alert]').textContent==='Save rejected. Your draft was kept.'`));
  await evaluate(`${rejection('dark')}.querySelector('[data-bml-ref=dismiss]').focus()`);
  assert.ok(await truth(`${rejection('dark')}.contains(document.activeElement)`),'A rejection notice must be keyboard reachable while its modal is open');
  await evaluate(`${rejection('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await close(edit('dark'));
  await action('[data-overlay-reject=dark]',`!${root('dark')}.querySelector('[data-overlay-reject]').checked`);
  await open(edit('dark')); await evaluate(`${form('dark')}.requestSubmit()`);
  await until(`!${panel(edit('dark'))}.open&&${toast('dark')}.dataset.sequence==='2'&&${toast('dark')}.matches(':popover-open')`);
  assert.ok(await truth(`document.activeElement===${trigger(edit('dark'))}&&${form('dark')}.elements.name.value===${JSON.stringify(rejected)}&&${form('light')}.elements.name.value==='Design library'`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await action('[data-overlay-open=dark]',`${panel(draft('dark'))}.matches(':modal')`);
  await evaluate(`window.retainedDraft=${panel(draft('dark'))};window.retainedDraftField=${draft('dark')}.querySelector('input');window.retainedDraftField.value='  My unsaved draft  ';window.retainedDraftField.focus();window.retainedDraftField.setSelectionRange(3,10)`);
  await click('[data-draft-refresh=dark]');
  await until(`${root('dark')}.dataset.draftRefreshes==='1'&&${panel(draft('dark'))}.matches(':modal')`);
  assert.ok(await truth(`${panel(draft('dark'))}===window.retainedDraft&&${draft('dark')}.querySelector('input')===window.retainedDraftField&&document.activeElement===window.retainedDraftField&&window.retainedDraftField.value==='  My unsaved draft  '&&window.retainedDraftField.selectionStart===3&&window.retainedDraftField.selectionEnd===10`));
  const nested=`document.querySelector('[data-nested-popover=dark]')`;
  await evaluate(`${trigger(nested)}.click()`); await until(`${content(nested)}.matches(':popover-open')`);
  await evaluate(`${nested}.querySelector('[data-bml-ref=cancel]').click()`);
  await until(`!${content(nested)}.matches(':popover-open')`);
  assert.ok(await truth(`${panel(draft('dark'))}.matches(':modal')`),'Closing nested options must retain the containing modal');
  await evaluate(`${trigger(nested)}.click()`); await until(`${content(nested)}.matches(':popover-open')`);
  await evaluate(`${trigger(nested)}.focus()`); await key('Escape');
  await until(`!${content(nested)}.matches(':popover-open')`);
  assert.ok(await truth(`${panel(draft('dark'))}.matches(':modal')`));
  await close(draft('dark'));
  assert.ok(await truth(`document.activeElement===document.querySelector('[data-overlay-open=dark]')`));
  await action('[data-overlay-refresh=dark]',`${toast('dark')}.dataset.sequence==='3'`);
  assert.ok(await truth(`!${panel(draft('dark'))}.open`),'An unchanged request cannot reopen a dismissed draft');
  await action('[data-overlay-open=dark]',`${panel(draft('dark'))}.matches(':modal')`);
  await evaluate(`${draft('dark')}.querySelector('form').requestSubmit()`);
  await until(`!${panel(draft('dark'))}.open&&${toast('dark')}.dataset.sequence==='4'`);
  assert.ok(await truth(`document.activeElement===document.querySelector('[data-overlay-open=dark]')`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);

  await evaluate(`${trigger(popup('dark'))}.focus()`); await key('Enter');
  await until(`${content(popup('dark'))}.matches(':popover-open')&&document.activeElement.name==='filter'`);
  await evaluate(`window.retainedPopup=${popup('dark')};window.popupContent=${content(popup('dark'))};${popup('dark')}.querySelector('input').value='  Layout options  ';${popup('dark')}.querySelector('form').requestSubmit()`);
  await until(`document.querySelector('[data-popover-toast=dark]').dataset.sequence==='1'&&${content(popup('dark'))}.matches(':popover-open')`);
  assert.ok(await truth(`${popup('dark')}===window.retainedPopup&&${popup('dark')}.querySelector('input').value==='  Layout options  '&&${popup('light')}.querySelector('input').value==='Components'`));
  await evaluate(`document.querySelector('[data-popover-toast=dark] [data-bml-ref=dismiss]').click()`);
  await action('[data-overlay-refresh=dark]',`${toast('dark')}.dataset.sequence==='5'`);
  assert.deepEqual(await evaluate(`({sameRoot:${popup('dark')}===window.retainedPopup,sameContent:${content(popup('dark'))}===window.popupContent,open:${popup('dark')}.open,topLayer:${content(popup('dark'))}.matches(':popover-open'),value:${popup('dark')}.querySelector('input').value})`),{sameRoot:true,sameContent:true,open:true,topLayer:true,value:'  Layout options  '});
  await evaluate(`${trigger(popup('dark'))}.focus()`); await key('Escape');
  await until(`!${content(popup('dark'))}.matches(':popover-open')`);
  assert.ok(await truth(`document.activeElement===${trigger(popup('dark'))}`));
  const menu=`document.querySelector('[data-catalog-menu=dark]')`;
  await evaluate(`${trigger(menu)}.focus()`); await key('ArrowUp');
  await until(`${content(menu)}.matches(':popover-open')`);
  assert.equal(await evaluate('document.activeElement.textContent'),'Close menu');
  await key('Home'); assert.equal(await evaluate('document.activeElement.textContent'),'Read the authoring notes');
  await key('ArrowDown'); assert.equal(await evaluate('document.activeElement.textContent'),'Close menu');
  await key('ArrowDown'); assert.equal(await evaluate('document.activeElement.textContent'),'Read the authoring notes');
  await key('End'); await key('Escape');
  await until(`!${content(menu)}.matches(':popover-open')`);
  for(const style of ['button','plain','block','link','ghost']) {
    const node=`document.querySelector('[data-dialog-style=${style}]')`;
    await open(node); await close(node);
  }
  const widths={sm:400,md:520,lg:760};
  for(const [size,width] of Object.entries(widths)) {
    const node=`document.querySelector('[data-dialog-size=${size}]')`;
    await open(node); assert.equal(await evaluate(`${panel(node)}.getBoundingClientRect().width`),width); await close(node);
  }
  assert.ok(await truth(`document.querySelector('[data-disabled-dialog] [data-bml-ref=trigger]').disabled`));
  for(let cycle=0;cycle<2;cycle++) {
    await action('[data-overlay-mounted=dark]',`!${edit('dark')}&&!window.retainedPopup.isConnected&&!window.retainedDraft.isConnected`);
    const removed=await listeners();
    assert.ok(removed.document<baseline.document&&removed.window<baseline.window);
    await action('[data-overlay-mounted=dark]',`${edit('dark')}&&${popup('dark')}.querySelector('form').dataset.bmlActionBound==='true'`);
    assert.deepEqual(await listeners(),baseline);
  }
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:640,deviceScaleFactor:1,mobile:false});
    await evaluate(`document.querySelector('[data-catalog-light]').click();document.fonts.ready`);
    await evaluate(`document.querySelectorAll('.toast-region:popover-open [data-bml-ref=dismiss]').forEach(button=>button.click());document.querySelectorAll('[data-example-states]').forEach(details=>{details.open=false;});document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);
    const page=await evaluate(`(()=>{const cards=[...document.querySelectorAll('.catalog-grid .ui-card')].map(card=>card.getBoundingClientRect());return {overflow:document.documentElement.scrollWidth-innerWidth,cardGap:cards[1].top-cards[0].bottom};})()`);
    assert.ok(page.overflow<=0&&(width>900||page.cardGap>=23.5),JSON.stringify(page));
    await screenshot(`page-${width}`);
    const dialogs=[];
    for(const id of ['dark','light']) {
      await action(`[data-overlay-open=${id}]`,`${panel(draft(id))}.matches(':modal')`);
      const geometry=await evaluate(`(()=>{const node=${panel(draft(id))},box=node.getBoundingClientRect(),body=node.querySelector('.modal-fields'),footer=node.querySelector('.modal-footer').getBoundingClientRect();return {dialogWidth:box.width,left:box.left,right:box.right,top:box.top,bottom:box.bottom,scrolling:body.scrollHeight>body.clientHeight,footer:footer.bottom<=box.bottom,overflow:document.documentElement.scrollWidth-innerWidth,gap:parseFloat(getComputedStyle(body).gap),background:getComputedStyle(node).backgroundColor};})()`);
      assert.ok(geometry.left>=16&&geometry.right<=width-16&&geometry.top>=0&&geometry.bottom<=640&&geometry.scrolling&&geometry.footer&&geometry.overflow<=0&&geometry.gap>=14,JSON.stringify(geometry));
      assert.equal(geometry.background,id==='dark'?'rgb(16, 19, 28)':'rgb(255, 255, 255)');
      const colors=await evaluate(`(${contrastSamples.toString()})('[data-catalog-draft=${id}] .example-note, [data-catalog-draft=${id}] .field span, [data-catalog-draft=${id}] .field small, [data-catalog-draft=${id}] input, [data-catalog-draft=${id}] h2')`);
      for(const sample of colors.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      dialogs.push({id,...geometry,colors:colors.text});
      await screenshot(`draft-${id}-${width}`); await close(draft(id));
    }
    const palettes=[];
    for(const id of ['dark','light']) {
      await evaluate(`${trigger(popup(id))}.scrollIntoView({block:'center'});${trigger(popup(id))}.click()`);
      await until(`${content(popup(id))}.matches(':popover-open')`);
      const box=await evaluate(`(()=>{const content=${content(popup(id))},rect=content.getBoundingClientRect(),clip=content.closest('.catalog-overlay-clip').getBoundingClientRect();return {left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom,escaped:rect.height>clip.height&&content.contains(document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2)),background:getComputedStyle(content).backgroundColor};})()`);
      assert.ok(box.left>=12&&box.right<=width-12&&box.top>=12&&box.bottom<=640-12&&box.escaped,JSON.stringify(box));
      assert.equal(box.background,id==='dark'?'rgb(16, 19, 28)':'rgb(255, 255, 255)');
      const popupColors=await evaluate(`(${contrastSamples.toString()})('[data-catalog-popover=${id}] .field span, [data-catalog-popover=${id}] .field small, [data-catalog-popover=${id}] input, [data-catalog-popover=${id}] .button-content')`);
      for(const sample of popupColors.text) assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      palettes.push({id,...box,colors:popupColors.text});
      await screenshot(`popover-${id}-${width}`);
      await evaluate(`${trigger(popup(id))}.focus()`); await key('Escape');
      await until(`!${content(popup(id))}.matches(':popover-open')`);
    }
    measurements.push({width,page,dialogs,palettes});
  }
  await writeFile(join(artifacts,`catalog-${mode}-overlays.json`),JSON.stringify(measurements,null,2));
  for(const id of ['dark','light']) {
    const confirmation=`document.querySelector('[data-catalog-confirm=overlay-${id}]')`;
    await open(confirmation);
    await evaluate(`${confirmation}.querySelector('input').value='  Keep the sample  ';${confirmation}.querySelector('form').requestSubmit()`);
    await until(`document.querySelector('[data-catalog-confirm-toast=overlay-${id}]').dataset.sequence==='1'&&document.querySelector('[data-catalog-confirm-toast=overlay-${id}]').matches(':popover-open')&&${panel(confirmation)}.matches(':modal')`);
    assert.ok(await truth(`${confirmation}.querySelector('input').value==='  Keep the sample  '&&!${confirmation}.querySelector('form [role=alert]')`));
    await evaluate(`document.querySelector('[data-catalog-confirm-toast=overlay-${id}] [data-bml-ref=dismiss]').focus()`);
    assert.ok(await truth(`document.querySelector('[data-catalog-confirm-toast=overlay-${id}]').contains(document.activeElement)`));
    await key('Enter'); await close(confirmation);
  }
  await call('Emulation.setScriptExecutionDisabled',{value:true}); await navigate('/overlays');
  await evaluate(`${trigger(popup('light'))}.click()`);
  assert.ok(await truth(`${popup('light')}.open&&!${content(popup('light'))}.hasAttribute('popover')&&${popup('light')}.querySelector('input').value==='Components'&&!${panel(edit('light'))}.open&&document.documentElement.scrollWidth<=innerWidth`));
  await evaluate(`${content(popup('light'))}.scrollIntoView({block:'center'})`);
  assert.ok(await truth(`(()=>{const content=${content(popup('light'))},box=content.getBoundingClientRect(),owner=${root('light')}.getBoundingClientRect();return box.width>200&&box.left>=owner.left&&box.right<=owner.right&&content.contains(document.elementFromPoint(box.left+box.width/2,box.top+box.height/2));})()`));
  await screenshot('native-390');
  await call('Emulation.setScriptExecutionDisabled',{value:false});
}
