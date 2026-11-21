import assert from 'node:assert/strict';

/** Nested layout, widget focus, pending state and retained drafts through real catalog actions. */
export async function checkSchemaForms({ navigate, evaluate, until, call }) {
  await navigate('/schema-forms');
  await until(`document.querySelector('[data-bml-schema-form]') && document.querySelector('form')?.dataset.bmlActionBound==='true'`);
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1100, deviceScaleFactor: 1, mobile: false });
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`), true);
    for (const palette of ['dark', 'light']) {
      const root = `document.querySelector('.catalog-palette-${palette} [data-bml-schema-form]')`;
      assert.equal(await evaluate(`getComputedStyle(${root}.querySelector('.schema-row')).display`), width < 600 ? 'flex' : 'grid');
      assert.equal(await evaluate(`${root}.querySelector('[data-schema-property=title] input').value`), 'Example note');
      assert.equal(await evaluate(`${root}.querySelector('[data-schema-property=active] input').checked`), false);
    }
  }
  const root = `document.querySelector('.catalog-palette-light [data-bml-schema-form]')`;
  const form = `${root}.closest('form')`;
  const key = async name => {
    const windowsVirtualKeyCode = { Enter: 13, ArrowDown: 40, ArrowRight: 39, Home: 36 }[name];
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: name, windowsVirtualKeyCode });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: name, windowsVirtualKeyCode });
  };
  await evaluate(`${form}.requestSubmit()`);
  assert.equal(await evaluate(`document.activeElement===${root}.querySelector('[data-schema-property=owner] [data-bml-ref=trigger]')`), true);
  await key('Enter'); await key('ArrowDown'); await key('Enter');
  const owner = await evaluate(`${root}.querySelector('[data-schema-property=owner] select').value`);
  assert.ok(['one', 'two'].includes(owner));
  await evaluate(`${form}.requestSubmit()`);
  assert.equal(await evaluate(`document.activeElement===${root}.querySelector('[data-schema-property=date] input')`), true);
  assert.equal(await evaluate(`${root}.querySelector('[role=tab][aria-selected=true]').textContent.trim()`), 'Options');
  await evaluate(`(() => {const date=${root}.querySelector('[data-schema-property=date] input');date.value='2030-01-02';date.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await evaluate(`${root}.querySelector('[role=tab]').focus()`); await key('Home');
  await evaluate(`(() => {const title=${root}.querySelector('[data-schema-property=title] input');title.value='reject';title.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await evaluate(`${form}.requestSubmit()`);
  await until(`${root}.inert`);
  await until(`document.querySelector('.catalog-palette-light').textContent.includes('The example rejected this submission')`);
  assert.equal(await evaluate(`${root}.querySelector('[data-schema-property=title] input').value`), 'reject');
  assert.equal(await evaluate(`${root}.querySelector('[data-schema-property=owner] select').value`), owner);
  assert.equal(await evaluate(`${root}.inert`), false);
  await evaluate(`(() => {const title=${root}.querySelector('[data-schema-property=title] input');title.value='Retried form';title.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await evaluate(`${form}.requestSubmit()`);
  await until(`document.querySelector('.catalog-palette-light').textContent.includes('Form saved (1)')`);
  assert.equal(await evaluate(`document.querySelector('.catalog-palette-dark [data-schema-property=title] input').value`), 'Example note');
  assert.equal(await evaluate(`JSON.parse(${root}.querySelector('[name=attributes]').value).date`), '2030-01-02');
  for(const palette of ['dark','light']) await checkFormAdapters({call,evaluate,until}, `.catalog-palette-${palette} [data-bml-schema-form]`);
}

async function checkFormAdapters({call, evaluate, until}, selector) {
  const root = `document.querySelector(${JSON.stringify(selector)})`;
  const field = `${root}.querySelector('[data-schema-property=choice]')`;
  await until(`${field}.querySelector('[data-bml-design-select]')?.hasAttribute('data-select-ready')`);
  await evaluate(`${field}.querySelector('[data-bml-ref=trigger]').click()`);
  const input = `${field}.querySelector('[data-bml-ref=query]')`;
  await evaluate(`${input}.value='31';${input}.dispatchEvent(new Event('input',{bubbles:true}))`);
  await until(`${field}.querySelector('[role=option]')?.textContent.includes('Example choice 31')`);
  const box = await evaluate(`(() => {const b=${field}.querySelector('[role=option]').getBoundingClientRect();return {x:b.x+b.width/2,y:b.y+b.height/2};})()`);
  await call('Input.dispatchMouseEvent', {type:'mousePressed',button:'left',clickCount:1,...box});
  await call('Input.dispatchMouseEvent', {type:'mouseReleased',button:'left',clickCount:1,...box});
  assert.equal(await evaluate(`${field}.querySelector('select').value`), 'choice-31');
  assert.equal(await evaluate(`${field}.querySelector('[data-bml-ref=selection]').textContent.trim()`), 'Example choice 31');
  await evaluate(`${field}.querySelector('[data-bml-ref=trigger]').click()`);
  await until(`!${field}.querySelector('[data-bml-ref=next]').disabled`);
  await evaluate(`${field}.querySelector('[data-bml-ref=next]').click()`);
  await until(`${field}.querySelector('[role=option]')?.textContent.includes('Example choice 26')`);
  await evaluate(`${input}.value='fail';${input}.dispatchEvent(new Event('input',{bubbles:true}))`);
  await until(`${field}.textContent.includes('Example lookup failed')`);
  assert.equal(await evaluate(`${field}.querySelector('select').value`), 'choice-31');
  await evaluate(`${input}.value='31';${input}.dispatchEvent(new Event('input',{bubbles:true}))`);
  await until(`${field}.querySelector('[data-schema-choices-status]').textContent===''`);
  await call('Input.dispatchKeyEvent', {type:'keyDown',key:'Escape',windowsVirtualKeyCode:27});
  await call('Input.dispatchKeyEvent', {type:'keyUp',key:'Escape',windowsVirtualKeyCode:27});
  for (const [property, name, type, bytes] of [['attachment','note.txt','text/plain','File bytes'],['illustration','image.svg','image/svg+xml','<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="blue"/></svg>']]) {
    const file = `${root}.querySelector('[data-schema-property=${property}]')`;
    await evaluate(`(() => {const input=${file}.querySelector('input[type=file]'),transfer=new DataTransfer();transfer.items.add(new File([${JSON.stringify(bytes)}],${JSON.stringify(name)},{type:${JSON.stringify(type)}}));input.files=transfer.files;input.dispatchEvent(new Event('change',{bubbles:true}));})()`);
    await until(`JSON.parse(${root}.querySelector('[name=attributes]').value).${property}?.startsWith('data:')`);
    assert.equal(await evaluate(`JSON.parse(${root}.querySelector('[name=attributes]').value).${property}`), `data:${type};base64,${Buffer.from(bytes).toString('base64')}`);
    if(property==='illustration') await until(`${file}.querySelector('img').naturalWidth===8`);
    await evaluate(`${file}.querySelector('[data-file-field]').setAttribute('data-readonly','')`);
    await until(`${file}.querySelector('input[type=file]').disabled && ${file}.querySelector('[data-bml-ref=fileClear]').disabled`);
    await evaluate(`${file}.querySelector('[data-file-field]').removeAttribute('data-readonly')`);
    await until(`!${file}.querySelector('[data-bml-ref=fileClear]').disabled`);
    await evaluate(`${file}.querySelector('[data-bml-ref=fileClear]').click()`);
    assert.equal(await evaluate(`JSON.parse(${root}.querySelector('[name=attributes]').value).${property}`), null);
  }
}
