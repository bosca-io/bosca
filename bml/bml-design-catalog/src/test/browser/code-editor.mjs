import assert from 'node:assert/strict';

export async function checkCodeEditor({ navigate, evaluate, until, call }) {
  await navigate('/components/content');
  const root = "document.querySelector('[data-code-example]')";
  const field = `${root}.querySelector('textarea[name=expression]')`;
  const editor = `${root}.querySelector('.cm-content')`;
  const button = text => `${root}.querySelectorAll('button')`;
  const press = async text => { await evaluate(`[...${button(text)}].find(button=>button.textContent.trim()===${JSON.stringify(text)}).click()`); };
  const fill = async text => {
    await evaluate(`${editor}.focus()`);
    await call('Input.dispatchKeyEvent', { type:'keyDown', key:'a', code:'KeyA', modifiers:process.platform==='darwin'?4:2, commands:['selectAll'] });
    await call('Input.dispatchKeyEvent', { type:'keyUp', key:'a', code:'KeyA' });
    await call('Input.insertText', { text });
  };
  await until(`${root}.querySelector('[data-code-ready]')`);
  assert.ok(await evaluate("[...document.querySelectorAll('[data-bml-code-editor]')].every(editor=>!editor.hasAttribute('data-bml-source-view'))"),'Editable fields retain a separate component identity from read-only viewers');
  const initial = await evaluate(`${field}.value`);
  await fill('profile.name & " draft"');
  assert.equal(await evaluate(`${field}.value`),'profile.name & " draft"');
  await press('Refresh editor'); await until(`${root}.innerText.includes('Refreshes: 1')`);
  assert.equal(await evaluate(`${field}.value`),'profile.name & " draft"');
  await evaluate(`${editor}.focus()`);
  const historyKey = async modifiers => {
    await call('Input.dispatchKeyEvent', { type:'keyDown', key:'z', code:'KeyZ', windowsVirtualKeyCode:90, modifiers });
    await call('Input.dispatchKeyEvent', { type:'keyUp', key:'z', code:'KeyZ', windowsVirtualKeyCode:90 });
  };
  await historyKey(process.platform==='darwin'?4:2);
  assert.equal(await evaluate(`${field}.value`),initial,'Undo history survives surrounding server updates');
  await historyKey(process.platform==='darwin'?12:10);
  assert.equal(await evaluate(`${field}.value`),'profile.name & " draft"','Redo restores the native draft');
  await press('Toggle read-only'); await until(`${editor}.getAttribute('aria-readonly')==='true'`);
  await fill('must not replace'); assert.equal(await evaluate(`${field}.value`),'profile.name & " draft"');
  await press('Toggle read-only'); await until(`${editor}.getAttribute('contenteditable')==='true'`);
  await press('Save expression'); await until(`${root}.querySelector('[data-code-saved]').textContent==='profile.name & " draft"'`);
  await fill('unsaved'); await press('Reset expression');
  await until(`${field}.value==='profile.name & " draft"'`);
  await press('Replace expression'); await until(`${field}.value==='profile.id'`);
  await evaluate(`${editor}.focus()`);
  await call('Input.dispatchKeyEvent', { type:'keyDown', key:'Tab', code:'Tab', windowsVirtualKeyCode:9 });
  await call('Input.dispatchKeyEvent', { type:'keyUp', key:'Tab', code:'Tab', windowsVirtualKeyCode:9 });
  assert.notEqual(await evaluate(`${field}.value`),'profile.id','Tab indents through the shared editor');
  await call('Input.dispatchKeyEvent', { type:'keyDown', key:'Escape', code:'Escape', windowsVirtualKeyCode:27 });
  await call('Input.dispatchKeyEvent', { type:'keyUp', key:'Escape', code:'Escape', windowsVirtualKeyCode:27 });
  await call('Input.dispatchKeyEvent', { type:'keyDown', key:'Tab', code:'Tab', windowsVirtualKeyCode:9 });
  await call('Input.dispatchKeyEvent', { type:'keyUp', key:'Tab', code:'Tab', windowsVirtualKeyCode:9 });
  assert.ok(await evaluate(`!${root}.querySelector('[data-bml-code-editor]').contains(document.activeElement)`),'Escape then Tab returns to native focus navigation');
  await fill('val example = "draft"\nprintln(example)');
  await evaluate(`(() => { const root=${root}.querySelector('[data-bml-code-editor]'); window.retainedCodeEditor=root.querySelector('.cm-editor'); root.dataset.language=''; root.dataset.path='example.kt'; root.dataset.line='2'; root.removeAttribute('data-wrapped'); })()`);
  await until(`${editor}.getAttribute('aria-label')==='Mapping expression for example.kt' && ${root}.querySelector('.source-keyword')?.textContent==='val'`);
  assert.ok(await evaluate(`${root}.querySelector('.cm-editor')===window.retainedCodeEditor && !${editor}.classList.contains('cm-lineWrapping')`),'Changing path and wrapping preserves the editor instance');
  assert.equal(await evaluate(`${field}.value`),'val example = "draft"\nprintln(example)','Changing grammar preserves the native draft');
  await evaluate(`${root}.querySelector('[data-bml-code-editor]').setAttribute('data-wrapped','')`);
  await until(`${editor}.classList.contains('cm-lineWrapping')`);
  await evaluate('delete window.retainedCodeEditor');
  await press('Toggle disabled'); await until(`${field}.disabled`);
  assert.equal(await evaluate(`new FormData(${field}.form).has('expression')`),false);
  await press('Toggle disabled'); await until(`!${field}.disabled`);
  for (const width of [1920,1024,768,390]) {
    await call('Emulation.setDeviceMetricsOverride', { width,height:1000,deviceScaleFactor:1,mobile:false });
    assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'));
  }
}
