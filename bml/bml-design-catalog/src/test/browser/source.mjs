import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Exercises caller-owned source changes through the compiled viewer, without accessing editor internals. */
export async function checkSources({ navigate, evaluate, until: poll, key, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const root = id => `document.querySelector('[data-catalog-source=${id}]')`;
  const content = id => `${root(id)}.querySelector('.cm-content')`;
  const action = async (id, name, done) => {
    await evaluate(`document.querySelector('[data-source-${name}=${id}]').click()`);
    await until(done);
  };
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await navigate('/source');
  await until(`document.querySelectorAll('[data-source-ready]').length===8&&document.querySelector('[data-source-refresh=dark]')?.dataset.bmlActionBound==='true'`);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Design library"] a[aria-current=page]').getAttribute('href')`), '/source');
  assert.ok(await evaluate(`!window.catalogSourceExecuted&&!document.querySelector('[data-source-markup] script')&&document.querySelector('[data-source-markup] textarea').value.includes('<script>window.catalogSourceExecuted = true;</script>')`));
  assert.deepEqual(await evaluate(`['short','medium','long','compact-bare'].map(name=>document.querySelector('[data-source-'+name+']').getBoundingClientRect().height)`), [110, 180, 240, 110]);
  assert.ok(await evaluate(`document.querySelector('[data-source-compact-bare] .toolbar')===null&&document.querySelector('[data-source-markup] .toolbar')===null&&document.querySelector('[data-source-markup] .cm-lineWrapping')!==null&&document.querySelector('[data-source-empty] textarea').value===''&&document.querySelector('[data-source-empty] .cm-content').getAttribute('aria-label')==='Empty source'`));
  await evaluate(`${content('light')}.focus()`);
  for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', { type, key: 'f', windowsVirtualKeyCode: 70,
    modifiers: await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2') });
  await until(`${root('light')}.querySelector('.cm-search input[name=search]')===document.activeElement`);
  for (const character of 'row40') for (const type of ['keyDown', 'keyUp']) {
    await call('Input.dispatchKeyEvent', { type, key: character, windowsVirtualKeyCode: character.toUpperCase().charCodeAt(0),
      ...(type === 'keyDown' ? { text: character } : {}) });
  }
  await key('Enter');
  try { await until(`${root('light')}.querySelector('.cm-searchMatch')?.textContent==='row40'`); }
  catch (error) {
    const search=await evaluate(`(() => {const root=${root('light')};return {query:root.querySelector('input[name=search]').value,matches:[...root.querySelectorAll('[class*=searchMatch]')].map(el=>({class:el.className,text:el.textContent})),active:root.querySelector('.cm-activeLine')?.textContent,scroll:root.querySelector('.cm-scroller').scrollTop};})()`);
    throw new Error(JSON.stringify(search), {cause:error});
  }
  await key('Escape'); await until(`!${root('light')}.querySelector('.cm-search')`);
  await evaluate(`document.querySelector('[data-catalog-source-owner=dark] details').open=true;document.querySelector('[data-catalog-source-owner=light] details').open=true`);
  await action('dark', 'line', `${root('dark')}.dataset.line==='40'&&${root('dark')}.querySelector('.cm-activeLine')?.textContent.includes('row40')`);
  assert.equal(await evaluate(`${root('light')}.dataset.line`), '1');
  assert.ok(await evaluate(`document.querySelector('[data-catalog-source-owner=dark] details').open`), 'Source options remain open after an action');
  try { await until(`${root('dark')}.querySelector('.cm-scroller').scrollTop>100`); }
  catch (error) {
    const geometry=await evaluate(`(() => {const root=${root('dark')};return [root,root.querySelector('.code-editor'),root.querySelector('.cm-editor'),root.querySelector('.cm-scroller')].map(el=>({class:el.className,height:el.getBoundingClientRect().height,client:el.clientHeight,scroll:el.scrollHeight,top:el.scrollTop,flex:getComputedStyle(el).flex}));})()`);
    throw new Error(JSON.stringify(geometry), {cause:error});
  }
  await evaluate(`${content('dark')}.focus()`);
  const before = await evaluate(`${root('dark')}.querySelector('textarea').value`);
  await call('Input.insertText', { text: 'Cannot edit this source' });
  await key('Backspace');
  assert.equal(await evaluate(`${root('dark')}.querySelector('textarea').value`), before);
  assert.ok(await evaluate(`!${content('dark')}.textContent.includes('Cannot edit this source')&&${content('dark')}.getAttribute('aria-readonly')==='true'&&!${content('dark')}.isContentEditable`));

  // Local wrapping and a requested-line selection survive a surrounding server action.
  await evaluate(`${root('dark')}.querySelector('[data-bml-ref=wrap]').click()`);
  await until(`${root('dark')}.querySelector('.cm-lineWrapping')`);
  await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))`);
  await evaluate(`window.catalogRetainedSource=${root('dark')};window.catalogRetainedContent=${content('dark')};window.catalogSourceScroll=${root('dark')}.querySelector('.cm-scroller').scrollTop;${content('dark')}.focus()`);
  await action('dark', 'refresh', `document.querySelector('[data-source-toast=dark]').dataset.sequence==='1'&&document.querySelector('[data-source-toast=dark]').matches(':popover-open')`);
  assert.ok(await evaluate(`${root('dark')}===window.catalogRetainedSource&&${content('dark')}===window.catalogRetainedContent&&document.activeElement===window.catalogRetainedContent&&${root('dark')}.querySelector('.cm-activeLine').textContent.includes('row40')&&${root('dark')}.querySelector('.cm-lineWrapping')!==null&&Math.abs(${root('dark')}.querySelector('.cm-scroller').scrollTop-window.catalogSourceScroll)<2`));
  assert.ok(await evaluate(`document.querySelector('[data-source-toast=light]').dataset.sequence==='0'&&!document.querySelector('[data-source-toast=light]').matches(':popover-open')&&!${root('light')}.querySelector('.cm-lineWrapping')`));
  await evaluate(`document.querySelector('[data-source-toast=dark] [data-bml-ref=dismiss]').click()`);
  await action('dark', 'plain', `${root('dark')}.dataset.language==='text'&&!${root('dark')}.querySelector('.source-keyword')`);
  assert.ok(await evaluate(`${root('dark')}===window.catalogRetainedSource&&!document.querySelector('[data-source-toast=dark]').matches(':popover-open')&&${root('light')}.querySelector('.source-keyword')!==null`));
  await action('dark', 'plain', `${root('dark')}.dataset.language===''&&${root('dark')}.querySelector('.source-keyword')`);
  await action('dark', 'wrap', `${root('dark')}.hasAttribute('data-wrapped')`);
  await action('dark', 'wrap', `!${root('dark')}.hasAttribute('data-wrapped')&&!${root('dark')}.querySelector('.cm-lineWrapping')`);

  await action('dark', 'replace', `${root('dark')}?.hasAttribute('data-source-ready')&&${content('dark')}.textContent.includes('component =')`);
  assert.ok(await evaluate(`${root('dark')}!==window.catalogRetainedSource&&!window.catalogRetainedSource.isConnected&&${root('dark')}.querySelector('textarea').value.includes('Replacement 1')&&document.querySelector('[data-source-line=dark]').disabled&&${root('light')}.querySelector('textarea').value.includes('row40')`));
  for (let cycle = 0; cycle < 2; cycle++) {
    await action('dark', 'visible', `!${root('dark')}`);
    assert.equal(await evaluate(`document.querySelectorAll('[data-source-ready]').length`), 7);
    await action('dark', 'visible', `${root('dark')}?.hasAttribute('data-source-ready')`);
    assert.equal(await evaluate(`document.querySelectorAll('[data-source-ready]').length`), 8);
    assert.equal(await evaluate(`${root('dark')}.querySelectorAll('.cm-editor').length`), 1);
  }
  await evaluate(`document.querySelector('[data-source-empty] .cm-content').focus()`); await key('Escape'); await key('Tab');
  assert.ok(await evaluate(`!document.querySelector('[data-source-empty]').contains(document.activeElement)`));
  await evaluate(`${content('light')}.focus()`); await key('Escape'); await key('Tab');
  assert.ok(await evaluate(`!${root('light')}.contains(document.activeElement)`));

  const measurements = [];
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click();document.fonts.ready`);
    await until(`document.documentElement.scrollWidth<=innerWidth`);
    assert.deepEqual(await evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-source='+id+'] .cm-editor')).backgroundColor)`), ['rgb(16, 19, 28)', 'rgb(255, 255, 255)']);
    const samples = await evaluate(`(() => {
      const luminance = color => color.match(/[0-9.]+/g).slice(0,3).map(Number).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
      const background=el=>{const color=getComputedStyle(el).backgroundColor,parts=color.match(/[0-9.]+/g).map(Number);return parts.length===3||parts[3]>0?color:background(el.parentElement);};
      return ['dark','light'].map(id=>{const root=document.querySelector('[data-catalog-source='+id+']');
        const samples=[...root.querySelectorAll('.source-comment,.source-keyword,.source-string,.source-constant,.source-definition,.source-operator,.source-name,.cm-gutters')].map(el=>{const l=[luminance(background(el)),luminance(getComputedStyle(el).color)].sort((a,b)=>a-b);return {class:el.className,ratio:(l[1]+.05)/(l[0]+.05)};});
        const stack=root.closest('[data-catalog-source-owner]'),children=[...stack.children].filter(el=>el.checkVisibility()&&el.getBoundingClientRect().height>0);return {id,samples,gaps:children.slice(1).map((el,i)=>el.getBoundingClientRect().top-children[i].getBoundingClientRect().bottom)};});
    })()`);
    for (const group of samples) {
      assert.ok(group.samples.length > 0);
      for (const sample of group.samples) assert.ok(sample.ratio >= 4.5, `${width} ${group.id}: ${JSON.stringify(sample)}`);
      assert.ok(group.gaps.every(gap => gap >= 15.5), JSON.stringify(group.gaps));
    }
    measurements.push({ width, groups: samples });
    await evaluate(`document.activeElement.blur();document.querySelectorAll('[data-catalog-source-owner] details').forEach(details=>{details.open=false;});window.scrollTo(0,0)`);
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `catalog-${mode}-source-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
  await writeFile(join(artifacts, `catalog-${mode}-source.json`), JSON.stringify(measurements, null, 2));

  await call('Emulation.setScriptExecutionDisabled', { value: true });
  await navigate('/source');
  assert.ok(await evaluate(`!document.querySelector('.cm-editor')&&!window.catalogSourceExecuted&&document.querySelector('[data-source-markup] pre').textContent.includes('<script>window.catalogSourceExecuted = true;</script>')&&document.querySelector('[data-source-markup] pre').tabIndex===0&&getComputedStyle(document.querySelector('[data-source-markup] pre')).whiteSpace==='pre-wrap'&&document.querySelector('[data-source-empty] pre').textContent===''`));
  assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth`));
  await call('Emulation.setScriptExecutionDisabled', { value: false });
}
