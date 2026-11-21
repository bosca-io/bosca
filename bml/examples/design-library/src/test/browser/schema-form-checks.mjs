import assert from 'node:assert/strict';

/** Real compiled consumer controls, native validation, typed submission and palette isolation. */
export async function checkSchemaForms({ call, evaluate, until, base }) {
  await call('Page.navigate', { url: base + '/schema-forms' });
  await until(`document.readyState==='complete' && document.querySelector('form')?.dataset.bmlActionBound==='true'`);
  for (const width of [1920, 1024, 768, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`), true, `form overflow at ${width}`);
    for (const palette of ['light', 'purple']) {
      const selector = `[data-palette=${palette}]`;
      const result = await evaluate(`(() => {
        const root=document.querySelector(${JSON.stringify(selector)}), form=root.querySelector('form');
        const name=root.querySelector('[data-schema-property=name] input');
        const quantity=root.querySelector('[data-schema-property=quantity] input');
        const enabled=root.querySelector('[data-schema-property=enabled] input');
        return {label:name.labels[0]?.textContent.trim(),required:name.required,quantity:quantity.value,enabled:enabled.checked,
          hidden:JSON.parse(form.elements.attributes.value),row:getComputedStyle(root.querySelector('.schema-row')).display};
      })()`);
      assert.equal(result.label, 'Name *');
      assert.equal(result.required, true);
      assert.equal(result.quantity, '0');
      assert.equal(result.enabled, false);
      assert.deepEqual(result.hidden, { quantity: 0, enabled: false });
      assert.equal(result.row, width < 600 ? 'flex' : 'grid');
    }
  }
  const fill = async (palette, name, quantity, enabled) => evaluate(`(() => {
    const root=document.querySelector('[data-palette=${palette}]');
    const name=root.querySelector('[data-schema-property=name] input'), quantity=root.querySelector('[data-schema-property=quantity] input'), enabled=root.querySelector('[data-schema-property=enabled] input');
    name.value=${JSON.stringify(name)}; quantity.value=${JSON.stringify(String(quantity))}; enabled.checked=${enabled};
    for(const control of [name,quantity,enabled]) control.dispatchEvent(new Event('input',{bubbles:true}));
  })()`);
  await fill('light', 'A', -1, true);
  await evaluate(`document.querySelector('[data-palette=light] form').requestSubmit()`);
  assert.equal(await evaluate(`document.querySelector('[data-saved=light]').textContent.trim()`), '');
  assert.equal(await evaluate(`document.activeElement.closest('[data-schema-property]').dataset.schemaProperty`), 'name');
  await fill('light', 'Atlas form', 7, true);
  await evaluate(`document.querySelector('[data-palette=light] form').requestSubmit()`);
  await until(`document.querySelector('[data-saved=light]')?.textContent.includes('Atlas form')`);
  assert.deepEqual(await evaluate(`JSON.parse(document.querySelector('[data-saved=light]').textContent)`), { name: 'Atlas form', quantity: 7, enabled: true });
  assert.equal(await evaluate(`document.querySelector('[data-saved=purple]').textContent.trim()`), '');
  assert.equal(await evaluate(`document.querySelector('[data-palette=light] [data-schema-property=name] input').value`), 'Atlas form');
  for(const palette of ['light','purple']) await checkFormAdapters({call,evaluate,until}, `[data-palette=${palette}] [data-bml-schema-form]`);
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
