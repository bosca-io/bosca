import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Real editor drafts, controlled options and native form serialization in independent palette roots. */
export async function checkJsonEditors({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const truth = expression => evaluate(`Boolean(${expression})`);
  const root = id => `document.querySelector('[data-catalog-json-editor=${id}]')`;
  const field = id => `${root(id)}.querySelector('textarea')`;
  const text = id => `${root(id)}.querySelector('.cm-content')`;
  const form = id => `document.querySelector('[data-catalog-json=${id}]')`;
  const toast = id => `document.querySelector('[data-catalog-json-toast=${id}]')`;
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const key = async (value, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key: value, modifiers,
      windowsVirtualKeyCode: { Enter: 13, Escape: 27, Tab: 9, a: 65 }[value] });
  };
  const replace = async (id, value) => {
    await evaluate(`${text(id)}.focus()`);
    await key('a', await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
    await call('Input.insertText', { text: value });
  };
  const action = async (id, name, done) => {
    await click(`[data-json-${name}=${id}]`);
    await until(done);
  };
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await navigate('/json');
  await until(`document.querySelectorAll('[data-json-ready]').length===8&&${form('dark')}.dataset.bmlActionBound==='true'`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`), '/json');
  assert.ok(await truth(`document.querySelector('[data-json-table] .jse-table-mode')&&document.querySelector('[data-json-empty] textarea').value===''&&document.querySelector('[data-json-malformed] textarea').value==='{ "title": '&&document.querySelector('[data-json-empty]').getAttribute('aria-invalid')==='true'&&document.querySelector('[data-json-malformed]').getAttribute('aria-invalid')==='true'&&document.querySelector('[data-json-readonly-case] textarea').readOnly&&document.querySelector('[data-json-disabled-case] textarea').disabled`));
  assert.ok(await truth(`!window.catalogJsonExecuted&&!document.querySelector('[data-json-strings] script')&&document.querySelector('[data-json-strings] textarea').value.includes('</textarea><script>window.catalogJsonExecuted = true;</script>')`));
  await evaluate(`document.querySelectorAll('[data-catalog-json-owner] details').forEach(details=>{details.open=true;})`);
  await action('dark', 'mode', `${root('dark')}.dataset.mode==='text'&&${text('dark')}`);
  assert.ok(await truth(`document.querySelector('[data-catalog-json-owner=dark] details').open`), 'Server actions retain the native state disclosure');
  const invalid = '{ "draft": ';
  await replace('dark', invalid);
  await until(`${field('dark')}.value===${JSON.stringify(invalid)}&&${root('dark')}.getAttribute('aria-invalid')==='true'`);
  await evaluate(`window.retainedJson=${root('dark')};window.retainedJsonEngine=${root('dark')}.querySelector('.jse-main');window.retainedJsonText=${text('dark')};${text('dark')}.focus()`);
  await action('dark', 'refresh', `${toast('dark')}.dataset.sequence==='1'&&${toast('dark')}.matches(':popover-open')`);
  assert.ok(await truth(`${root('dark')}===window.retainedJson&&${root('dark')}.querySelector('.jse-main')===window.retainedJsonEngine&&${text('dark')}===window.retainedJsonText&&document.activeElement===window.retainedJsonText&&${field('dark')}.value===${JSON.stringify(invalid)}`));
  assert.ok(await truth(`${root('light')}.dataset.mode==='tree'&&${field('light')}.value.includes('enabled')&&${toast('light')}.dataset.sequence==='0'&&!${toast('light')}.matches(':popover-open')`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click();${form('dark')}.requestSubmit()`);
  await until(`${root('dark')}.dataset.error==='Enter valid JSON before saving.'&&!${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${field('dark')}.value===${JSON.stringify(invalid)}&&${text('dark')}.textContent===${JSON.stringify(invalid)}&&${root('dark')}.querySelector('[data-bml-ref=feedback]').textContent.includes('Enter valid JSON')&&!${toast('dark')}.matches(':popover-open')`));

  const valid = '{ "saved": "current draft", "count": 42 }';
  await evaluate(`(() => {const fetch=window.fetch.bind(window);window.fetch=async(...args)=>{
    if(String(args[0]).includes('/_bml/action')){window.fetch=fetch;await new Promise(resolve=>{window.releaseJsonSave=resolve;});}
    return fetch(...args);
  };})()`);
  await replace('dark', valid);
  await evaluate(`${form('dark')}.requestSubmit()`);
  await until(`typeof window.releaseJsonSave==='function'&&${form('dark')}.hasAttribute('data-bml-action-pending')&&${field('dark')}.readOnly&&${text('dark')}.getAttribute('aria-readonly')==='true'`);
  assert.equal(await evaluate(`new FormData(${form('dark')}).get('settings')`), valid, 'Immediate submit must flush the current text draft');
  await evaluate('window.releaseJsonSave()');
  await until(`${toast('dark')}.dataset.sequence==='2'&&${toast('dark')}.matches(':popover-open')&&!${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${root('dark')}.getAttribute('aria-invalid')==='false'&&!${field('dark')}.readOnly&&${toast('dark')}.querySelector('[role=status]').textContent==='Settings saved.'`));
  await replace('dark', '{"unsaved":true}');
  await click('[data-json-reset=dark]');
  await until(`${field('dark')}.value===${JSON.stringify(valid)}&&${text('dark')}.textContent===${JSON.stringify(valid)}`);
  await action('dark', 'replace', `${field('dark')}.value==='{"component":"json-editor","revision":1}'`);
  assert.ok(await truth(`${root('dark')}===window.retainedJson&&${root('dark')}.querySelector('.jse-main')===window.retainedJsonEngine`), 'A changed value reconciles on the same editor');
  await replace('dark', '{"local":true}'); await click('[data-json-reset=dark]');
  await until(`${field('dark')}.value==='{"component":"json-editor","revision":1}'`);

  await action('dark', 'readonly', `${root('dark')}.hasAttribute('data-readonly')&&${text('dark')}.getAttribute('aria-readonly')==='true'`);
  await evaluate(`window.readonlyJson=${field('dark')}.value;${text('dark')}.focus()`);
  await call('Input.insertText', { text: 'FORBIDDEN' });
  assert.ok(await truth(`${field('dark')}.value===window.readonlyJson&&new FormData(${form('dark')}).get('settings')===window.readonlyJson&&document.querySelector('[data-json-save=dark]').disabled`));
  await action('dark', 'readonly', `!${field('dark')}.readOnly`);
  await action('dark', 'disabled', `${field('dark')}.disabled`);
  assert.ok(await truth(`${root('dark')}.querySelector('[data-bml-ref=editor]').inert&&!new FormData(${form('dark')}).has('settings')&&document.querySelector('[data-json-save=dark]').disabled&&!${field('light')}.disabled`));
  await action('dark', 'disabled', `!${field('dark')}.disabled&&!${field('dark')}.readOnly`);
  await action('dark', 'mode', `${root('dark')}.dataset.mode==='table'&&${root('dark')}.querySelector('.jse-table-mode')`);
  await action('dark', 'mode', `${root('dark')}.dataset.mode==='tree'&&${root('dark')}.querySelector('.jse-tree-mode')`);

  // The engine can change view locally without requiring another caller action.
  await evaluate(`${root('dark')}.querySelector('button[title^="Switch to text"]').click()`);
  await until(`${text('dark')}`);
  await action('dark', 'refresh', `${toast('dark')}.dataset.sequence==='3'`);
  assert.ok(await truth(`${root('dark')}.dataset.mode==='tree'&&${text('dark')}`), 'An unchanged server mode preserves the local view choice');
  await evaluate(`${text('dark')}.focus()`); await key('Escape'); await key('Tab');
  assert.ok(await truth(`document.activeElement!==${text('dark')}&&!${text('dark')}.contains(document.activeElement)`));
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  for (let cycle = 0; cycle < 2; cycle++) {
    await evaluate(`window.removedJson=${root('dark')};window.removedJsonEngine=${root('dark')}.querySelector('.jse-main')`);
    await action('dark', 'visible', `!${root('dark')}&&!window.removedJsonEngine.isConnected&&!window.removedJson.querySelector('.jse-main')`);
    assert.ok(await truth(`!new FormData(${form('dark')}).has('settings')&&document.querySelector('[data-json-save=dark]').disabled`));
    await action('dark', 'visible', `${root('dark')}?.hasAttribute('data-json-ready')`);
    assert.equal(await evaluate(`${root('dark')}.querySelectorAll('.jse-main').length`), 1);
    assert.ok(await truth(`${field('dark')}.value==='{"component":"json-editor","revision":1}'&&!${toast('dark')}.matches(':popover-open')`));
  }

  await evaluate(`document.querySelector('[data-catalog-json-owner=dark] details').open=false`);
  const sequence=await evaluate(`Number(${toast('dark')}.dataset.sequence)`);
  await evaluate(`${form('dark')}.requestSubmit()`);
  await until(`Number(${toast('dark')}.dataset.sequence)===${sequence+1}&&!${form('dark')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`!document.querySelector('[data-catalog-json-owner=dark] details').open`), 'A save respects an explicitly closed disclosure');
  await evaluate(`${toast('dark')}.querySelector('[data-bml-ref=dismiss]').click()`);
  const measurements = [];
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click();document.fonts.ready`);
    await until(`document.documentElement.scrollWidth<=innerWidth`);
    assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-json-editor='+id+'] .jse-main')).backgroundColor)`), ['rgb(16, 19, 28)', 'rgb(255, 255, 255)']);
    const geometry = await evaluate(`(() => {
      const groups=[...document.querySelectorAll('[data-catalog-json-owner]')].map(owner=>{const editor=owner.querySelector('[data-bml-json-editor]').getBoundingClientRect(),engine=owner.querySelector('.jse-main').getBoundingClientRect(),actions=owner.querySelector('.example-actions').getBoundingClientRect(),form=owner.querySelector('form').getBoundingClientRect();return {id:owner.dataset.catalogJsonOwner,height:engine.height,gap:actions.top-editor.bottom,contained:engine.bottom<=actions.top&&actions.bottom<=form.bottom};});
      const cards=[...document.querySelectorAll('.catalog-grid .ui-card')].map(card=>card.getBoundingClientRect());return {groups,cards:cards.map(rect=>({top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right})),overflow:document.documentElement.scrollWidth-innerWidth};
    })()`);
    for (const group of geometry.groups) assert.ok(Math.abs(group.height-320)<1&&group.gap>=15.5&&group.contained, JSON.stringify(group));
    assert.ok(geometry.overflow<=0);
    if (width===390) assert.ok(geometry.cards.slice(1).every((card,i)=>card.top-geometry.cards[i].bottom>=23.5), JSON.stringify(geometry.cards));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-json-palette] .jse-key, [data-json-palette] .jse-value, [data-json-palette] .jse-menu button:not(:disabled), [data-json-palette] [data-bml-ref=feedback], [data-json-cases] .jse-key, [data-json-cases] .jse-value, [data-json-cases] .jse-message, [data-json-cases] .jse-message button:not(:disabled), [data-json-cases] [data-bml-ref=feedback]')`);
    assert.ok(colors.text.length>=10);
    for (const sample of colors.text) assert.ok(sample.ratio>=4.5, `${width}: ${JSON.stringify(sample)}`);
    measurements.push({width,...geometry,colors:colors.text});
    await evaluate(`document.querySelectorAll('[data-catalog-json-owner] details').forEach(details=>{details.open=false;});document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);
    const shot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-json-${width}.png`),Buffer.from(shot.data,'base64'));
    for (const index of width>700?[0,2,4]:[0,1,2,3,4,5]) {
      await evaluate(`document.querySelectorAll('[data-json-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`);
      const cases=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-json-cases-${width}${index===0?'':'-'+(index+1)}.png`),Buffer.from(cases.data,'base64'));
    }
  }
  await writeFile(join(artifacts,`catalog-${mode}-json.json`),JSON.stringify(measurements,null,2));

  await call('Emulation.setScriptExecutionDisabled', { value: true });
  await navigate('/json');
  assert.ok(await truth(`!document.querySelector('.jse-main')&&!window.catalogJsonExecuted&&document.querySelector('[data-json-strings] textarea').value.includes('</textarea><script>window.catalogJsonExecuted = true;</script>')&&${field('dark')}.name==='settings'&&!${field('dark')}.hidden&&${field('dark')}.getAttribute('aria-label')==='Example settings'&&document.querySelector('[data-json-empty] textarea').value===''&&document.querySelector('[data-json-malformed] textarea').value==='{ "title": '&&document.querySelector('[data-json-readonly-case] textarea').readOnly&&document.querySelector('[data-json-disabled-case] textarea').disabled&&document.documentElement.scrollWidth<=innerWidth`));
  await call('Emulation.setScriptExecutionDisabled', { value: false });
}
