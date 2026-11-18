import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export async function checkCalendars({navigate,evaluate,until,call,key,artifacts,mode}) {
  for (const width of [1440,390]) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await navigate('/calendars');
    await until(`document.querySelectorAll('.calendar-cell').length===84`);
    const example = `document.querySelector('[data-catalog-calendar=dark]')`;
    const root = `${example}.querySelector('[data-bml-calendar]')`;
    const choose = async view => {
      await evaluate(`[...${example}.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(view)}).click()`);
      await until(`${root}.dataset.view===${JSON.stringify(view)}&&${root}.querySelectorAll('.calendar-column').length===${view==='week'?7:1}`);
    };
    if (artifacts) { const shot=await call('Page.captureScreenshot',{format:'png'}); await writeFile(join(artifacts,`calendar-${mode}-${width}-month.png`),Buffer.from(shot.data,'base64')); }
    assert.equal(await evaluate(`getComputedStyle(${root}.querySelector('.calendar-grid')).display`),'grid');
    const heights=await evaluate(`Array.from(${root}.querySelectorAll('.calendar-cell'),cell=>cell.getBoundingClientRect().height)`);
    assert.equal(heights.length,42); assert.ok(heights.every(height=>Math.abs(height-heights[0])<1));
    assert.ok(await evaluate(`Array.from(${root}.querySelectorAll('.calendar-more')).every(more=>more.getBoundingClientRect().bottom<=more.parentElement.getBoundingClientRect().bottom)`));
    await choose('week');
    assert.equal(await evaluate(`${root}.querySelectorAll('[data-hour]').length`),168);
    await evaluate(`window.retainedCalendar=${root};window.retainedGrid=${root}.querySelector('.calendar-grid');${root}.querySelector('.calendar-body').scrollTop=450;${root}.querySelector('.calendar-body').scrollLeft=${width===390?100:0};window.savedCalendarScroll=[${root}.querySelector('.calendar-body').scrollTop,${root}.querySelector('.calendar-body').scrollLeft,window.scrollY];${root}.querySelector('[data-event=planning]').click()`);
    await until(`${example}.textContent.includes('Selected Planning')`);
    assert.ok(await evaluate(`${root}===window.retainedCalendar&&${root}.querySelector('.calendar-grid')===window.retainedGrid`));
    assert.deepEqual(await evaluate(`[${root}.querySelector('.calendar-body').scrollTop,${root}.querySelector('.calendar-body').scrollLeft,window.scrollY]`), await evaluate('window.savedCalendarScroll'));
    assert.equal(await evaluate(`${root}.dataset.view`),'week');
    await evaluate(`${example}.querySelector('[data-calendar-create] dialog .calendar-event-footer > .example-actions [data-dialog-close]').click()`);
    await until(`!${example}.hasAttribute('data-calendar-editor-open')`);
    if (artifacts) { const shot=await call('Page.captureScreenshot',{format:'png'}); await writeFile(join(artifacts,`calendar-${mode}-${width}-week.png`),Buffer.from(shot.data,'base64')); }
    if (width === 1440) {
      await evaluate(`${root}.querySelector('[data-event=planning]').scrollIntoView({block:'center'})`);
      const points=await evaluate(`(()=>{const event=${root}.querySelector('[data-event=planning]').getBoundingClientRect(),target=${root}.querySelector('[data-day="2026-11-06"]').getBoundingClientRect();return {x:event.x+event.width/2,y:event.y+10,targetX:target.x+target.width/2}})()`);
      await call('Input.dispatchMouseEvent',{type:'mousePressed',x:points.x,y:points.y,button:'left',clickCount:1});
      await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:points.targetX,y:points.y+24,button:'left',buttons:1});
      assert.equal(await evaluate(`${root}.querySelector('[data-event=planning]').parentElement.dataset.day`),'2026-11-06');
      assert.equal(await evaluate(`${root}.querySelector('[data-event=planning]').style.top`),'456px');
      assert.equal(await evaluate(`${root}.querySelectorAll('.calendar-ghost').length`),0);
      if (artifacts) { const shot=await call('Page.captureScreenshot',{format:'png'}); await writeFile(join(artifacts,`calendar-${mode}-drag-in-place.png`),Buffer.from(shot.data,'base64')); }
      await key('Escape');
      await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:points.targetX,y:points.y+24,button:'left',clickCount:1});
      assert.equal(await evaluate(`${root}.querySelector('[data-event=planning]').parentElement.dataset.day`),'2026-11-05');
    }
    await choose('day');
    if (width === 1440) {
      await evaluate(`${root}.querySelector('[data-event=planning]').scrollIntoView({block:'center'})`);
      const point=await evaluate(`(()=>{const r=${root}.querySelector('[data-event=planning]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.bottom-3}})()`);
      await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
      await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:point.x,y:point.y+24,button:'left',buttons:1});
      assert.equal(await evaluate(`${root}.querySelector('[data-event=planning]').style.height`),'72px');
      assert.equal(await evaluate(`${root}.querySelector('[data-event=planning]').style.top`),'432px');
      await evaluate(`window.scrollBeforeResize=${root}.querySelector('.calendar-body').scrollTop`);
      await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y+24,button:'left',clickCount:1});
      await until(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='planning').endsAt).getMinutes()===30`);
      assert.equal(await evaluate(`${root}.querySelector('.calendar-body').scrollTop`),await evaluate('window.scrollBeforeResize'));
      assert.ok(await evaluate(`${root}===window.retainedCalendar`));
      assert.equal(await evaluate(`${root}.dataset.view`),'day');
    }
    if (width === 1440) {
      const reminder = `${root}.querySelector('[data-event=reminder]')`;
      await evaluate(`${reminder}.scrollIntoView({block:'center'})`);
      const point=await evaluate(`(()=>{const r=${reminder}.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+5}})()`);
      await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
      await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:point.x,y:point.y+24,button:'left',buttons:1});
      assert.equal(await evaluate(`${reminder}.style.top`),'600px');
      assert.ok(await evaluate(`${reminder}.textContent.includes('12:30 – 13:00 Reminder')`));
      await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y+24,button:'left',clickCount:1});
      await until(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='reminder').startsAt).getMinutes()===30`);
      assert.ok(await evaluate(`${reminder}.textContent.includes('12:30 Reminder')`));
    }
    await evaluate(`${root}.querySelector('[data-event=planning]').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',altKey:true,bubbles:true}))`);
    await until(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='planning').startsAt).getMinutes()===15`);
    assert.equal(await evaluate(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='planning').startsAt).getMinutes()`),15);
    assert.equal(await evaluate(`document.querySelector('[data-catalog-calendar=light] [data-bml-calendar]').dataset.view`),'month');
    await evaluate(`window.scrollBeforeCreate=${root}.querySelector('.calendar-body').scrollTop;${root}.querySelector('[data-hour="9"]').click()`);
    const dialog = `${example}.querySelector('[data-calendar-create] dialog')`;
    const form = `${dialog}.querySelector('form')`;
    await until(`${dialog}.open`);
    assert.equal(await evaluate(`${form}.elements.startLocal.value`),'2026-11-05T09:00');
    const count = await evaluate(`JSON.parse(${root}.dataset.events).length`);
    await evaluate(`${form}.elements.title.value='Quick meeting';${form}.elements.endLocal.value='2026-11-05T08:00';${form}.elements.endLocal.dispatchEvent(new Event('input',{bubbles:true}));${form}.requestSubmit()`);
    assert.equal(await evaluate(`${form}.checkValidity()`),false);
    assert.equal(await evaluate(`${form}.elements.title.value`),'Quick meeting');
    assert.equal(await evaluate(`JSON.parse(${root}.dataset.events).length`),count);
    await evaluate(`${form}.elements.endLocal.value='2026-11-05T10:00';${form}.elements.endLocal.dispatchEvent(new Event('input',{bubbles:true}));${form}.requestSubmit()`);
    await until(`!${dialog}.open&&JSON.parse(${root}.dataset.events).some(e=>e.title==='Quick meeting')`);
    assert.equal(await evaluate(`${root}.querySelectorAll('.calendar-timed[data-editable]').length`),4);
    assert.equal(await evaluate(`${root}.querySelector('.calendar-body').scrollTop`),await evaluate('window.scrollBeforeCreate'));
    assert.equal(await evaluate(`${root}.dataset.view`),'day');
    assert.equal(await evaluate(`JSON.parse(document.querySelector('[data-catalog-calendar=light] [data-bml-calendar]').dataset.events).length`),4);
    await evaluate(`${example}.querySelector('[data-calendar-add]').click()`);
    await until(`${dialog}.open`);
    await evaluate(`${dialog}.querySelector('.calendar-event-footer > .example-actions [data-dialog-close]').click()`);
    await until(`!${dialog}.open&&!${example}.hasAttribute('data-calendar-editor-open')`);
    assert.equal(await evaluate(`JSON.parse(${root}.dataset.events).length`),count+1);
    await evaluate(`[...${example}.querySelectorAll('button')].find(b=>b.textContent.trim()==='month').click()`);
    await until(`${root}.dataset.view==='month'&&${root}.querySelector('[data-new-day]')`);
    await evaluate(`${root}.querySelector('[data-new-day="2026-11-06"]').click()`);
    await until(`${dialog}.open&&${form}.elements.startLocal.value==='2026-11-06T09:00'`);
    await evaluate(`${form}.elements.title.value='Month addition';${form}.requestSubmit()`);
    await until(`!${dialog}.open&&JSON.parse(${root}.dataset.events).some(e=>e.title==='Month addition')`);
    if (width === 1440) {
      await evaluate(`${root}.querySelector('[data-event=planning]').scrollIntoView({block:'center'})`);
      await evaluate(`window.monthBoxes=Array.from(${root}.querySelectorAll('.calendar-cell'),cell=>{const r=cell.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})`);
      const points=await evaluate(`(()=>{const source=${root}.querySelector('[data-event=planning]').getBoundingClientRect(),target=${root}.querySelector('[data-day="2026-11-08"]').getBoundingClientRect();return {x:source.x+source.width/2,y:source.y+source.height/2,targetX:target.x+target.width/2,targetY:target.y+target.height/2}})()`);
      await call('Input.dispatchMouseEvent',{type:'mousePressed',x:points.x,y:points.y,button:'left',clickCount:1});
      await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:points.targetX,y:points.targetY,button:'left',buttons:1});
      const during = await evaluate(`Array.from(${root}.querySelectorAll('.calendar-cell'),cell=>{const r=cell.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})`);
      assert.deepEqual(during,await evaluate('window.monthBoxes'));
      assert.ok(during.every(cell=>Math.abs(cell.height-during[0].height)<1));
      const preview=await evaluate(`(()=>{const event=${root}.querySelector('[data-event=planning]'),r=event.getBoundingClientRect(),cell=event.parentElement.getBoundingClientRect();return {visible:r.top>=cell.top&&r.bottom<=cell.bottom,height:r.height}})()`);
      assert.equal(preview.visible,true); assert.equal(preview.height,20);
      if (artifacts) { const shot=await call('Page.captureScreenshot',{format:'png'}); await writeFile(join(artifacts,`calendar-${mode}-month-drag.png`),Buffer.from(shot.data,'base64')); }
      await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:points.targetX,y:points.targetY,button:'left',clickCount:1});
      await until(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='planning').startsAt).getDate()===8`);
      assert.deepEqual(await evaluate(`Array.from(${root}.querySelectorAll('.calendar-cell'),cell=>{const r=cell.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})`),await evaluate('window.monthBoxes'));
      // A native compatibility click following the drop must not select a day or change mode.
      assert.equal(await evaluate(`${root}.dataset.view`),'month');
      assert.ok(await evaluate(`${root}===window.retainedCalendar`));
    }
  }
}
