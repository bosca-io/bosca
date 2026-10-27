import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real contract requests and native submission through two independently themed picker instances. */
export async function checkPickers({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const native = id => `document.querySelector('[data-picker-native=${id}]')`;
  const root = id => `${native(id)}.closest('[data-bml-design-select]')`;
  const part = (id, ref) => `${root(id)}.querySelector('[data-bml-ref=${ref}]')`;
  const key = async value => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key: value,
      windowsVirtualKeyCode: { Enter: 13, Escape: 27, Tab: 9, Home: 36, End: 35, ArrowDown: 40, ArrowUp: 38 }[value],
      ...(value === 'Enter' && type === 'keyDown' ? { text: '\r' } : {}) });
  };
  const open = async id => {
    await evaluate(`${part(id, 'trigger')}.focus()`); await key('Enter');
    await until(`${part(id, 'popup')}.matches(':popover-open')`);
  };
  const type = (id, query) => evaluate(`${part(id, 'query')}.value=${JSON.stringify(query)};${part(id, 'query')}.dispatchEvent(new Event('input',{bubbles:true}))`);
  const ready = (id, text) => until(`!${part(id, 'popup')}.hasAttribute('aria-busy')&&${part(id, 'options')}.textContent.includes(${JSON.stringify(text)})`);
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await navigate('/selection');
  await until(`document.querySelector('[data-picker-form=dark]')?.dataset.bmlActionBound==='true'&&${part('dark', 'trigger')}.hasAttribute('aria-controls')&&${part('light', 'trigger')}.hasAttribute('aria-controls')`);
  assert.ok(await evaluate(`(() => {const field=${native('dark')};return field instanceof HTMLSelectElement&&field.labels.length===1&&field.required&&field.options.length===17&&field.querySelector('[value=unavailable]').disabled&&new FormData(field.form).get('example')==='';})()`));
  assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-picker-native='+id+']').closest('[data-bml-design-select]').querySelector('[data-bml-ref=trigger]')).backgroundColor)`), ['rgb(22, 26, 37)', 'rgb(241, 245, 249)']);
  await evaluate(`(() => {
    window.pickerRequests=[];window.pickerReplies=[];
    const original=window.fetch.bind(window);
    window.fetch=async(...args)=>{
      if(!String(args[0]).includes('/_bml/contract/CatalogChoices/search'))return original(...args);
      const request=JSON.parse(args[1].body)[0];window.pickerRequests.push(request);
      const response=await original(...args);
      if(request.query===window.pickerHoldQuery){window.pickerHoldQuery=null;await new Promise(resolve=>{window.releasePicker=resolve;});}
      const json=response.json.bind(response);
      response.json=async()=>{const value=await json();window.pickerReplies.push(request.query);return value;};
      return response;
    };
  })()`);
  await open('dark'); await type('dark', 'c');
  assert.ok(await evaluate(`${part('dark', 'status')}.textContent.includes('at least 2')&&window.pickerRequests.length===0`));
  await evaluate(`window.pickerHoldQuery='control'`); await type('dark', 'control');
  await until(`typeof window.releasePicker==='function'`);
  assert.ok(await evaluate(`${part('dark', 'popup')}.getAttribute('aria-busy')==='true'&&${part('dark', 'status')}.textContent==='Searching…'`));
  assert.deepEqual(await evaluate('window.pickerRequests.map(request=>[request.query,request.offset])'), [['control', 0]]);
  await evaluate(`window.releasePicker()`); await ready('dark', 'Button states');
  assert.ok(await evaluate(`${part('dark', 'previous')}.disabled&&!${part('dark', 'next')}.disabled&&${part('dark', 'options')}.querySelector('[aria-disabled=true]').textContent.includes('Unavailable')`));
  await key('End');
  assert.equal(await evaluate(`document.getElementById(${part('dark', 'query')}.getAttribute('aria-activedescendant')).textContent`), 'Text inputs · control');
  await key('Home'); await key('Enter');
  assert.equal(await evaluate(`${native('dark')}.value`), 'button');
  assert.equal(await evaluate(`${native('light')}.value`), '');

  await open('dark'); await type('dark', 'control'); await ready('dark', 'Button states');
  await evaluate(`${part('dark', 'next')}.click()`); await ready('dark', 'Checkbox choices');
  assert.equal(await evaluate(`${native('dark')}.value`), 'button');
  await evaluate(`${part('dark', 'next')}.click()`); await ready('dark', 'Local view tabs');
  assert.ok(await evaluate(`${part('dark', 'next')}.disabled&&!${part('dark', 'previous')}.disabled`));
  await evaluate(`${part('dark', 'previous')}.click()`); await ready('dark', 'Checkbox choices');
  assert.deepEqual(await evaluate('window.pickerRequests.slice(-3).map(request=>request.offset)'), [3, 6, 3]);

  await evaluate(`window.retainedPicker=${root('dark')};window.retainedQuery=${part('dark', 'query')};window.retainedQuery.setSelectionRange(1,4);document.querySelector('[data-picker-states=dark]').open=true;document.querySelector('[data-picker-failure=dark]').click()`);
  await until(`document.querySelector('[data-picker-example=dark]').hasAttribute('data-search-failure')`);
  assert.ok(await evaluate(`document.querySelector('[data-picker-states=dark]').open`), 'Picker state controls remain available after an action');
  assert.ok(await evaluate(`${root('dark')}===window.retainedPicker&&${part('dark', 'query')}===window.retainedQuery&&window.retainedQuery.value==='control'&&window.retainedQuery.selectionStart===1&&window.retainedQuery.selectionEnd===4`));
  await type('dark', 'control');
  await until(`${part('dark', 'status')}.textContent==='Search failed. Please try again.'`);
  assert.equal(await evaluate(`${native('dark')}.value`), 'button');
  await evaluate(`document.querySelector('[data-picker-failure=dark]').click()`);
  await until(`!document.querySelector('[data-picker-example=dark]').hasAttribute('data-search-failure')`);
  await type('dark', 'control'); await ready('dark', 'Button states');
  await type('dark', 'no-matches');
  await until(`${part('dark', 'status')}.textContent==='No options found.'`);
  assert.equal(await evaluate(`${native('dark')}.value`), 'button');

  // Delay an actual response until a newer query has rendered, then observe the old JSON being consumed.
  await evaluate(`window.pickerHoldQuery='layout';window.releasePicker=null`); await type('dark', 'layout');
  await until(`typeof window.releasePicker==='function'`);
  await type('dark', 'overlay'); await ready('dark', 'Dialogs');
  const oldReplies = await evaluate(`window.pickerReplies.filter(query=>query==='layout').length`);
  await evaluate('window.releasePicker()');
  await until(`window.pickerReplies.filter(query=>query==='layout').length>${oldReplies}`);
  await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))`);
  assert.ok(await evaluate(`${part('dark', 'options')}.textContent.includes('Dialogs')&&!${part('dark', 'options')}.textContent.includes('Cards')`));
  await key('Escape');
  await evaluate(`document.querySelector('[data-picker-form=dark]').requestSubmit()`);
  await until(`document.querySelector('[data-picker-toast=dark]').matches(':popover-open')`);
  assert.equal(await evaluate(`document.querySelector('[data-picker-toast=dark] [role=status]').textContent`), 'Saved Button states.');
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-picker-form=dark]')).get('example')`), 'button');

  await evaluate(`document.querySelector('[data-picker-loading=dark]').click()`);
  await until(`${part('dark', 'trigger')}.disabled&&${part('dark', 'trigger')}.getAttribute('aria-busy')==='true'`);
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-picker-form=dark]')).get('example')`), 'button');
  await evaluate(`document.querySelector('[data-picker-loading=dark]').click()`); await until(`!${part('dark', 'trigger')}.disabled`);
  await evaluate(`document.querySelector('[data-picker-disable=dark]').click()`); await until(`${native('dark')}.disabled`);
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-picker-form=dark]')).get('example')`), null);
  assert.equal(await evaluate(`${part('light', 'trigger')}.disabled`), false);
  await evaluate(`document.querySelector('[data-picker-disable=dark]').click()`); await until(`!${native('dark')}.disabled`);
  await evaluate(`document.querySelector('[data-picker-form=dark] [type=reset]').click()`);
  await until(`${native('dark')}.value===''`);
  assert.ok(await evaluate(`!document.querySelector('[data-picker-form=dark]').reportValidity()&&document.activeElement===${part('dark', 'trigger')}`));

  await open('light'); await type('light', 'layout'); await ready('light', 'Cards');
  await evaluate(`${part('light', 'next')}.click()`); await ready('light', 'deliberately long');
  await key('Home'); await key('Enter');
  assert.equal(await evaluate(`${native('light')}.value`), 'page-heading');
  await evaluate(`document.querySelector('[data-picker-form=light]').requestSubmit()`);
  await until(`document.querySelector('[data-picker-toast=light]').matches(':popover-open')`);
  assert.equal(await evaluate(`${native('dark')}.value`), '');
  await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click()`);

  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await open('light'); await type('light', 'layout'); await ready('light', 'Cards');
    await evaluate(`${part('light', 'next')}.click()`); await ready('light', 'deliberately long');
    assert.ok(await evaluate(`(() => {const popup=${part('light', 'popup')},r=popup.getBoundingClientRect();return r.left>=11&&r.right<=innerWidth-11&&r.top>=11&&r.bottom<=innerHeight-11&&document.documentElement.scrollWidth<=innerWidth;})()`));
    assert.equal(await evaluate(`getComputedStyle(${part('light', 'popup')}).backgroundColor`), 'rgb(241, 245, 249)');
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `catalog-${mode}-picker-${width}.png`), Buffer.from(shot.data, 'base64'));
    await key('Escape');
  }
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 320, deviceScaleFactor: 1, mobile: false });
  await evaluate(`${part('dark', 'trigger')}.scrollIntoView({block:'center'})`);
  await open('dark'); await type('dark', 'layout'); await ready('dark', 'Cards');
  assert.ok(await evaluate(`(() => {const r=${part('dark', 'popup')}.getBoundingClientRect();return r.left>=11&&r.right<=innerWidth-11&&r.top>=11&&r.bottom<=innerHeight-11;})()`));
  assert.equal(await evaluate(`getComputedStyle(${part('dark', 'popup')}).backgroundColor`), 'rgb(22, 26, 37)');
  await key('Escape');

  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 1000, deviceScaleFactor: 1, mobile: false });
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  await navigate('/selection');
  assert.ok(await evaluate(`(() => {const native=${native('dark')};return native.labels.length===1&&native.required&&native.options.length===17&&native.querySelector('[value=unavailable]').disabled&&native.tabIndex!==-1;})()`));
  await evaluate(`${native('dark')}.value='panel'`);
  assert.equal(await evaluate(`new FormData(document.querySelector('[data-picker-form=dark]')).get('example')`), 'panel');
  await call('Emulation.setScriptExecutionDisabled', { value: false });
}
