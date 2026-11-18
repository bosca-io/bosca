import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Exercises the compiled library without a product adapter or Git dependency. */
export async function checkWorkspaces({ call, evaluate, until, base, mode, artifacts }) {
  const primary = '[data-primary-workspace]', secondary = '[data-secondary-workspace]';
  const toggle = root => `${root} [data-bml-fullscreen-button]`;
  const frame = () => evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  const click = async selector => {
    const point = await evaluate(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
    for (const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,...point,button:'left',clickCount:1});
  };
  const expanded = async (root, value) => {
    await until(`document.querySelector(${JSON.stringify(root)}).hasAttribute('data-bml-workspace-expanded')===${value}&&document.querySelector(${JSON.stringify(toggle(root))}).getAttribute('aria-busy')!=='true'`);
    await frame();
  };
  const escape = async () => {
    for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
    await frame();
  };
  const iconControl = async (root, expanded, label = expanded ? 'Exit full screen' : 'Full screen') => {
    const state=await evaluate(`(() => {
      const button=document.querySelector(${JSON.stringify(toggle(root))}),rect=button.getBoundingClientRect(),content=button.cloneNode(true);
      content.querySelectorAll('[role=tooltip]').forEach(e=>e.remove());
      return {label:button.getAttribute('aria-label'),tooltip:button.dataset.tooltipContent,pressed:button.getAttribute('aria-pressed'),
        width:rect.width,height:rect.height,text:content.textContent.trim(),
        enter:button.querySelector('[data-fullscreen-enter-icon]').checkVisibility(),exit:button.querySelector('[data-fullscreen-exit-icon]').checkVisibility()};
    })()`);
    assert.deepEqual(state,{label,tooltip:label,pressed:String(expanded),width:30,height:30,text:'',enter:!expanded,exit:expanded},
      'The icon toggle keeps a fixed footprint and names the current action');
  };
  const focusTooltip = async (root, label) => {
    await evaluate(`document.activeElement?.blur();document.querySelector(${JSON.stringify(toggle(root))}).focus()`);
    await until(`[...document.querySelectorAll('[role=tooltip]')].some(t=>t.matches(':popover-open')&&t.textContent===${JSON.stringify(label)})`);
  };
  const reports=[];
  for (const width of [1440,1024,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
    await call('Page.navigate',{url:base+'/workspaces'});
    await until(`document.readyState==='complete'&&!document.querySelector('${toggle(primary)}')?.hidden&&Boolean(document.querySelector('${primary} .cm-editor'))`);
    await evaluate('document.fonts.ready');
    await frame();
    const normal = await evaluate(`(() => {
      const main=document.querySelector('main'),a=document.querySelector('${primary}'),b=document.querySelector('${secondary}');
      return {a:a.getBoundingClientRect().toJSON(),b:b.getBoundingClientRect().toJSON(),bottom:main.getBoundingClientRect().bottom-parseFloat(getComputedStyle(main).paddingBottom),
        outer:main.scrollHeight-main.clientHeight,overflow:document.documentElement.scrollWidth>innerWidth,
        editor:a.querySelector('.cm-editor').getBoundingClientRect().height,
        source:a.querySelector('[data-bml-source-view]').getBoundingClientRect().height,
        colors:[a,b].map(e=>getComputedStyle(e).backgroundColor)};
    })()`);
    assert.ok(Math.abs(Math.max(normal.a.bottom,normal.b.bottom)-normal.bottom)<=1&&normal.outer<=1&&!normal.overflow,JSON.stringify(normal));
    assert.ok(Math.abs(normal.editor-normal.source)<=1,'fill removes the source viewer height cap');
    assert.notEqual(normal.colors[0],normal.colors[1],'Neighbors inherit independent consumer palettes');
    await iconControl(primary,false);
    await evaluate(`void(window.workspaceSource=document.querySelector('${primary} .cm-editor'))`);
    await evaluate(`document.querySelector('[data-workspace-draft]').value='Retained draft';document.querySelector('${primary} .cm-scroller').scrollTop=150`);
    await frame();
    await focusTooltip(primary,'Full screen');
    const key=width===1024?' ':'Enter';
    for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key,code:key===' '?'Space':'Enter',windowsVirtualKeyCode:key===' '?32:13,
      ...(type==='keyDown'?{text:key===' '?' ':'\r'}:{})});
    await expanded(primary,true);
    await iconControl(primary,true);
    const full=await evaluate(`(() => {const e=document.querySelector('${primary}');return {rect:e.getBoundingClientRect().toJSON(),width:innerWidth,height:innerHeight,native:document.fullscreenElement===e};})()`);
    assert.ok(full.rect.x===0&&full.rect.y===0&&full.rect.width===full.width&&full.rect.height===full.height,JSON.stringify(full));
    assert.ok(await evaluate(`document.querySelector('[data-workspace-background]').inert&&document.querySelector('${primary} .cm-editor')===window.workspaceSource`));
    assert.equal(await evaluate(`document.querySelector('${primary} .cm-scroller').scrollTop`),150);
    const updateText=await evaluate(`document.querySelector('[data-workspace-update]').textContent`);
    await click('[data-workspace-update]');
    await until(`document.querySelector('[data-workspace-update]').textContent!==${JSON.stringify(updateText)}`);
    assert.ok(await evaluate(`document.querySelector('${primary}').hasAttribute('data-bml-workspace-expanded')&&document.querySelector('${primary} .cm-editor')===window.workspaceSource&&document.querySelector('[data-workspace-draft]').value==='Retained draft'`));
    await click('[data-workspace-reject]');
    await until(`document.querySelector('[data-workspace-reject]').textContent.includes('Rejection ready')`);
    const saveText=await evaluate(`document.querySelector('[data-workspace-save]').textContent`);
    await click('[data-workspace-save]');
    await until(`document.querySelector('[data-workspace-save]').textContent!==${JSON.stringify(saveText)}&&document.querySelector('${primary} [role=alert]')?.textContent.includes('Draft rejected')`);
    assert.ok(await evaluate(`document.querySelector('[data-workspace-draft]').value==='Retained draft'&&!document.querySelector('${toggle(primary)}').hidden`));
    await iconControl(primary,true);
    await click(`${primary} [data-bml-ref=trigger]`);
    await until(`Boolean(document.querySelector('${primary} dialog[open]'))`);
    await escape();
    assert.ok(await evaluate(`!document.querySelector('${primary} dialog').open&&document.querySelector('${primary}').hasAttribute('data-bml-workspace-expanded')`),'Escape dismisses the projected dialog first');
    await focusTooltip(primary,'Exit full screen');
    await escape();
    await expanded(primary,false);
    await iconControl(primary,false);
    const restored=await evaluate(`({background:document.querySelector('[data-workspace-background]').inert,original:document.querySelector('[data-originally-inert]').inert,
      focus:document.activeElement===document.querySelector('${toggle(primary)}'),active:document.activeElement?.outerHTML.slice(0,500),width:innerWidth,
      button:document.querySelector('${toggle(primary)}').outerHTML,visible:document.querySelector('${toggle(primary)}').checkVisibility(),
      ancestors:[...function*(e){while(e){if(e.inert)yield e.tagName+'.'+e.className;e=e.parentElement}}(document.querySelector('${toggle(primary)}'))]})`);
    assert.ok(!restored.background&&restored.original&&restored.focus,JSON.stringify(restored));
    await evaluate(`document.querySelector('${secondary}').requestFullscreen=async()=>{throw new DOMException('Denied','NotAllowedError')}`);
    await click(toggle(secondary));
    await expanded(secondary,true);
    await iconControl(secondary,true);
    assert.ok(await evaluate(`!document.fullscreenElement&&getComputedStyle(document.querySelector('${secondary}')).backgroundColor==='rgb(255, 255, 255)'`));
    await evaluate(`document.querySelector('${secondary}').style.setProperty('--workspace-fullscreen-bg','#243746')`);
    assert.equal(await evaluate(`getComputedStyle(document.querySelector('${secondary}')).backgroundColor`),'rgb(36, 55, 70)');
    await writeFile(join(artifacts,`${mode}-workspace-${width}.png`),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
    await click(toggle(secondary));
    await expanded(secondary,false);
    await iconControl(secondary,false);
    reports.push({width,normal,full});
  }
  assert.ok(reports.some(report=>report.full.native),'A trusted click exercises native full screen');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
  await evaluate(`document.querySelector('${primary}').requestFullscreen=undefined;document.querySelector('[data-nested-workspace]').requestFullscreen=undefined`);
  await click(toggle(primary));
  await expanded(primary,true);
  await evaluate(`document.querySelector('[data-nested-disclosure]').open=true`);
  await click(toggle('[data-nested-workspace]'));
  await expanded('[data-nested-workspace]',true);
  await iconControl('[data-nested-workspace]',true,'Return to draft');
  await focusTooltip('[data-nested-workspace]','Return to draft');
  await escape();
  await expanded('[data-nested-workspace]',false);
  await iconControl('[data-nested-workspace]',false,'Expand nested');
  assert.ok(await evaluate(`document.querySelector('${primary}').hasAttribute('data-bml-workspace-expanded')&&document.querySelector('[data-workspace-background]').inert`));
  await click(toggle('[data-nested-workspace]'));
  await expanded('[data-nested-workspace]',true);
  await evaluate(`document.querySelector('${primary}').remove()`);
  await frame();
  assert.ok(await evaluate(`!document.querySelector('[data-workspace-background]').inert&&document.querySelector('[data-originally-inert]').inert`),'Nested unmount releases background locks in either disposal order');
  await click(toggle(secondary));
  await expanded(secondary,true);
  await escape();
  await expanded(secondary,false);
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await call('Page.navigate',{url:base+'/workspaces'});
  await until(`document.readyState==='complete'&&Boolean(document.querySelector('${primary} pre'))`);
  assert.ok(await evaluate(`!document.querySelector('.cm-editor')&&[...document.querySelectorAll('[data-bml-fullscreen-button]')].every(e=>e.hidden&&!e.checkVisibility())&&document.querySelector('${primary} pre').textContent.includes('row120')`),'Without scripts, source remains readable and full-screen controls remain hidden');
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`${mode}-workspaces.json`),JSON.stringify(reports,null,2));
}
