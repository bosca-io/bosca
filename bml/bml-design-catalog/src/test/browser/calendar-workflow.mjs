import assert from 'node:assert/strict';

/** Full application flow over the library controls, using isolated catalog state. */
export async function checkCalendarWorkflow({navigate,evaluate,until,call}) {
  for (const width of [1440,390]) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await navigate('/calendars');
    const example=`document.querySelector('[data-catalog-calendar=dark]')`;
    const root=`${example}.querySelector('[data-bml-calendar]')`;
    const dialog=`${example}.querySelector('[data-calendar-create] dialog')`;
    const form=`${dialog}.querySelector('form')`;
    await until(`${root}?.querySelector('[data-event=planning]')`);
    await evaluate(`${root}.querySelector('[data-event=planning]').click()`);
    await until(`${dialog}.open`);
    const footer=await evaluate(`(()=>{const node=${dialog}.querySelector('[data-calendar-event-actions]'),del=node.querySelector('[data-calendar-delete] [data-bml-ref=trigger]'),save=node.querySelector(':scope > .example-actions button[type=submit]');const a=del.getBoundingClientRect(),b=save.getBoundingClientRect(),r=node.getBoundingClientRect();return {sameRow:Math.abs(a.y+a.height/2-b.y-b.height/2)<1,contained:a.top>=r.top&&a.bottom<=r.bottom,saveForm:save.form===${form}}})()`);
    assert.equal(footer.sameRow,true);assert.equal(footer.contained,true);assert.equal(footer.saveForm,true);
    await evaluate(`${form}.elements.title.value='Edited planning';${form}.elements.description.value='Bring notes';${form}.elements.location.value='Room A';for(const option of ${form}.elements.participantIds.options) option.selected=option.value==='alex';${form}.elements.participantIds.dispatchEvent(new Event('change',{bubbles:true}));${form}.requestSubmit()`);
    await until(`!${dialog}.open&&JSON.parse(${root}.dataset.events).find(e=>e.id==='planning').title==='Edited planning'`);
    const saved=await evaluate(`JSON.parse(${root}.dataset.events).find(e=>e.id==='planning')`);
    assert.equal(saved.description,'Bring notes'); assert.equal(saved.location,'Room A'); assert.equal(saved.participants[0].profileId,'alex');
    await evaluate(`${root}.querySelector('[data-event=planning]').click()`); await until(`${dialog}.open`);
    const deletion=`${example}.querySelector('[data-calendar-delete]')`;
    await evaluate(`${deletion}.querySelector('[data-bml-ref=trigger]').click()`);
    await until(`${deletion}.querySelector('dialog').open`);
    await evaluate(`${deletion}.querySelector('[data-dialog-close]').click()`);
    assert.ok(await evaluate(`JSON.parse(${root}.dataset.events).some(e=>e.id==='planning')`));
    await evaluate(`${deletion}.querySelector('[data-bml-ref=trigger]').click()`);
    await evaluate(`${deletion}.querySelector('form').requestSubmit()`);
    await until(`!${dialog}.open&&!JSON.parse(${root}.dataset.events).some(e=>e.id==='planning')`);

    await evaluate(`${example}.querySelector('[data-calendar-toggle=personal]').click()`);
    await until(`!JSON.parse(${root}.dataset.events).some(e=>e.calendarId==='personal')`);
    await evaluate(`${example}.querySelector('[data-calendar-toggle=personal]').click()`);
    await until(`JSON.parse(${root}.dataset.events).some(e=>e.calendarId==='personal')`);
    await evaluate(`${example}.querySelector('.calendar-sidebar [data-calendar-add]').click()`);
    const calDialog=`${example}.querySelector('[data-calendar-edit-dialog] dialog')`;
    await until(`${calDialog}.open`);
    await evaluate(`${calDialog}.querySelector('form').elements.name.value='Team';${calDialog}.querySelector('form').requestSubmit()`);
    await until(`!${calDialog}.open&&${example}.querySelector('.calendar-sidebar').textContent.includes('Team')`);
    await evaluate(`[...${example}.querySelectorAll('.calendar-sidebar [data-calendar-edit]')].at(-1).click()`);
    await until(`${calDialog}.open`);
    await evaluate(`${calDialog}.querySelector('form').elements.name.value='Team renamed';${calDialog}.querySelector('form').elements.color.value='#c03060';${calDialog}.querySelector('form').requestSubmit()`);
    await until(`!${calDialog}.open&&${example}.querySelector('.calendar-sidebar').textContent.includes('Team renamed')`);

    await evaluate(`${example}.querySelector('[data-calendar-add]').click()`); await until(`${dialog}.open`);
    await evaluate(`${form}.querySelector('[data-bml-ref=frequency]').value='WEEKLY';${form}.querySelector('[data-bml-ref=frequency]').dispatchEvent(new Event('change',{bubbles:true}));${form}.querySelector('[data-bml-ref=interval]').value='2';${form}.querySelector('[data-bml-ref=interval]').dispatchEvent(new Event('change',{bubbles:true}));${form}.querySelector('[data-bml-ref=count]').value='3';${form}.querySelector('[data-bml-ref=count]').dispatchEvent(new Event('change',{bubbles:true}))`);
    assert.equal(await evaluate(`${form}.elements.rrule.value`),'FREQ=WEEKLY;INTERVAL=2;COUNT=3');
    await evaluate(`${form}.querySelector('[data-weekday=MO]').click()`);
    assert.equal(await evaluate(`${form}.elements.rrule.value`),'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=3');
    await evaluate(`${form}.querySelector('[data-bml-ref=frequency]').value='DAILY';${form}.querySelector('[data-bml-ref=frequency]').dispatchEvent(new Event('change',{bubbles:true}));${form}.querySelector('[data-bml-ref=interval]').value='1';${form}.querySelector('[data-bml-ref=interval]').dispatchEvent(new Event('change',{bubbles:true}));${form}.elements.title.value='All day series';${form}.elements.kind.value='all-day';${form}.elements.kind.dispatchEvent(new Event('change',{bubbles:true}));${form}.elements.startLocal.value='2026-11-05';${form}.elements.endLocal.value='2026-11-06';${form}.elements.endLocal.dispatchEvent(new Event('input',{bubbles:true}));${form}.requestSubmit()`);
    await until(`!${dialog}.open&&JSON.parse(${root}.dataset.events).filter(e=>e.title==='All day series').length===3`);
    assert.ok(await evaluate(`JSON.parse(${root}.dataset.events).filter(e=>e.title==='All day series').every(e=>e.allDay)`));
    await evaluate(`[...${root}.querySelectorAll('[data-event]')].find(node=>node.textContent.includes('All day series')).click()`); await until(`${dialog}.open`);
    assert.ok(await evaluate(`${form}.querySelector('[data-bml-ref=recurrence]').hidden`));
    assert.equal(await evaluate(`${form}.elements.rrule.type`),'hidden');
    await evaluate(`${form}.elements.title.value='One occurrence';${form}.requestSubmit()`);
    await until(`!${dialog}.open&&JSON.parse(${root}.dataset.events).filter(e=>e.title==='One occurrence').length===1`);
    assert.equal(await evaluate(`JSON.parse(${root}.dataset.events).filter(e=>e.title==='All day series').length`),2);

    await evaluate(`${root}.querySelector('[data-select-day="2026-11-05"]').click()`);
    await until(`${root}.dataset.view==='day'`);
    assert.ok(await evaluate(`${root}.querySelector('[data-event=reminder]')!==null`),await evaluate(`${root}.dataset.events`));
    await evaluate(`${example}.querySelector('[data-calendar-reject]').click()`);
    await until(`${example}.querySelector('[data-calendar-reject]').checked`);
    await evaluate(`${root}.querySelector('[data-event=reminder]').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',altKey:true,bubbles:true}))`);
    await until(`${root}.querySelector('[role=alert]')`);
    assert.equal(await evaluate(`${root}.hasAttribute('data-drag-pending')`),false);
    assert.equal(await evaluate(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='reminder').startsAt).getMinutes()`),0);
    await evaluate(`${example}.querySelector('[data-calendar-reject]').click()`);
    await until(`!${example}.querySelector('[data-calendar-reject]').checked`);
    await evaluate(`${root}.querySelector('[data-event=reminder]').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',altKey:true,bubbles:true}))`);
    await until(`new Date(JSON.parse(${root}.dataset.events).find(e=>e.id==='reminder').startsAt).getMinutes()===15`);
    await evaluate(`[...${example}.querySelectorAll('.calendar-sidebar [data-calendar-edit]')].at(-1).click()`); await until(`${calDialog}.open`);
    const removeCalendar=`${example}.querySelector('[data-calendar-delete-calendar]')`;
    await evaluate(`${removeCalendar}.querySelector('[data-bml-ref=trigger]').click()`); await until(`${removeCalendar}.querySelector('dialog').open`);
    await evaluate(`${removeCalendar}.querySelector('form').requestSubmit()`);
    await until(`!${calDialog}.open&&!${example}.querySelector('.calendar-sidebar').textContent.includes('Team renamed')`);
  }
}
