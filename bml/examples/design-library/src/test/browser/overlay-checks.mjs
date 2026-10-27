import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Exercises the compiled producer's native modals and popovers with application-owned state. */
export async function checkOverlays({ call, evaluate, until, base, mode, artifacts }) {
  const edit = '[data-edit-dialog=light]';
  const draft = '[data-draft-dialog=light]';
  const popover = '[data-popover=light]';
  const trigger = root => `${root} > [data-bml-ref=trigger]`;
  const modal = root => `${root} > dialog`;
  const content = root => `${root} > [data-bml-ref=content]`;
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const focus = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  const open = root => `document.querySelector(${JSON.stringify(modal(root))})?.matches(':modal')`;
  const expanded = root => `document.querySelector(${JSON.stringify(content(root))})?.matches(':popover-open')`;
  const key = async (key, code = key, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key, code, modifiers,
      windowsVirtualKeyCode: { Escape:27, Tab:9, Enter:13, ArrowDown:40, ArrowUp:38, Home:36, End:35 }[key],
      ...(type === 'keyDown' && key === 'Enter' ? { text:'\r' } : {}) });
  };
  const listeners = async () => {
    const counts = {};
    for (const expression of ['document', 'window']) {
      const handle = await call('Runtime.evaluate', { expression });
      const result = await call('DOMDebugger.getEventListeners', { objectId:handle.result.objectId });
      counts[expression] = result.listeners.filter(listener => ['keydown','pointerdown','scroll','resize','bml:update'].includes(listener.type)).length;
      await call('Runtime.releaseObject', { objectId:handle.result.objectId });
    }
    return counts;
  };
  const instrumentation = await call('Page.addScriptToEvaluateOnNewDocument', { source:`
    window.fixtureObservers = new Set();
    const OriginalResizeObserver = window.ResizeObserver;
    window.ResizeObserver = class extends OriginalResizeObserver {
      observe(...args) { super.observe(...args); window.fixtureObservers.add(this); }
      disconnect() { super.disconnect(); window.fixtureObservers.delete(this); }
    };` });
  await call('Emulation.setDeviceMetricsOverride', { width:1440,height:1000,deviceScaleFactor:1,mobile:false });
  await call('Page.navigate', { url:base + '/overlays' });
  await until(`document.readyState === 'complete' && document.querySelector('[data-programmatic-open=light]')?.dataset.bmlActionBound === 'true' && !document.querySelector('${trigger(popover)}').hasAttribute('title')`);
  const baseline = await listeners();
  const observers = await evaluate('window.fixtureObservers.size');
  assert.equal(await evaluate('document.querySelector("#native-dialog").hasAttribute("data-bml-c")'), false);
  assert.ok(await evaluate(`document.querySelector('${edit}').classList.contains('fixture-dialog') && !document.querySelector('${edit}').hasAttribute('triggerLabel')`));
  assert.ok(await evaluate(`document.querySelector('${trigger(edit)}').getAttribute('aria-controls') === document.querySelector('${modal(edit)}').id`));
  assert.ok(await evaluate(`(() => { const ids=[...document.querySelectorAll('dialog')].map(node=>node.id); return ids.length===new Set(ids).size })()`));
  await click(trigger('[data-disabled-dialog=light]'));
  assert.equal(Boolean(await evaluate(open('[data-disabled-dialog=light]'))), false);
  await evaluate(`document.querySelector('${trigger('[data-disabled-dialog=light]')}').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
  assert.equal(Boolean(await evaluate(open('[data-disabled-dialog=light]'))), false);
  assert.deepEqual(await evaluate(`(() => { const node=document.querySelector('${trigger('[data-ghost-dialog=light]')}'); const box=node.getBoundingClientRect(); return [box.width,box.height] })()`), [24,24]);
  assert.ok(await evaluate(`document.querySelector('${trigger('[data-link-dialog=light]')}').classList.contains('link-trigger')`));
  await click(trigger(edit));
  await until(open(edit));
  assert.equal(await evaluate('document.activeElement?.name'), 'name');
  assert.equal(await evaluate(`document.querySelector('${modal(edit)}').getBoundingClientRect().width`), 520);
  assert.deepEqual(await evaluate(`(() => { const node=document.querySelector('${modal(edit)}'); return [getComputedStyle(node).backgroundColor,getComputedStyle(node.querySelector('.modal-footer')).backgroundColor] })()`), ['rgb(255, 255, 255)','rgb(248, 250, 252)']);
  const modalBorders = await evaluate(`(() => {
    const panel=document.querySelector('${modal(edit)}'),sibling=document.querySelector('[data-edit-dialog=purple] > dialog');
    const border=node=>getComputedStyle(node).borderTopColor;
    const before={light:border(panel),dark:border(sibling)};
    panel.style.setProperty('--dialog-border','rgb(116, 66, 186)');
    const override={light:border(panel),dark:border(sibling)};
    panel.style.removeProperty('--dialog-border');
    return {before,override,restored:border(panel)};
  })()`);
  assert.deepEqual(modalBorders.before,{light:'rgb(203, 213, 225)',dark:'rgb(35, 40, 57)'});
  assert.deepEqual(modalBorders.override,{light:'rgb(116, 66, 186)',dark:modalBorders.before.dark});
  assert.equal(modalBorders.restored,modalBorders.before.light,'Modal frame overrides are local and reversible');
  const axHandle = await call('Runtime.evaluate', { expression:`document.querySelector('${modal(edit)}')` });
  const ax = await call('Accessibility.getPartialAXTree', { objectId:axHandle.result.objectId,fetchRelatives:false });
  assert.equal(ax.nodes.find(node => node.role?.value === 'dialog').name.value, 'Edit display name');
  await call('Runtime.releaseObject', { objectId:axHandle.result.objectId });
  await focus(`${modal(edit)} [data-bml-ref=close]`);
  await key('Tab','Tab',8);
  assert.equal(await evaluate('document.activeElement?.type'), 'submit');
  await key('Tab');
  assert.equal(await evaluate('document.activeElement?.getAttribute("data-bml-ref")'), 'close');
  await key('Escape');
  await until(`!(${open(edit)})`);
  assert.ok(await evaluate(`document.activeElement === document.querySelector('${trigger(edit)}')`));
  await click(trigger(edit));
  await until(open(edit));
  await click(`${modal(edit)} [data-bml-ref=cancel]`);
  await until(`!(${open(edit)})`);
  await click(trigger(edit));
  await until(open(edit));
  for (const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent', { type,x:4,y:4,button:'left',clickCount:1 });
  await until(`!(${open(edit)})`);
  await click(trigger(edit));
  await until(open(edit));
  const before = await evaluate(`(() => { const box=document.querySelector('[data-modal-save=light]').getBoundingClientRect(); return [box.width,box.height] })()`);
  await evaluate(`(() => { const original=window.fetch.bind(window); window.fetch=async (...args)=>{
    if(String(args[0]).includes('/_bml/action')) { window.fetch=original; await new Promise(resolve=>{window.releaseModalAction=resolve}); }
    return original(...args); };
    document.querySelector('${modal(edit)} input[name=name]').value='retry'; document.querySelector('[data-modal-save=light]').click(); })()`);
  await until('document.querySelector("[data-modal-form=light]").hasAttribute("data-bml-action-pending") && typeof window.releaseModalAction === "function"');
  assert.deepEqual(await evaluate(`(() => { const node=document.querySelector('[data-modal-save=light]'); const box=node.getBoundingClientRect();
    return {size:[box.width,box.height],text:node.textContent.includes('Save name'),progress:getComputedStyle(node.querySelector('.button-progress')).display} })()`), {size:before,text:true,progress:'flex'});
  await evaluate('window.releaseModalAction()');
  await until(`${open(edit)} && document.querySelector('${modal(edit)} [role=alert]')?.textContent.includes('Try a different name')`);
  assert.equal(await evaluate(`document.querySelector('${modal(edit)} input[name=name]').value`), 'retry');
  assert.equal(await evaluate('document.activeElement?.name'), 'name');
  assert.ok(await evaluate(`document.querySelector('${edit}').hasAttribute('data-force-open') && !document.querySelector('${edit}').hasAttribute('data-completed')`));
  await evaluate(`document.querySelector('${modal(edit)} input[name=name]').value='Grace'`);
  await click('[data-modal-save=light]');
  await until(`!(${open(edit)}) && document.querySelector('[data-overlay-result=light]').textContent.includes('Saved Grace (2)')`);
  assert.match(await evaluate('document.querySelector("[data-overlay-result=purple]").textContent'), /\(0\)/);
  await click(trigger(edit));
  await until(open(edit));
  assert.equal(await evaluate(`document.querySelector('${modal(edit)} input[name=name]').value`), 'Grace');
  await key('Escape');
  await until(`!(${open(edit)})`);
  await click('[data-programmatic-open=light]');
  await until(open(draft));
  assert.equal(await evaluate(`document.querySelector('${modal(draft)}').getBoundingClientRect().width`), 760);
  assert.equal(await evaluate('document.activeElement?.name'), 'draft');
  await evaluate(`window.fixtureDraftPanel=document.querySelector('${modal(draft)}'); document.querySelector('${modal(draft)} input[name=draft]').value='Unsaved draft'`);
  await click(trigger('[data-nested-popover=light]'));
  await until(expanded('[data-nested-popover=light]'));
  await click('[data-nested-close=light]');
  await until(`!(${expanded('[data-nested-popover=light]')})`);
  assert.ok(await evaluate(open(draft)), 'The nested cancel control dismisses its popup while retaining the modal');
  await click(trigger('[data-nested-popover=light]'));
  await until(expanded('[data-nested-popover=light]'));
  await focus(trigger('[data-nested-popover=light]'));
  await key('Escape');
  await until(`!(${expanded('[data-nested-popover=light]')})`);
  assert.ok(await evaluate(open(draft)), 'Escape dismisses the nested popup while retaining the modal');
  await click('[data-draft-update=light]');
  await until(`${open(draft)} && document.querySelector('[data-overlay-result=light]').textContent.includes('Updates 1')`);
  assert.ok(await evaluate(`window.fixtureDraftPanel === document.querySelector('${modal(draft)}')`));
  assert.equal(await evaluate(`document.querySelector('${modal(draft)} input[name=draft]').value`), 'Unsaved draft');
  await key('Escape');
  await until(`!(${open(draft)})`);
  assert.ok(await evaluate('document.activeElement === document.querySelector("[data-programmatic-open=light]")'));
  await click('[data-draft-update=light]');
  await until('document.querySelector("[data-overlay-result=light]").textContent.includes("Updates 2")');
  assert.equal(Boolean(await evaluate(open(draft))), false, 'The same server request does not reopen a dismissed preserved dialog');
  await click('[data-programmatic-open=light]');
  await until(open(draft));
  await click('[data-draft-complete=light]');
  await until(`!(${open(draft)})`);
  await click('[data-programmatic-open=light]');
  await until(open(draft));
  await evaluate('document.querySelector("[data-palette=light]").classList.add("alternate")');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('${modal(draft)}')).backgroundColor`), 'rgb(239, 246, 255)');
  await evaluate('document.querySelector("[data-palette=light]").classList.remove("alternate")');
  let shot=await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`dialog-${mode}-1440.png`),Buffer.from(shot.data,'base64'));
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:640,deviceScaleFactor:1,mobile:false});
  const phoneLayout=`(() => { const node=document.querySelector('${modal(draft)}'); const box=node.getBoundingClientRect();
    const body=node.querySelector('.modal-fields'); const footer=node.querySelector('.modal-footer').getBoundingClientRect();
    const actions=[...node.querySelectorAll('.modal-footer button')].map(button=>button.getBoundingClientRect());
    return box.left>=16 && box.right<=document.documentElement.clientWidth-16 && box.bottom<=innerHeight && body.scrollHeight>body.clientHeight &&
      footer.bottom<=box.bottom && actions.every(button=>button.left>=footer.left+18 && button.right<=footer.right-18) })()`;
  await until(phoneLayout);

  shot=await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`dialog-${mode}-390.png`),Buffer.from(shot.data,'base64'));
  await key('Escape');
  await until(`!(${open(draft)})`);
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await click(trigger('[data-confirm-dialog=light]'));
  await until(open('[data-confirm-dialog=light]'));
  assert.equal(await evaluate(`document.querySelector('${modal('[data-confirm-dialog=light]')}').getBoundingClientRect().width`), 400);
  await key('Escape');
  const confirmationMeasurements=[];
  for(const [width,height] of [[1440,1000],[390,640],[390,360]]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
    for(const palette of ['light','purple']) {
      const root=`[data-confirm-dialog=${palette}]`;
      await click(trigger(root));await until(open(root));
      const original=await evaluate(`document.querySelector('${modal(root)} .confirmation-copy').textContent`);
      let normalHeight=0;
      for(const long of [false,true]) {
        if(long) await evaluate(`document.querySelector('${modal(root)} .confirmation-copy').textContent=${JSON.stringify('A longer explanation remains readable as the dialog grows. '.repeat(80)+'x'.repeat(280))}`);
        const size=await evaluate(`(() => {
          const panel=document.querySelector('${modal(root)}'),copy=panel.querySelector('.confirmation-copy'),footer=panel.querySelector('.modal-footer'),
            rect=node=>node.getBoundingClientRect().toJSON(),style=getComputedStyle(copy),range=document.createRange();range.selectNodeContents(copy);
          return {panel:rect(panel),copy:rect(copy),footer:rect(footer),text:range.getBoundingClientRect().toJSON(),
            padding:[style.paddingTop,style.paddingRight,style.paddingBottom,style.paddingLeft],lineHeight:parseFloat(style.lineHeight),
            parentWrapping:getComputedStyle(panel.parentElement).whiteSpace,wrapping:getComputedStyle(panel).whiteSpace,alignment:getComputedStyle(panel).textAlign,
            horizontal:panel.scrollWidth-panel.clientWidth,copyHorizontal:copy.scrollWidth-copy.clientWidth,vertical:copy.scrollHeight-copy.clientHeight};
        })()`);
        assert.equal(size.parentWrapping,'nowrap');assert.equal(size.wrapping,'normal');assert.equal(size.alignment,'left');
        assert.deepEqual(size.padding,['18px','18px','18px','18px']);
        assert.ok(size.panel.left>=16&&size.panel.right<=width-16&&size.panel.top>=0&&size.panel.bottom<=height);
        assert.ok(size.horizontal<=1&&size.copyHorizontal<=1,'Descriptions wrap instead of introducing horizontal scroll');
        assert.ok(size.footer.bottom<=size.panel.bottom&&size.footer.top>=size.copy.bottom-1,
          `Actions remain within the dialog below its description: ${JSON.stringify(size)}`);
        if(long) {
          assert.ok(size.panel.height>normalHeight,'The dialog grows vertically with its description');
          assert.ok(size.vertical>0,'Long descriptions have a vertical scrolling area');
          assert.ok(await evaluate(`(() => {const copy=document.querySelector('${modal(root)} .confirmation-copy');copy.scrollTop=copy.scrollHeight;return copy.scrollTop>0})()`));
        } else {
          normalHeight=size.panel.height;
          assert.ok(size.text.height>size.lineHeight,'The normal description wraps and grows the dialog');
        }
        confirmationMeasurements.push({width,height,palette,long,...size});
        shot=await call('Page.captureScreenshot',{format:'png'});
        await writeFile(join(artifacts,`confirmation-${mode}-${palette}-${width}-${height}-${long?'long':'normal'}.png`),Buffer.from(shot.data,'base64'));
      }
      await evaluate(`document.querySelector('${modal(root)} .confirmation-copy').textContent=${JSON.stringify(original)}`);
      await key('Escape');await until(`!(${open(root)})`);
    }
  }
  await writeFile(join(artifacts,`confirmation-${mode}.json`),JSON.stringify(confirmationMeasurements,null,2));
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await click(trigger('[data-confirm-dialog=light]'));
  await until(open('[data-confirm-dialog=light]'));
  await click(`${modal('[data-confirm-dialog=light]')} [data-bml-ref=cancel]`);
  assert.match(await evaluate('document.querySelector("[data-overlay-result=light]").textContent'), /Confirmed 0/);
  await click(trigger('[data-confirm-dialog=light]'));
  await until(open('[data-confirm-dialog=light]'));
  await click('[data-confirm=light]');
  await until('document.querySelector("[data-overlay-result=light]").textContent.includes("Confirmed 1") && !document.querySelector("dialog:modal")');
  await click('[data-force-open-action=light]');
  await until(open(edit));
  await key('Escape');
  await until(`!(${open(edit)})`);
  for (const root of [popover,'[data-popover=purple]']) {
    await evaluate(`document.querySelector('${root}').open=true`);
    await until(expanded(root));
  }
  assert.equal(await evaluate('document.activeElement?.name'),'filter');
  const themes=await evaluate(`(() => { const a=document.querySelector('${content(popover)}'); const b=document.querySelector('${content('[data-popover=purple]')}');
    const box=a.getBoundingClientRect(); const clip=a.closest('.overlay-clip').getBoundingClientRect();
    return {light:getComputedStyle(a).backgroundColor,dark:getComputedStyle(b).backgroundColor,unique:a.id!==b.id,
      owned:document.querySelector('${popover}').contains(a),width:box.width,escaped:box.bottom>clip.bottom,
      controls:document.querySelector('${trigger(popover)}').getAttribute('aria-controls')===a.id} })()`);
  assert.deepEqual(themes,{light:'rgb(255, 255, 255)',dark:'rgb(16, 19, 28)',unique:true,owned:true,width:420,escaped:true,controls:true});
  const popoverBorders=await evaluate(`(() => {
    const panel=document.querySelector('${content(popover)}'),sibling=document.querySelector('${content('[data-popover=purple]')}');
    const border=node=>getComputedStyle(node).borderTopColor;
    const before={light:border(panel),dark:border(sibling)};
    panel.style.setProperty('--popover-border','rgb(116, 66, 186)');
    const override={light:border(panel),dark:border(sibling)};
    panel.style.removeProperty('--popover-border');
    return {before,override,restored:border(panel)};
  })()`);
  assert.deepEqual(popoverBorders.before,{light:'rgb(203, 213, 225)',dark:'rgb(35, 40, 57)'});
  assert.deepEqual(popoverBorders.override,{light:'rgb(116, 66, 186)',dark:popoverBorders.before.dark});
  assert.equal(popoverBorders.restored,popoverBorders.before.light,'Popover frame overrides stay local and reversible');
  await evaluate('document.querySelector("[data-palette=light]").classList.add("alternate")');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('${content(popover)}')).backgroundColor`),'rgb(239, 246, 255)');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('${content('[data-popover=purple]')}')).backgroundColor`),'rgb(16, 19, 28)');
  await evaluate('document.querySelector("[data-palette=light]").classList.remove("alternate")');
  shot=await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`popover-${mode}-1440.png`),Buffer.from(shot.data,'base64'));
  await evaluate(`document.querySelector('[data-popover=purple]').open=false`);
  await click('[data-popover-update=light-popover]');
  await until(`(${expanded(popover)}) && document.querySelector('[data-popover-update=light-popover]').textContent.includes('Count 1')`);
  await click(`${content(popover)} [data-bml-ref=cancel]`);
  await until(`!(${expanded(popover)})`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${trigger(popover)}')`));
  await focus(trigger(popover));
  await key('Enter');
  await until(expanded(popover));
  await click(`${content(popover)} a`);
  assert.equal(await evaluate('location.hash'),'#overlay-destination');
  await evaluate('history.replaceState(null,"",location.pathname)');
  for(const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,x:2,y:2,button:'left',clickCount:1});
  await until(`!(${expanded(popover)})`);
  const menu='[data-menu=light]';
  await focus(trigger(menu));
  await key('ArrowUp');
  await until(expanded(menu));
  assert.equal(await evaluate('document.activeElement?.textContent'),'Last choice');
  await key('Home');
  assert.equal(await evaluate('document.activeElement?.textContent'),'First choice');
  await key('ArrowDown');
  assert.equal(await evaluate('document.activeElement?.textContent'),'Last choice');
  await key('ArrowDown');
  assert.equal(await evaluate('document.activeElement?.textContent'),'First choice');
  await key('End');
  assert.equal(await evaluate('document.activeElement?.textContent'),'Last choice');
  await key('Escape');
  await until(`!(${expanded(menu)})`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('${trigger(menu)}')`));
  const menuMeasurements=[];
  const measureMenu = root => evaluate(`(() => {
    const panel=document.querySelector('${root}'),heading=panel.querySelector('.menu-heading');
    return {marker:getComputedStyle(panel.querySelector('summary')).listStyleType,
      arrow:getComputedStyle(panel.querySelector('summary'),'::after').content,
      heading:getComputedStyle(heading.querySelector('strong')).fontSize,
      gap:getComputedStyle(panel.querySelector('[role=menu]')).gap,
      rows:[...panel.querySelectorAll('[role=menuitem]')].map(item=>{
        const icons=[...item.querySelectorAll('.menu-icon, .button-icon')].filter(icon=>icon.checkVisibility());
        const icon=icons[0],svg=icon.querySelector('svg'),label=(item.querySelector('.button-content')??item).lastElementChild;
        const row=item.getBoundingClientRect(),box=icon.getBoundingClientRect();
        return {count:icons.length,width:box.width,height:box.height,left:box.left,
          svgWidth:svg.getBoundingClientRect().width,svgHeight:svg.getBoundingClientRect().height,
          gap:label.getBoundingClientRect().left-box.right,rowHeight:row.height,
          text:getComputedStyle(item).color,icon:getComputedStyle(svg).color,hidden:svg.getAttribute('aria-hidden')};
      })};
  })()`);
  const checkMenu = (sample,palette) => {
    assert.equal(sample.marker,'none');assert.equal(sample.arrow,'none');
    assert.equal(sample.heading,'13px');assert.equal(sample.gap,'4px');
    for(const row of sample.rows) {
      assert.equal(row.count,1,'Hidden projected icons stay hidden without an application CSS reset');
      assert.deepEqual([row.width,row.height,row.svgWidth,row.svgHeight],[16,16,16,16]);
      assert.equal(row.gap,8);assert.equal(row.rowHeight,32);assert.equal(row.hidden,'true');
      assert.equal(row.icon,row.text,'Menu icons inherit their action color');
      assert.equal(row.left,sample.rows[0].left,'Native and library button icons align');
    }
    assert.equal(sample.rows[2].text,palette==='light'?'rgb(185, 28, 28)':'rgb(255, 84, 112)');
  };
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:640,deviceScaleFactor:1,mobile:false});
    for(const palette of ['light','purple']) {
      const root=`[data-menu=${palette}]`;
      await evaluate(`document.querySelector('${trigger(root)}').scrollIntoView({block:'center'});document.querySelector('${root}').open=true`);
      await until(expanded(root));
      const sample=await measureMenu(root);checkMenu(sample,palette);
      menuMeasurements.push({width,palette,scripts:true,...sample});
      shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`menu-${mode}-${palette}-${width}.png`),Buffer.from(shot.data,'base64'));
      await key('Escape');await until(`!(${expanded(root)})`);
    }
  }
  await writeFile(join(artifacts,`menu-${mode}.json`),JSON.stringify(menuMeasurements,null,2));
  for(const height of [640,360]) {
    await call('Emulation.setDeviceMetricsOverride',{width:390,height,deviceScaleFactor:1,mobile:false});
    await evaluate(`document.querySelector('${trigger(popover)}').scrollIntoView({block:'center'}); document.querySelector('${popover}').open=true`);
    await until(expanded(popover));
    const geometry=await evaluate(`(() => { const node=document.querySelector('${content(popover)}'); const box=node.getBoundingClientRect();
      const hit=document.elementFromPoint(box.left+box.width/2,box.top+box.height/2);
      return {left:box.left,right:box.right,top:box.top,bottom:box.bottom,width:document.documentElement.clientWidth,height:innerHeight,
        hit:node.contains(hit),target:hit?.outerHTML.slice(0,300)} })()`);
    assert.ok(geometry.left>=12 && geometry.right<=geometry.width-12 && geometry.top>=12 && geometry.bottom<=geometry.height-12 && geometry.hit,
      `Popover fits narrow/short viewports and escapes clipping: ${JSON.stringify(geometry)}`);
    if(height===640) {shot=await call('Page.captureScreenshot',{format:'png'}); await writeFile(join(artifacts,`popover-${mode}-390.png`),Buffer.from(shot.data,'base64'));}
    await key('Escape');
    await until(`!(${expanded(popover)})`);
    await focus(trigger(popover));
    await until(`document.querySelector('${popover} .bosca-tooltip:popover-open')`);
    const tooltipGeometry=await evaluate(`(() => { const node=document.querySelector('${popover} .bosca-tooltip:popover-open'); const box=node.getBoundingClientRect();
      return {top:box.top,bottom:box.bottom,width:box.width,height:innerHeight,style:node.style.cssText,parent:node.parentElement.tagName,visible:node.checkVisibility()} })()`);
    assert.ok(tooltipGeometry.visible && tooltipGeometry.width>0 && tooltipGeometry.top>=8 && tooltipGeometry.bottom<=tooltipGeometry.height-8,
      `Tooltip fits the browser height in a short document: ${JSON.stringify(tooltipGeometry)}`);
    await key('Escape');
  }
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  for(let repeat=0;repeat<2;repeat++) {
    await evaluate(`document.querySelector('${popover}').open=true`);
    await until(expanded(popover));
    await click(trigger(edit));
    await until(open(edit));
    await evaluate(`window.fixtureRemovedModal=document.querySelector('${modal(edit)}')`);
    await click('[data-toggle-overlays=light]');
    await until('!document.querySelector("[data-popover=light]")');
    assert.ok(await evaluate('!window.fixtureRemovedModal.isConnected && !document.querySelector("dialog:modal")'), 'Unmount removes the open modal and popup from the top layer');
    await click('[data-toggle-overlays=light]');
    await until(`document.querySelector('${popover}') && !document.querySelector('${trigger(popover)}').hasAttribute('title')`);
  }
  for(let attempt=0;attempt<2 && await evaluate('!!document.querySelector("dialog:modal")');attempt++) await key('Escape');
  assert.deepEqual(await listeners(),baseline,'Overlay updates and repeated disposal do not leak document/window listeners');
  assert.equal(await evaluate('window.fixtureObservers.size'),observers,'Popover ResizeObservers disconnect on unmount');
  assert.equal(await evaluate('document.querySelectorAll(".popover-content:popover-open").length'),0);
  await call('Page.removeScriptToEvaluateOnNewDocument',{identifier:instrumentation.identifier});
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  try {
    await call('Emulation.setDeviceMetricsOverride',{width:390,height:640,deviceScaleFactor:1,mobile:false});
    await call('Page.navigate',{url:base+'/overlays'});
    await until(`document.readyState==='complete'&&document.querySelector('${menu} > [data-bml-design-popover-content]:not([popover])')`);
    for(const palette of ['light','purple']) {
      const root=`[data-menu=${palette}]`;
      await click(trigger(root));
      await until(`document.querySelector('${root}').open`);
      assert.ok(await evaluate(`document.querySelector('${content(root)}').checkVisibility()`));
      const sample=await measureMenu(root);checkMenu(sample,palette);
      menuMeasurements.push({width:390,palette,scripts:false,...sample});
    }
  } finally {await call('Emulation.setScriptExecutionDisabled',{value:false});}
  await writeFile(join(artifacts,`menu-${mode}.json`),JSON.stringify(menuMeasurements,null,2));
}
