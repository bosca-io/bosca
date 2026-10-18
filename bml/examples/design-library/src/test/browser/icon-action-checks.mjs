import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real DOM, native popovers and server actions in the isolated compiled consumer. */
export async function checkIconActions({ call, evaluate, until, base, mode, artifacts }) {
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
  const action = '[data-icon-action=light]';
  const popup = selector => `document.querySelector(${JSON.stringify(selector)})?.getAttribute('aria-describedby')?.split(/\\s+/).map(id => document.getElementById(id)).find(node => node?.matches('.bosca-tooltip:popover-open'))`;
  const focus = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  const hover = async selector => {
    const point = await evaluate(`(() => { const node=document.querySelector(${JSON.stringify(selector)}); node.scrollIntoView({block:'center'});
      const box=node.getBoundingClientRect(); return {x:box.left+box.width/2,y:box.top+box.height/2} })()`);
    await call('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point });
  };
  const clear = async () => {
    await evaluate('document.activeElement?.blur()');
    await call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 0, y: 0 });
    await until('!document.querySelector(".bosca-tooltip:popover-open")');
  };
  const escape = async () => {
    for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  };
  const listeners = async () => {
    const counts = {};
    for (const expression of ['document', 'window']) {
      const handle = await call('Runtime.evaluate', { expression });
      const result = await call('DOMDebugger.getEventListeners', { objectId: handle.result.objectId });
      counts[expression] = result.listeners.filter(listener => ['keydown','scroll','resize'].includes(listener.type)).length;
      await call('Runtime.releaseObject', { objectId: handle.result.objectId });
    }
    return counts;
  };
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: base + '/actions' });
  await until(`document.readyState === 'complete' && document.querySelector('${action}')?.dataset.bmlActionBound === 'true' && !document.querySelector('${action}').hasAttribute('title') && !document.querySelector('[data-icon-action=purple]').hasAttribute('title')`);
  const baseline = await listeners();
  const controls = await evaluate(`(() => {
    const button=document.querySelector('${action}'); const small=document.querySelector('[data-xs-action=light]');
    const link=document.querySelector('[data-icon-link=light]');
    return { button:button instanceof HTMLButtonElement, type:button.type, label:button.getAttribute('aria-label'),
      size:[button.getBoundingClientRect().width,button.getBoundingClientRect().height],
      small:[small.getBoundingClientRect().width,small.getBoundingClientRect().height],
      classes:button.classList.contains('icon-only') && button.classList.contains('ghost'),
      ref:button.getAttribute('data-bml-ref'), propsConsumed:!button.hasAttribute('icon') && !button.hasAttribute('label') && !button.hasAttribute('size'),
      nativeAction:button.hasAttribute('data-bml-method'), disabled:document.querySelector('[data-disabled-action=light]').disabled,
      link:link instanceof HTMLAnchorElement && link.getAttribute('href') === '#link-target' && link.getAttribute('rel') === 'help',
      linkSize:[link.getBoundingClientRect().width,link.getBoundingClientRect().height] };
  })()`);
  assert.deepEqual(controls, { button:true, type:'button', label:'Increment', size:[30,30], small:[24,24], classes:true,
    ref:'increment', propsConsumed:true, nativeAction:true, disabled:true, link:true, linkSize:[30,30] });
  await evaluate('document.querySelector("[data-disabled-action=light]").click()');
  assert.match(await evaluate('document.querySelector("[data-action-count=light]").textContent'), /Count 0/);
  await focus('[data-icon-action=purple]');
  await until(popup('[data-icon-action=purple]'));
  await hover(action);
  await until(popup(action));
  const themes = await evaluate(`(() => { const light=${popup(action)}; const dark=${popup('[data-icon-action=purple]')};
    return { unique:light.id !== dark.id, light:getComputedStyle(light).backgroundColor, dark:getComputedStyle(dark).backgroundColor,
      text:getComputedStyle(light).color, owned:document.querySelector('${action}').contains(light),
      described:document.querySelector('${action}').getAttribute('aria-describedby').split(' ').includes('light-hint'),
      parent:light.parentElement.tagName } })()`);
  assert.deepEqual(themes, { unique:true, light:'rgb(226, 232, 240)', dark:'rgb(29, 34, 48)', text:'rgb(15, 23, 42)', owned:true, described:true, parent:'BUTTON' });
  await evaluate('document.querySelector("[data-palette=light]").classList.add("alternate")');
  assert.equal(await evaluate(`getComputedStyle(${popup(action)}).backgroundColor`), 'rgb(219, 234, 254)');
  assert.equal(await evaluate(`getComputedStyle(${popup(action)}).color`), 'rgb(30, 58, 138)');
  assert.equal(await evaluate(`getComputedStyle(${popup('[data-icon-action=purple]')}).backgroundColor`), 'rgb(29, 34, 48)');
  await evaluate('document.querySelector("[data-palette=light]").classList.remove("alternate")');
  let shot = await call('Page.captureScreenshot', { format:'png' });
  await writeFile(join(artifacts, `actions-${mode}-1440.png`), Buffer.from(shot.data,'base64'));
  const tooltipPoint = await evaluate(`(() => { const box=(${popup(action)}).getBoundingClientRect(); return {x:box.left+box.width/2,y:box.top+box.height/2} })()`);
  await call('Input.dispatchMouseEvent', { type:'mouseMoved', ...tooltipPoint });
  await pause(180);
  assert.ok(await evaluate(popup(action)), 'Tooltip remains open while its text is hovered');
  await evaluate(`(${popup(action)}).click()`);
  assert.match(await evaluate('document.querySelector("[data-action-count=light]").textContent'), /Count 0/);
  await escape();
  await until('!document.querySelector(".bosca-tooltip:popover-open")');
  assert.equal(await evaluate(`document.querySelector('${action}').getAttribute('aria-describedby')`), 'light-hint');
  await clear();
  const delayed = '[data-delayed=light]';
  const started = await evaluate(`(() => { document.querySelector('${delayed}').focus(); return performance.now() })()`);
  assert.equal(Boolean(await evaluate(popup(delayed))), false);
  await until(popup(delayed));
  assert.ok(await evaluate(`performance.now() - ${started}`) >= 300);
  await clear();
  await focus(delayed);
  await escape();
  await evaluate('window.dispatchEvent(new Event("scroll"))');
  await pause(400);
  assert.equal(Boolean(await evaluate(popup(delayed))), false, 'Escape cancels the pending tooltip');
  await clear();
  for (const placement of ['top','bottom','left','right']) {
    const selector = `.light [data-placed=${placement}]`;
    await focus(selector);
    await until(popup(selector));
    const geometry = await evaluate(`(() => { const tip=(${popup(selector)}).getBoundingClientRect(); const anchor=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
      return {left:tip.left,right:tip.right,top:tip.top,bottom:tip.bottom,anchorLeft:anchor.left,anchorRight:anchor.right,anchorTop:anchor.top,anchorBottom:anchor.bottom,width:document.documentElement.clientWidth,height:document.documentElement.clientHeight} })()`);
    assert.ok(geometry.left >= 8 && geometry.right <= geometry.width-8 && geometry.top >= 8 && geometry.bottom <= geometry.height-8, JSON.stringify(geometry));
    if (placement === 'top') assert.ok(geometry.bottom <= geometry.anchorTop-7, JSON.stringify(geometry));
    if (placement === 'bottom') assert.ok(geometry.top >= geometry.anchorBottom+7, JSON.stringify(geometry));
    if (placement === 'left') assert.ok(geometry.right <= geometry.anchorLeft-7, JSON.stringify(geometry));
    if (placement === 'right') assert.ok(geometry.left >= geometry.anchorRight+7, JSON.stringify(geometry));
    await clear();
  }
  await evaluate(`document.querySelector('${action}').click()`);
  await until('document.querySelector("[data-action-count=light]").textContent.includes("Count 1")');
  assert.match(await evaluate('document.querySelector("[data-action-count=purple]").textContent'), /Count 0/);
  await evaluate('document.querySelector("[data-rename=light]").click()');
  await until(`document.querySelector('${action}').getAttribute('aria-label') === 'Updated action' && !document.querySelector('${action}').hasAttribute('title')`);
  await focus(action);
  await until(popup(action));
  assert.equal(await evaluate(`(${popup(action)}).textContent`), 'Updated action');
  const axHandle = await call('Runtime.evaluate', { expression:`document.querySelector('${action}')` });
  const ax = await call('Accessibility.getPartialAXTree', { objectId:axHandle.result.objectId, fetchRelatives:false });
  assert.equal(ax.nodes.find(node => node.role?.value === 'button').name.value, 'Updated action');
  const oldId = await evaluate(`(${popup(action)}).id`);
  await evaluate('document.querySelector("[data-toggle-actions=light]").click()');
  await until(`!document.querySelector('${action}')`);
  assert.equal(await evaluate(`document.getElementById(${JSON.stringify(oldId)})`), null);
  await evaluate('document.querySelector("[data-toggle-actions=light]").click()');
  await until(`document.querySelector('${action}') && !document.querySelector('${action}').hasAttribute('title')`);
  await clear();
  await hover(action);
  await evaluate('document.querySelector("[data-toggle-actions=light]").click()');
  await until(`!document.querySelector('${action}')`);
  await pause(300);
  assert.equal(await evaluate('document.querySelector(".light .bosca-tooltip:popover-open")'), null);
  await evaluate('document.querySelector("[data-toggle-actions=light]").click()');
  await until(`document.querySelector('${action}') && !document.querySelector('${action}').hasAttribute('title')`);
  const replace = '[data-replacement=light-replacement]';
  await focus(replace);
  await until(popup(replace));
  await evaluate(`document.querySelector('${replace}').click()`);
  await until(`document.querySelector('${replace}').textContent.includes('Replacement 1')`);
  await focus(replace);
  await until(popup(replace));
  assert.equal(await evaluate(`(${popup(replace)}).textContent`), 'Replacement hint');
  await clear();
  const submit = '[data-icon-submit=light]';
  const before = await evaluate(`(() => { const box=document.querySelector('${submit}').getBoundingClientRect(); return [box.width,box.height] })()`);
  await evaluate(`(() => { const original=window.fetch.bind(window); window.fetch=async (...args) => {
    if (String(args[0]).includes('/_bml/action')) { window.fetch=original; await new Promise(resolve => { window.releaseIconAction=resolve; }); }
    return original(...args); };
    document.querySelector('[data-icon-form=light] input[name=name]').value='Grace'; document.querySelector('${submit}').click(); })()`);
  await until('document.querySelector("[data-icon-form=light]").hasAttribute("data-bml-action-pending") && typeof window.releaseIconAction === "function"');
  assert.deepEqual(await evaluate(`(() => { const node=document.querySelector('${submit}'); const box=node.getBoundingClientRect();
    return {size:[box.width,box.height],name:node.getAttribute('aria-label'),content:getComputedStyle(node.querySelector('.button-content')).opacity,progress:getComputedStyle(node.querySelector('.button-progress')).display} })()`),
  { size:before, name:'Save name', content:'0', progress:'flex' });
  await evaluate('window.releaseIconAction()');
  await until('document.querySelector("[data-icon-result=light]").textContent.includes("Saved Grace (1)")');
  assert.match(await evaluate('document.querySelector("[data-icon-result=purple]").textContent'), /Saved\s+\(0\)/);
  assert.deepEqual(await listeners(), baseline, 'Repeated patches and unmounts remove their document/window listeners');
  await evaluate('document.querySelector("[data-icon-link=light]").click()');
  assert.equal(await evaluate('location.hash'), '#link-target');
  await evaluate('history.replaceState(null,"",location.pathname); window.scrollTo(0,0)');
  for (const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride', { width,height:1000,deviceScaleFactor:1,mobile:false });
    await clear();
    const clipped = '[data-clipped=light]';
    await focus(clipped);
    await until(popup(clipped));
    assert.ok(await evaluate(`(() => { const tip=(${popup(clipped)}); const box=tip.getBoundingClientRect(); const clip=document.querySelector('${clipped}').closest('.tooltip-clip').getBoundingClientRect();
      return box.bottom > clip.bottom && box.left >= 8 && box.right <= document.documentElement.clientWidth-8 && tip.contains(document.elementFromPoint(box.left+box.width/2,box.top+box.height/2)) })()`), 'Popover escapes clipped ancestors and fits the viewport');
    assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'), `Actions page fits ${width}px`);
    shot = await call('Page.captureScreenshot', { format:'png' });
    await writeFile(join(artifacts, `tooltips-${mode}-${width}.png`), Buffer.from(shot.data,'base64'));
    await clear();
  }
}
