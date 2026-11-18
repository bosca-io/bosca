import assert from 'node:assert/strict';
import test from 'node:test';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../../bml-runtime/package.json',import.meta.url));
const {Window}=require('happy-dom'),{build}=require('esbuild');
const bundle=await build({entryPoints:['src/main/client/helpers/calendar-event-fields.ts'],bundle:true,write:false,format:'esm'});
const {mountCalendarEventFields}=await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
function fixture(value,check) {
  const window=new Window();
  for(const key of ['document','Event','HTMLElement','HTMLInputElement']) globalThis[key]=window[key];
  const root=document.createElement('div'),form=document.createElement('form'); root.dataset.value=JSON.stringify(value);form.append(root);document.body.append(form);
  root.innerHTML='<select data-bml-ref="kind"><option value="timed">Timed</option><option value="all-day">All day</option></select><input data-bml-ref="start" type="datetime-local"><input data-bml-ref="end" type="datetime-local"><input data-bml-ref="startValue" type="hidden"><input data-bml-ref="endValue" type="hidden"><input data-bml-ref="zone" type="hidden"><input data-bml-ref="rrule" type="hidden"><fieldset data-bml-ref="recurrence"></fieldset><select data-bml-ref="frequency"><option value="">None</option><option value="WEEKLY">Weekly</option></select><input data-bml-ref="interval" value="1"><input data-bml-ref="count"><input data-bml-ref="until"><input type="checkbox" data-weekday="MO" value="MO">';
  const ref=name=>root.querySelector(`[data-bml-ref="${name}"]`);ref('kind').value=value.allDay?'all-day':'timed';ref('rrule').value=value.rrule??'';
  const cleanup=[];
  mountCalendarEventFields({root,ref,on:(node,name,handler,capture)=>{node.addEventListener(name,handler,capture);cleanup.push(()=>node.removeEventListener(name,handler,capture));}});
  try{check({root,form,ref});}finally{cleanup.forEach(fn=>fn());window.happyDOM.cancelAsync();}
}
test('ambiguous DST instants survive unchanged local date fields',()=>{
  const previous=process.env.TZ;process.env.TZ='America/New_York';
  try{fixture({startsAt:'2026-11-01T05:30:00Z',endsAt:'2026-11-01T06:30:00Z'},({form,ref})=>{
    assert.equal(ref('start').value,'2026-11-01T01:30');assert.equal(ref('end').value,'2026-11-01T01:30');
    form.dispatchEvent(new Event('submit',{bubbles:true}));
    assert.equal(ref('startValue').value,'2026-11-01T05:30:00Z');assert.equal(ref('endValue').value,'2026-11-01T06:30:00Z');assert.equal(ref('end').checkValidity(),true);
    ref('end').value='2026-11-01T02:30';ref('end').dispatchEvent(new Event('input',{bubbles:true}));
    assert.equal(ref('endValue').value,'2026-11-01T07:30:00.000Z');
  });}finally{if(previous===undefined)delete process.env.TZ;else process.env.TZ=previous;}
});
test('all-day dates use an exclusive end and simple recurrence controls build RRULE',()=>fixture({startsAt:'2026-11-05',endsAt:'2026-11-06',allDay:true},({root,ref})=>{
  assert.equal(ref('start').type,'date');assert.equal(ref('startValue').value,'2026-11-05T00:00:00');assert.equal(ref('end').checkValidity(),true);
  ref('end').value='2026-11-05';ref('end').dispatchEvent(new Event('input',{bubbles:true}));assert.equal(ref('end').checkValidity(),false);
  ref('frequency').value='WEEKLY';ref('interval').value='2';ref('count').value='3';root.querySelector('[data-weekday]').checked=true;
  ref('frequency').dispatchEvent(new Event('change',{bubbles:true}));assert.equal(ref('rrule').value,'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=3');
}));

test('recurrence is hidden and disabled for all-day events and restores for timed events',()=>fixture({startsAt:'2026-11-05',endsAt:'2026-11-06',allDay:true,rrule:'FREQ=WEEKLY'},({ref})=>{
  assert.equal(ref('recurrence').hidden,true);assert.equal(ref('recurrence').disabled,true);
  assert.equal(ref('rrule').type,'hidden');assert.equal(ref('rrule').value,'FREQ=WEEKLY');
  ref('kind').value='timed';ref('kind').dispatchEvent(new Event('change',{bubbles:true}));
  assert.equal(ref('recurrence').hidden,false);assert.equal(ref('recurrence').disabled,false);
  ref('kind').value='all-day';ref('kind').dispatchEvent(new Event('change',{bubbles:true}));
  assert.equal(ref('recurrence').hidden,true);assert.equal(ref('rrule').value,'FREQ=WEEKLY');
}));
