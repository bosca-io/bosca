import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Verifies authored documentation against the packaged library and exercises catalog-owned compositions. */
export async function checkReferences({ navigate, evaluate, until: poll, key, call, artifacts, mode, expected }) {
  const until = expression => poll(`Boolean(${expression})`);
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await navigate('/components');
  const links = await evaluate(`Array.from(document.querySelectorAll('[data-export-link]'), a => ({tag:a.dataset.exportLink,href:a.getAttribute('href')}))`);
  assert.deepEqual(links.map(link => link.tag).sort(), Object.keys(expected).sort());
  const routes = [...new Set(links.map(link => link.href.split('#')[0]))];
  for (const path of routes) {
    await navigate(path);
    const entries = await evaluate(`Array.from(document.querySelectorAll('[data-export]'), entry => ({
      tag:entry.dataset.export, api:entry.querySelector('.reference-api').textContent,
      contract:entry.querySelector('dd').textContent, snippet:entry.querySelector('.reference-details [data-bml-source-view] textarea').value,
      rendered:entry.querySelector('.reference-demo').children.length>0,
      compiled:!!entry.querySelector('.reference-demo [data-bml-c~="bml-696f2e626f7363613a626d6c2d64657369676e-'+entry.dataset.export+'"]')
    }))`);
    assert.deepEqual(entries.map(entry => entry.tag).sort(), links.filter(link => link.href.startsWith(path+'#')).map(link => link.tag).sort());
    for (const entry of entries) {
      assert.ok(entry.rendered && (entry.compiled || !expected[entry.tag].scoped), `Missing compiled ${entry.tag} example`);
      assert.ok(entry.contract.length > 20 && entry.snippet.includes('<'), entry.tag);
      for (const prop of expected[entry.tag].props) {
        assert.ok(entry.api.includes(`${prop.name}: ${prop.type}${prop.hasDefault ? ' = ' : ' (required)'}`), `${entry.tag}.${prop.name}: ${entry.api}`);
      }
      assert.equal(entry.api.split('\n').length, expected[entry.tag].props.length || 1, entry.tag);
    }
  }

  await navigate('/components/controls');
  await evaluate(`document.querySelector('[data-export=button]').scrollIntoView({block:'start'});document.fonts.ready`);
  const desktop = await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`catalog-${mode}-references-desktop.png`),Buffer.from(desktop.data,'base64'));
  await until(`document.querySelector('[data-local-views] [role=tab]')?.hasAttribute('aria-controls')`);
  await evaluate(`document.querySelector('[data-local-views] [role=tab]').focus()`); await key('ArrowRight');
  assert.ok(await evaluate(`(() => {const tab=document.querySelector('[data-local-views] [aria-selected=true]'),panel=document.getElementById(tab.getAttribute('aria-controls'));return tab.textContent.trim()==='Details'&&!panel.hidden&&panel.getAttribute('aria-labelledby')===tab.id;})()`));
  await key('Tab');
  assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-local-views] [data-view=Details]')`),'The copyable local-tab example has a keyboard-reachable text panel');
  await evaluate(`document.querySelector('[data-export=segmented-control] [role=radio]').focus()`); await key('ArrowRight');
  assert.equal(await evaluate(`document.querySelector('[data-export=segmented-control] input').value`), 'Code');

  await navigate('/components/overlays');
  await until(`document.querySelector('[data-catalog-confirm=reference] form')?.dataset.bmlActionBound==='true'`);
  await evaluate(`document.querySelector('[data-export=dialog] [data-bml-ref=trigger]').click()`);
  await until(`document.querySelector('[data-export=dialog] dialog').open`);
  assert.ok(await evaluate(`document.activeElement.name==='dialog-name'`));
  await key('Escape');
  await until(`!document.querySelector('[data-export=dialog] dialog').open`);
  assert.ok(await evaluate(`document.activeElement===document.querySelector('[data-export=dialog] [data-bml-ref=trigger]')`));
  await evaluate(`document.querySelector('[data-catalog-confirm=reference] [data-bml-ref=trigger]').click()`);
  await until(`document.querySelector('[data-catalog-confirm=reference] dialog').open`);
  await evaluate(`(() => {const form=document.querySelector('[data-catalog-confirm=reference] form');form.elements.reason.value='  Keep my reason  ';form.requestSubmit();})()`);
  await until(`document.querySelector('[data-catalog-confirm-toast=reference]')?.matches(':popover-open')&&document.querySelector('[data-catalog-confirm-toast=reference] [role=alert]')?.textContent.includes('rejected')`);
  assert.ok(await evaluate(`document.querySelector('[data-catalog-confirm=reference] dialog').open&&document.querySelector('[data-catalog-confirm=reference] input').value==='  Keep my reason  '`));
  assert.ok(await evaluate(`!document.querySelector('[data-catalog-confirm=reference] form [role=alert]')`));
  await evaluate(`document.querySelector('[data-catalog-confirm=reference] form').requestSubmit()`);
  await until(`document.querySelector('[data-catalog-confirm-toast=reference]').dataset.sequence==='2'`);
  await evaluate(`document.querySelector('[data-catalog-confirm-toast=reference] [data-bml-ref=dismiss]').focus()`); await key('Enter');
  await until(`!document.querySelector('[data-catalog-confirm-toast=reference]').matches(':popover-open')`);
  assert.ok(await evaluate(`document.querySelector('[data-catalog-confirm=reference] dialog').open`));
  await key('Escape');
  await evaluate(`document.querySelector('[data-export=popover] summary').click()`);
  await until(`document.activeElement.name==='option-filter'`);
  await key('Escape');
  await until(`document.activeElement===document.querySelector('[data-export=popover] summary')`);

  await navigate('/components/feedback');
  await until(`document.querySelector('[data-catalog-notify=reference]')?.dataset.bmlActionBound==='true'`);
  await evaluate(`document.querySelector('[data-catalog-notify=reference]').click()`);
  await until(`document.querySelector('[data-catalog-toast=reference]').matches(':popover-open')`);
  assert.equal(await evaluate(`document.querySelector('[data-catalog-toast=reference]').dataset.sequence`), '1');
  await evaluate(`document.querySelector('[data-catalog-notify=reference]').click()`);
  await until(`document.querySelector('[data-catalog-toast=reference]').dataset.sequence==='2'`);

  await navigate('/components/content');
  await until(`document.querySelector('[data-catalog-json=reference] [data-json-ready]')&&document.querySelector('[data-source-ready]')&&document.querySelector('[data-catalog-comment=reference]')?.dataset.bmlActionBound==='true'`);
  const tree = 'document.querySelector("[data-export=tree] [role=tree]")';
  await until(`${tree}.querySelector('[data-path="src/main.kt"] a').tabIndex===0`);
  await evaluate(`${tree}.querySelector('[data-path=src] button').focus()`); await key('ArrowRight');
  assert.equal(await evaluate(`document.activeElement.closest('[role=treeitem]').dataset.path`), 'src/main.kt');
  await key('ArrowLeft'); await key('ArrowLeft');
  assert.ok(await evaluate(`${tree}.querySelector('[data-path=src]').getAttribute('aria-expanded')==='false'&&${tree}.querySelector('[data-path="src/main.kt"]').hidden`));
  const comment = 'document.querySelector("[data-catalog-comment=reference]")';
  await evaluate(`${comment}.querySelector('textarea').value='x';${comment}.querySelector('textarea').dispatchEvent(new Event('input',{bubbles:true}));${comment}.requestSubmit()`);
  await until(`${comment}.querySelector('textarea').getAttribute('aria-invalid')==='true'`);
  assert.equal(await evaluate(`${comment}.querySelector('textarea').value`), 'x');
  await evaluate(`${comment}.querySelector('textarea').value='A complete example comment';${comment}.querySelector('textarea').dispatchEvent(new Event('input',{bubbles:true}));${comment}.querySelector('textarea').focus()`);
  for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:'Enter',windowsVirtualKeyCode:13,modifiers:2});
  await until(`document.querySelector('[data-catalog-comment-toast=reference]').matches(':popover-open')&&document.querySelector('[data-catalog-comment-toast=reference] [role=status]').textContent==='Comment posted.'`);
  assert.ok(await evaluate(`!document.querySelector('[data-catalog-comment-result]')`));
  assert.ok(await evaluate(`document.querySelector('#readonly-reference').readOnly&&document.querySelector('#disabled-reference').disabled`));

  const json = 'document.querySelector("[data-catalog-json=reference] [data-bml-json-editor]")';
  await evaluate(`${json}.querySelector('button[title^="Switch to text"]').click()`);
  await until(`${json}.querySelector('.cm-content')`);
  const replaceJson = async text => {
    await evaluate(`${json}.querySelector('.cm-content').focus()`);
    for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:'a',windowsVirtualKeyCode:65,modifiers:await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2')});
    await call('Input.insertText',{text});
  };
  await replaceJson('{ broken ');
  await evaluate(`document.querySelector('[data-catalog-json=reference]').requestSubmit()`);
  await until(`${json}.dataset.error==='Enter valid JSON before saving.'`);
  assert.equal(await evaluate(`${json}.querySelector('textarea').value`), '{ broken ');
  await replaceJson('{"saved":true}');
  await evaluate(`document.querySelector('[data-catalog-json=reference]').requestSubmit()`);
  await until(`document.querySelector('[data-catalog-json-toast=reference]').matches(':popover-open')&&document.querySelector('[data-catalog-json-toast=reference] [role=status]').textContent==='Settings saved.'`);
  assert.ok(await evaluate(`!document.querySelector('[data-catalog-json-result]')`));
  assert.ok(await evaluate(`${json}.getAttribute('aria-invalid')==='false'&&document.querySelector('[data-name=readonly-settings] textarea').readOnly&&document.querySelector('[data-name=disabled-settings] textarea').disabled&&document.querySelector('[data-name=disabled-settings] textarea').value==='{}'`));
  const source = 'document.querySelector("[data-export=source-view] .reference-demo [data-source-ready]")';
  await evaluate(`window.catalogSource=${source}.querySelector('.cm-content').textContent;${source}.querySelector('.cm-content').focus()`);
  await call('Input.insertText',{text:'Cannot edit this'});
  assert.ok(await evaluate(`${source}.querySelector('.cm-content').textContent===window.catalogSource`));
  await evaluate(`${source}.querySelector('[data-bml-ref=wrap]').click()`);
  assert.equal(await evaluate(`${source}.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')`),'true');

  for (const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    for (const path of ['/components',...routes,'/components/aliases']) {
      await navigate(path);
      if (path.endsWith('/content')) await until(`document.querySelector('[data-json-ready]')&&document.querySelector('[data-source-ready]')`);
      await until('document.documentElement.scrollWidth<=innerWidth');
      assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`),'/components');
    }
  }
  assert.ok(await evaluate(`(() => {const a=document.querySelector('[data-catalog-unprefixed]'),b=document.querySelector('[data-catalog-aliased]');return a.getAttribute('data-bml-c')===b.getAttribute('data-bml-c')&&getComputedStyle(a).backgroundColor===getComputedStyle(b).backgroundColor;})()`));
  await navigate('/components/content');
  await until(`document.querySelector('[data-json-ready]')&&document.querySelector('[data-source-ready]')`);
  await evaluate(`document.querySelector('[data-export=json-editor]').scrollIntoView({block:'start'})`);
  const screenshot = await call('Page.captureScreenshot',{format:'png'});
  await writeFile(join(artifacts,`catalog-${mode}-references-phone.png`),Buffer.from(screenshot.data,'base64'));

  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await navigate('/components/content');
  assert.ok(await evaluate(`(() => {const json=document.querySelector('[data-catalog-json=reference] textarea');return !json.hidden&&json.name==='settings'&&json.value==='{"saved":true}'&&!document.querySelector('.jse-main')&&document.querySelector('[data-export=source-view] pre').textContent.includes('Design library')&&!document.querySelector('.cm-editor');})()`));
  await navigate('/components/overlays');
  await evaluate(`document.querySelector('[data-export=popover] summary').click()`);
  assert.ok(await evaluate(`document.querySelector('[data-export=popover] details').open`));
  await call('Emulation.setScriptExecutionDisabled',{value:false});
}
