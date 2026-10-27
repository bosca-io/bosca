import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Exercises projected widgets and native form ownership, not a separate grid-only fixture. */
export async function checkDashboards({ navigate, evaluate, until: poll, call, key, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`), reports = [];
  for (const width of [1440, 390]) for (const id of ['dark', 'light']) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await navigate('/dashboards');
    await until(`document.querySelectorAll('[data-grid-ready]').length===2&&document.querySelectorAll('[data-chart-ready]').length===2&&[...document.querySelectorAll('[data-bml-method]')].every(node=>node.dataset.bmlActionBound==='true')`);
    const example = `document.querySelector('[data-catalog-dashboard=${id}]')`;
    const root = `${example}.querySelector('[data-bml-dashboard-grid]')`, field = `${root}.querySelector('textarea')`;
    const form = `document.querySelector('[data-dashboard-form=${id}]')`;
    const widget = name => `${root}.querySelector('[data-dashboard-item=${name}]')`;
    const handle = (name, kind) => `${widget(name)}.querySelector('[data-dashboard-handle=${kind}]')`;
    const state = name => `${example}.querySelector('[data-dashboard-${name}]')`;
    const layout = `${field}.value`, item = name => `JSON.parse(${layout}).find(item=>item.id==='${name}')`;
    const original = await evaluate(layout);
    const sibling = `document.querySelector('[data-catalog-dashboard=${id==='dark'?'light':'dark'}] textarea')`;
    const siblingValue = await evaluate(`${sibling}.value`);
    await evaluate(`window.gridRoot=${root};window.widget=${widget('notes')};window.widgetCanvas=${root}.querySelector('canvas');window.note=${root}.querySelector('input[name=note]');window.note.value='Unsent note';window.gridEvents=[];${root}.addEventListener('layoutchange',event=>window.gridEvents.push(event.detail))`);
    assert.equal(await evaluate(`getComputedStyle(${root}.querySelector('.dashboard-cells')).gap`), '16px');
    assert.ok(await evaluate(`${handle('summary','move')}.closest('.dashboard-handles').hidden`));
    if (width === 390) {
      assert.ok(await evaluate(`${root}.hasAttribute('data-stacked')&&[...${root}.querySelectorAll('.dashboard-handles')].every(el=>el.hidden)`));
      const boxes = await evaluate(`Array.from(${root}.querySelectorAll('[data-dashboard-item]'),el=>{const r=el.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,bottom:r.bottom}})`);
      assert.ok(boxes.every(box=>Math.abs(box.width-boxes[0].width)<1));
      assert.ok(boxes.slice(1).every((box,i)=>box.y-boxes[i].bottom>=15.5));
      assert.equal(await evaluate(layout), original);
    } else {
      assert.ok(await evaluate(`!${root}.hasAttribute('data-stacked')`));
      // Native keyboard resizing displaces collisions while keeping widget DOM and input focus.
      await evaluate(`${handle('visits','resize')}.focus()`); await key('ArrowRight');
      await until(`${item('visits')}.w===9`);
      assert.equal(await evaluate(`${item('notes')}.y`), 8, 'Displaced notes must clear the locked summary');
      assert.equal(await evaluate(`${item('summary')}.y`), 6);
      assert.equal(await evaluate(`${item('visits')}.h`), 6);
      assert.ok(await evaluate(`document.activeElement===${handle('visits','resize')}`));
      assert.ok(await evaluate(`window.widget===${widget('notes')}&&window.widgetCanvas===${root}.querySelector('canvas')&&window.note.value==='Unsent note'`));
      await evaluate(`${form}.reset()`); await until(`${layout}===${JSON.stringify(original)}`);
      // Moving toward the locked summary is rejected without silently overlapping it.
      await evaluate(`${handle('visits','move')}.focus()`); await key('ArrowDown');
      assert.equal(await evaluate(layout), original);
      // Unlocking the summary retains the draft and enables collision displacement.
      await evaluate(`${example}.querySelector('[data-example-states]').open=true;${state('locked')}.click()`);
      await until(`!${handle('summary','move')}.closest('.dashboard-handles').hidden`);
      await evaluate(`${handle('visits','move')}.focus()`); await key('ArrowDown');
      await until(`${item('visits')}.y===1&&${item('summary')}.y===7`);
      // Real pointer gestures use the final release coordinates and cancellation restores geometry.
      await evaluate(`${form}.reset()`); await until(`${layout}===${JSON.stringify(original)}`);
      const point = async kind => {
        await evaluate(`${handle('visits',kind)}.scrollIntoView({block:'center'})`);
        return evaluate(`(()=>{const r=${handle('visits',kind)}.getBoundingClientRect(),cells=${root}.querySelector('.dashboard-cells');return {x:r.x+r.width/2,y:r.y+r.height/2,step:(cells.clientWidth+16)/12,row:76}})()`);
      };
      const drag = async (kind, dx, dy, ending='up') => {
        const p = await point(kind);
        await call('Input.dispatchMouseEvent',{type:'mousePressed',x:p.x,y:p.y,button:'left',clickCount:1});
        await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:p.x+p.step*dx,y:p.y+p.row*dy,button:'left',buttons:1});
        if (ending==='escape') await key('Escape');
        if (ending==='cancel') await evaluate(`${root}.dispatchEvent(new PointerEvent('pointercancel',{bubbles:true,pointerId:1}))`);
        await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:p.x+p.step*dx,y:p.y+p.row*dy,button:'left',clickCount:1});
      };
      await drag('move',0,1,'escape'); assert.equal(await evaluate(layout), original);
      await drag('move',0,1,'cancel'); assert.equal(await evaluate(layout), original);
      await drag('move',0,1); await until(`${item('visits')}.y===1`);
      await drag('resize',-1,1); await until(`${item('visits')}.w===7&&${item('visits')}.h===7`);
      assert.ok(await evaluate(`window.gridEvents.some(event=>event.kind==='move')&&window.gridEvents.some(event=>event.kind==='resize')&&window.gridEvents.every(event=>event.itemId==='visits')`));
      assert.ok(await evaluate(`window.widgetCanvas===${root}.querySelector('canvas')&&window.note===${root}.querySelector('input[name=note]')`));
      await evaluate(`${root}.style.setProperty('--dashboard-gap','24px')`);
      assert.equal(await evaluate(`getComputedStyle(${root}.querySelector('.dashboard-cells')).gap`), '24px');
      await evaluate(`${root}.style.removeProperty('--dashboard-gap')`);
    }
    await evaluate(`${example}.querySelector('[data-example-states]').open=true;window.beforeRefresh=${layout};window.note.value='Unsent note';window.note.focus();window.note.setSelectionRange(2,5);${state('refresh')}.click()`);
    await until(`document.querySelector('[data-dashboard-toast=${id}]')?.matches(':popover-open')`);
    assert.deepEqual(await evaluate(`[${root}===window.gridRoot,${layout}===window.beforeRefresh,${widget('notes')}===window.widget,${root}.querySelector('canvas')===window.widgetCanvas,window.note.value==='Unsent note',document.activeElement===window.note,window.note.selectionStart===2]`), Array(7).fill(true));
    await evaluate(`${state('reject')}.click()`); await until(`${state('reject')}.hasAttribute('checked')`);
    await evaluate(`window.rejectedLayout=${layout};(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseLayoutSave=resolve);}return original(...args);};${form}.requestSubmit();})()`);
    await until(`${form}.hasAttribute('data-bml-action-pending')&&typeof window.releaseLayoutSave==='function'`);
    assert.ok(await evaluate(`${field}.readOnly&&[...${root}.querySelectorAll('[data-dashboard-handle]')].every(button=>button.disabled)`));
    await evaluate('window.releaseLayoutSave()');
    await until(`${root}.querySelector('[data-bml-ref=feedback]').textContent.includes('rejected')`);
    assert.equal(await evaluate(layout), await evaluate('window.rejectedLayout'));
    await evaluate(`${state('readonly')}.click()`); await until(`${root}.hasAttribute('data-readonly')`);
    assert.ok(await evaluate(`${field}.readOnly&&!${field}.disabled&&new FormData(${form}).has('layout')&&[...${root}.querySelectorAll('.dashboard-handles')].every(el=>el.hidden)`));
    await evaluate(`${state('disabled')}.click()`); await until(`${field}.disabled`);
    assert.ok(await evaluate(`!new FormData(${form}).has('layout')`));
    await evaluate(`${state('disabled')}.click()`); await until(`!${field}.disabled`);
    await evaluate(`${state('readonly')}.click()`); await until(`!${root}.hasAttribute('data-readonly')`);
    await evaluate(`${form}.reset()`); await until(`${layout}===${JSON.stringify(original)}`);
    await evaluate(`${state('reject')}.click()`); await until(`!${state('reject')}.hasAttribute('checked')`);
    await evaluate(`${form}.requestSubmit()`); await until(`document.querySelector('[data-dashboard-toast=${id}] [role=status]')?.textContent==='Layout saved.'`);
    await evaluate(`${state('replace')}.click()`); await until(`${item('visits')}.w===6&&${item('visits')}.h===7`);
    assert.ok(await evaluate(`${root}===window.gridRoot&&${root}.querySelector('canvas')===window.widgetCanvas`));
    // Malformed or mismatched controlled input cannot submit an older layout.
    await evaluate(`window.gridValue=${root}.dataset.value;${root}.dataset.value='[{"id":"missing","x":0,"y":0,"w":1,"h":1}]';document.dispatchEvent(new CustomEvent('bml:update',{detail:{target:${root},added:[],retained:[]}}))`);
    assert.ok(await evaluate(`!${field}.hidden&&!${field}.checkValidity()&&${layout}.includes('missing')&&!${root}.hasAttribute('data-grid-ready')`));
    await evaluate(`${root}.dataset.value=window.gridValue;document.dispatchEvent(new CustomEvent('bml:update',{detail:{target:${root},added:[],retained:[]}}))`);
    await until(`${root}.hasAttribute('data-grid-ready')&&${field}.hidden`);
    for (let cycle=0;cycle<2;cycle++) {
      await evaluate(`${state('visible')}.click()`); await until(`!${example}.querySelector('[data-grid-ready]')`);
      await evaluate(`${state('visible')}.click()`); await until(`${example}.querySelector('[data-grid-ready] canvas')`);
    }
    assert.equal(await evaluate(`${sibling}.value`), siblingValue);
    assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&[...${root}.querySelectorAll('[data-dashboard-item]')].every(el=>el.getBoundingClientRect().right<=${root}.getBoundingClientRect().right+1)`));
    const screenshot = await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-dashboards-${width}-${id}.png`),Buffer.from(screenshot.data,'base64'));
    reports.push({width,palette:id,stacked:width===390,nativeForm:true,retainedContent:true,replacement:true,disposal:true,pointer:width===1440,keyboard:width===1440});
  }
  await call('Network.clearBrowserCookies');
  await call('Emulation.setScriptExecutionDisabled',{value:true}); await navigate('/dashboards');
  assert.ok(await evaluate(`[...document.querySelectorAll('[data-bml-dashboard-grid]')].every(root=>!root.querySelector('textarea').hidden&&JSON.parse(root.querySelector('textarea').value).length===3&&root.querySelectorAll('[data-dashboard-item]').length===3&&getComputedStyle(root.querySelector('.dashboard-cells')).display==='flex')`));
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-dashboards.json`),JSON.stringify(reports,null,2));
}
