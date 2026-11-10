import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom');
const { build } = require('esbuild');
const bundle = await build({ entryPoints: ['src/main/client/helpers/calendar.ts'], bundle: true, write: false, format: 'esm', loader: { '.css': 'empty' } });
const calendar = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const events = [
  { id: 'edit', title: '<Planning>', startsAt: '2026-11-05T09:00:00', endsAt: '2026-11-05T10:00:00', editable: true },
  { id: 'fixed', title: 'Reminder', startsAt: '2026-11-05T11:00:00', endsAt: '2026-11-05T12:00:00' },
  { id: 'all', title: 'Release', startsAt: '2026-11-04T00:00:00', endsAt: '2026-11-06T00:00:00', allDay: true },
];
async function fixture(view, check) {
  const window = new Window();
  for (const key of ['document','HTMLElement','Element','Event','CustomEvent','MouseEvent','KeyboardEvent','PointerEvent']) globalThis[key] = window[key];
  globalThis.CSS = { supports: () => true };
  const root = document.createElement('div'); root.dataset.current = '2026-11-05'; root.dataset.view = view; root.dataset.events = JSON.stringify(events);
  const host = document.createElement('div'), status = document.createElement('p'); root.append(host, status); document.body.append(root);
  const cleanup = []; let update;
  calendar.mountCalendar({ root, ref: name => name === 'calendar' ? host : status,
    on: (node, name, handler) => { node.addEventListener(name, handler); cleanup.push(() => node.removeEventListener(name, handler)); },
    onUpdate: fn => update = fn, onUnmount: fn => cleanup.push(fn),
  });
  try { await check({ root, host, status, update, dispose: () => cleanup.forEach(fn => fn()) }); }
  finally { cleanup.forEach(fn => fn()); window.happyDOM.cancelAsync(); }
}
test('Monday-first month grid crosses years and leap days', () => {
  const days = calendar.monthDays(new Date('2028-02-10T00:00:00'));
  assert.equal(days.length, 42); assert.equal(days[0].getDay(), 1);
  assert.ok(days.some(day => calendar.dateKey(day) === '2028-02-29'));
  assert.equal(calendar.dateKey(calendar.startOfWeek(new Date('2027-01-01T00:00:00'))), '2026-12-28');
  assert.equal(calendar.intersects(events[2], new Date('2026-11-06T00:00:00')), false);
});
test('month overflow selection, safe titles and update reconciliation', () => fixture('month', ({root,host,update}) => {
  assert.equal(host.querySelectorAll('.calendar-cell').length, 42);
  assert.equal(host.querySelector('[data-event="edit"]').textContent, '09:00 <Planning>');
  assert.equal(host.querySelector('planning'), null);
  let selected; root.addEventListener('calendarselectevent', e => selected = e.detail.event);
  host.querySelector('[data-event="edit"]').click(); assert.equal(selected.id, 'edit');
  let requested; root.addEventListener('calendarnewevent', e => requested=e.detail.day);
  host.querySelector('[data-new-day="2026-11-05"]').click(); assert.equal(requested,'2026-11-05');
  const cell = host.querySelector('[data-day="2026-11-06"]');
  cell.dispatchEvent(new MouseEvent('click',{bubbles:true,detail:1}));
  cell.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,detail:2}));
  assert.equal(requested,'2026-11-06');
  let day; root.addEventListener('calendarselectday', e => day = e.detail.day);
  host.querySelector('[data-day="2026-11-05"] .calendar-date').click(); assert.equal(day, '2026-11-05');
  root.dataset.events = JSON.stringify([...events, ...[1,2,3].map(id => ({...events[0],id:String(id)}))]); update();
  assert.equal(host.querySelector('[data-day="2026-11-05"] .calendar-more').textContent, '+3 more');
  root.dataset.events = '[]'; update(); assert.equal(host.querySelectorAll('[data-event]').length,0);
}));
for (const view of ['week','day']) test(`${view} slots, exclusive all-day end, keyboard edits and cleanup`, () => fixture(view, ({root,host,update,dispose}) => {
  assert.equal(host.querySelectorAll('.calendar-column').length, view === 'week' ? 7 : 1);
  assert.equal(host.querySelectorAll('.calendar-slot').length, view === 'week' ? 168 : 24);
  assert.equal(host.querySelectorAll('.calendar-allday [data-event="all"]').length, view === 'week' ? 2 : 1);
  let slot; root.addEventListener('calendarselectslot', e => slot=e.detail);
  host.querySelector('[data-day="2026-11-05"] [data-hour="9"]').click(); assert.deepEqual(slot,{day:'2026-11-05',hour:9});
  const moves=[]; root.addEventListener('calendarmoveevent', e => moves.push(e.detail));
  const key = () => new KeyboardEvent('keydown',{key:'ArrowDown',altKey:true,bubbles:true});
  host.querySelector('[data-event="edit"]').dispatchEvent(key());
  assert.equal(new Date(moves[0].startsAt).getHours(),9); assert.equal(new Date(moves[0].startsAt).getMinutes(),15);
  host.querySelector('[data-event="fixed"]').dispatchEvent(key()); assert.equal(moves.length,1);
  root.setAttribute('data-readonly',''); update(); host.querySelector('[data-event="edit"]').dispatchEvent(key()); assert.equal(moves.length,1);
  root.removeAttribute('data-readonly'); update(); dispose(); host.querySelector('[data-event="edit"]').dispatchEvent(key()); assert.equal(moves.length,1);
}));
test('pointer move previews in place, preserves duration and suppresses the drag click', () => fixture('day', ({root,host,update}) => {
  const column = host.querySelector('.calendar-column'); column.getBoundingClientRect = () => ({left:0,right:300,top:0,bottom:1200});
  let moved, selected=0; root.addEventListener('calendarmoveevent', e => moved=e.detail); root.addEventListener('calendarselectevent',()=>selected++);
  const node = host.querySelector('[data-event="edit"]');
  node.dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:100,clientY:450,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:100,clientY:474,bubbles:true}));
  assert.equal(node.parentElement,column);
  assert.equal(node.style.top,'456px');
  assert.equal(node.style.height,'48px');
  assert.ok(node.textContent.includes('09:30 – 10:30'));
  assert.equal(host.querySelector('.calendar-ghost'),null);
  document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
  assert.equal(node.style.top,'456px');
  assert.equal(new Date(moved.startsAt).getMinutes(),30);
  assert.equal(Date.parse(moved.endsAt)-Date.parse(moved.startsAt),3600000);
  host.querySelector('[data-event="edit"]').dispatchEvent(new MouseEvent('click',{bubbles:true,detail:1})); assert.equal(selected,0);
  root.dataset.events=JSON.stringify(events.map(event=>event.id==='edit'?{...event,startsAt:moved.startsAt,endsAt:moved.endsAt}:event)); update();
  assert.equal(root.hasAttribute('data-drag-pending'),false);
  assert.equal(host.querySelector('[data-event="edit"]').style.top,'456px');
  host.querySelector('[data-event="edit"]').dispatchEvent(new PointerEvent('pointerdown',{button:0,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
  host.querySelector('[data-event="edit"]').dispatchEvent(new MouseEvent('click',{bubbles:true,detail:1})); assert.equal(selected,1);
}));
test('resizing at midnight keeps the end on the next day and cancel emits no change', () => fixture('day', ({root,host,update}) => {
  root.dataset.events=JSON.stringify([{...events[0],startsAt:'2026-11-05T23:00:00',endsAt:'2026-11-06T00:00:00'}]); update();
  host.querySelector('.calendar-column').getBoundingClientRect=()=>({left:0,right:300,top:0,bottom:1200});
  const changes=[]; root.addEventListener('calendarmoveevent',e=>changes.push(e.detail));
  host.querySelector('[data-resize]').dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:100,clientY:1100,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:100,clientY:1088,bubbles:true}));
  assert.equal(host.querySelector('[data-event="edit"]').style.height,'36px');
  document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
  assert.equal(new Date(changes[0].endsAt).getHours(),23); assert.equal(new Date(changes[0].endsAt).getMinutes(),45);
  update(); // A rejected change restores the controlled occurrence.
  host.querySelector('[data-event="edit"]').dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:100,clientY:1100,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:100,clientY:1088,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointercancel',{bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
  assert.equal(changes.length,1); assert.equal(host.querySelector('.calendar-ghost'),null);
}));

test('week drag previews in the destination column and Escape restores the original geometry', () => fixture('week', ({root,host}) => {
  const source=host.querySelector('.calendar-column[data-day="2026-11-05"]'), target=host.querySelector('.calendar-column[data-day="2026-11-06"]');
  source.getBoundingClientRect=()=>({left:0,right:100,top:0,bottom:1200});
  target.getBoundingClientRect=()=>({left:100,right:200,top:0,bottom:1200});
  let changes=0; root.addEventListener('calendarmoveevent',()=>changes++);
  const node=source.querySelector('[data-event="edit"]');
  node.dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:50,clientY:450,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:150,clientY:474,bubbles:true}));
  assert.equal(node.parentElement,target); assert.equal(node.style.top,'456px');
  assert.ok(target.classList.contains('calendar-drop-target'));
  node.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
  assert.equal(source.querySelector('[data-event="edit"]').style.top,'432px');
  assert.equal(target.querySelector('[data-event="edit"]'),null);
  assert.equal(host.querySelector('.calendar-drop-target'),null);
  document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true})); assert.equal(changes,0);
}));
test('month drag previews inside the destination day and restores on cancellation', () => fixture('month', ({host}) => {
  const source=host.querySelector('[data-day="2026-11-05"]'),target=host.querySelector('[data-day="2026-11-06"]');
  source.getBoundingClientRect=()=>({left:0,right:100,top:0,bottom:100});
  target.getBoundingClientRect=()=>({left:100,right:200,top:0,bottom:100});
  const node=source.querySelector('[data-event="edit"]');
  node.dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:50,clientY:50,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:150,clientY:50,bubbles:true}));
  assert.equal(node.parentElement,target);
  assert.ok(node.textContent.includes('09:00 – 10:00'));
  document.dispatchEvent(new PointerEvent('pointercancel',{bubbles:true}));
  assert.ok(source.querySelector('[data-event="edit"]'));
  assert.equal(target.querySelector('[data-event="edit"]'),null);
}));

test('retained ancestor updates refresh data without resetting either scroll axis', () => fixture('week', ({root,host}) => {
  host.scrollTop=450; host.scrollLeft=120;
  const originalGrid=host.firstElementChild;
  document.dispatchEvent(new CustomEvent('bml:update',{detail:{target:document.body,added:[],retained:[root]}}));
  assert.equal(host.firstElementChild,originalGrid);
  assert.equal(host.scrollTop,450); assert.equal(host.scrollLeft,120);
  root.dataset.events=JSON.stringify(events.map(event=>event.id==='edit'?{...event,title:'Updated planning'}:event));
  document.dispatchEvent(new CustomEvent('bml:update',{detail:{target:document.body,added:[],retained:[root]}}));
  assert.ok(host.querySelector('[data-event="edit"]').textContent.includes('Updated planning'));
  assert.equal(host.scrollTop,450); assert.equal(host.scrollLeft,120);
}));

test('rejected async saves restore geometry and allow retry', () => fixture('day', async ({root,host,status,update}) => {
  host.querySelector('.calendar-column').getBoundingClientRect=()=>({left:0,right:300,top:0,bottom:1200});
  let reject=true;
  root.addEventListener('calendarmoveevent',event=> {
    if(reject) event.detail.waitUntil(Promise.reject(new Error('Save rejected')));
    else { root.dataset.events=JSON.stringify(events.map(value=>value.id==='edit'?{...value,startsAt:event.detail.startsAt,endsAt:event.detail.endsAt}:value)); update(); event.detail.waitUntil(Promise.resolve()); }
  });
  const move=()=>{host.querySelector('.calendar-column').getBoundingClientRect=()=>({left:0,right:300,top:0,bottom:1200});host.querySelector('[data-event="edit"]').dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:100,clientY:450,bubbles:true}));document.dispatchEvent(new PointerEvent('pointermove',{clientX:100,clientY:474,bubbles:true}));document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));};
  move(); await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(status.textContent,'Save rejected'); assert.equal(status.getAttribute('role'),'alert');
  assert.equal(root.hasAttribute('data-drag-pending'),false); assert.equal(host.querySelector('[data-event="edit"]').style.top,'432px');
  reject=false; move(); await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(status.textContent,'Event saved.'); assert.equal(host.querySelector('[data-event="edit"]').style.top,'456px');
}));
test('all-day occurrences move between all-day columns using calendar dates', () => fixture('week', ({root,host,update}) => {
  root.dataset.events=JSON.stringify([{...events[2],startsAt:'2026-11-05',endsAt:'2026-11-06',editable:true}]); update();
  const source=host.querySelector('.calendar-allday[data-day="2026-11-05"]'),target=host.querySelector('.calendar-allday[data-day="2026-11-06"]');
  source.getBoundingClientRect=()=>({left:0,right:100,top:0,bottom:30}); target.getBoundingClientRect=()=>({left:100,right:200,top:0,bottom:30});
  let result; root.addEventListener('calendarmoveevent',event=>result=event.detail);
  source.querySelector('[data-event="all"]').dispatchEvent(new PointerEvent('pointerdown',{button:0,clientX:50,clientY:10,bubbles:true}));
  document.dispatchEvent(new PointerEvent('pointermove',{clientX:150,clientY:10,bubbles:true})); document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
  assert.equal(result.startsAt,'2026-11-06'); assert.equal(result.endsAt,'2026-11-07');
}));
test('multi-day segments clip to their day and only the final segment resizes', () => fixture('week', ({root,host,update}) => {
  root.dataset.events=JSON.stringify([{...events[0],startsAt:'2026-11-04T22:00:00',endsAt:'2026-11-05T02:00:00'}]); update();
  const first=host.querySelector('.calendar-column[data-day="2026-11-04"] [data-event="edit"]'),last=host.querySelector('.calendar-column[data-day="2026-11-05"] [data-event="edit"]');
  assert.equal(first.style.top,'1056px'); assert.equal(first.style.height,'96px'); assert.equal(first.querySelector('[data-resize]'),null);
  assert.equal(last.style.top,'0px'); assert.equal(last.style.height,'96px'); assert.ok(last.querySelector('[data-resize]'));
}));
