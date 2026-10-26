import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** All optional engines share a real consumer form and survive its server patches. */
export async function checkWidgets({ call, evaluate, until, base, mode, artifacts }) {
  const reports = [];
  const key = async (key, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', {
      type, key, modifiers, windowsVirtualKeyCode: { a: 65, Enter: 13, ArrowRight: 39, ArrowDown: 40 }[key],
    });
  };
  for (const width of [1440, 390]) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await call('Page.navigate', { url: base + '/widgets' });
    await until(`document.querySelectorAll('[data-document-ready]').length===2&&document.querySelectorAll('[data-flow-ready] .vue-flow__node').length===4&&document.querySelectorAll('[data-chart-ready]').length===2&&document.querySelectorAll('[data-grid-ready]').length===2&&[...document.querySelectorAll('[data-widget-form]')].every(form=>form.dataset.bmlActionBound==='true')`);
    for (const id of ['light', 'purple']) {
      const root = `document.querySelector('[data-widgets=${id}]')`;
      const form = `${root}.querySelector('form')`;
      const editor = `${root}.querySelector('.tiptap')`;
      const flow = `${root}.querySelector('[data-bml-flow-editor]')`;
      const grid = `${root}.querySelector('[data-bml-dashboard-grid]')`;
      const values = () => evaluate(`Object.fromEntries(new FormData(${form}))`);
      const initialDocument = JSON.parse((await values()).document);
      assert.deepEqual(Object.keys(initialDocument).sort(), ['content','type']);
      assert.equal(initialDocument.content[0].content[0].text, 'Consumer document');
      assert.ok(!(await values()).document.includes('Consumer article'), 'Only the record body reaches the native field');
      const sibling = `document.querySelector('[data-widget-form=${id==='light'?'purple':'light'}]')`;
      const siblingBefore = await evaluate(`Object.fromEntries(new FormData(${sibling}))`);
      await evaluate(`${editor}.focus()`);
      await key('a', await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
      const draft = `Draft ${id} <strong>stays text</strong>`;
      await call('Input.insertText', { text: draft });
      assert.equal(await evaluate(`${editor}.textContent`), draft);
      assert.equal(await evaluate(`${editor}.querySelector('strong')`), null);
      const originalGraph = JSON.parse((await values()).graph);
      await evaluate(`${flow}.querySelector('.vue-flow__node[data-id=start]').focus()`);
      await key('Enter');
      await key('ArrowRight');
      await until(`JSON.parse(${flow}.querySelector('textarea').value).nodes.find(node=>node.id==='start').position.x!==${originalGraph.nodes[0].position.x}`);
      const originalLayout = (await values()).layout;
      assert.equal(await evaluate(`${grid}.hasAttribute('data-stacked')`), width===390);
      if (width===1440) {
        await evaluate(`${grid}.querySelector('[data-dashboard-item=notes] [data-dashboard-handle=move]').focus()`);
        await key('ArrowDown');
        assert.notEqual((await values()).layout, originalLayout);
      }
      await evaluate(`window.widgetNodes=[${editor},${flow}.querySelector('.vue-flow'),${grid},${grid}.querySelector('canvas'),${grid}.querySelector('input')];window.widgetNodes[4].value='Retained note';${root}.querySelector('[data-widget-refresh]').click()`);
      await until(`${root}.querySelector('[data-widget-refresh]').textContent.includes('Refresh 1')`);
      const retainedValues = await values();
      assert.ok(await evaluate(`window.widgetNodes.every((node,index)=>node===[${editor},${flow}.querySelector('.vue-flow'),${grid},${grid}.querySelector('canvas'),${grid}.querySelector('input')][index])`), 'Consumer refresh retains engines and projected input');
      assert.equal(JSON.parse(retainedValues.document).content[0].content[0].text, draft);
      assert.equal(retainedValues.note, 'Retained note');
      await evaluate(`${form}.requestSubmit()`);
      await until(`${root}.querySelector('[data-widget-save]').textContent.includes('Save 1')`);
      for (const name of ['document', 'graph', 'layout']) {
        const submitted = JSON.parse(retainedValues[name]);
        // VueFlow's initialization flag is local to mounted nodes.
        if (name === 'graph') for (const node of submitted.nodes) delete node.initialized;
        assert.deepEqual(JSON.parse(await evaluate(`${root}.querySelector('[data-saved-${name}]').textContent`)), submitted, `Server saves current ${name} data`);
      }
      assert.deepEqual(await evaluate(`Object.fromEntries(new FormData(${sibling}))`), siblingBefore, 'Consumer instances remain independent');
      assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&${editor}.getBoundingClientRect().height>=180&&getComputedStyle(${grid}.querySelector('.dashboard-cells')).gap==='16px'`));
      assert.equal(await evaluate(`getComputedStyle(${editor}).backgroundColor`), id==='light'?'rgb(255, 255, 255)':'rgb(16, 19, 28)');
      if (width===390) assert.equal((await values()).layout, originalLayout, 'Responsive stacking leaves saved coordinates unchanged');
      await evaluate(`${grid}.scrollIntoView({block:'center'})`);
      const shot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `widgets-${mode}-${width}-${id}.png`), Buffer.from(shot.data, 'base64'));
      reports.push({ width, palette: id, nativeForm: true, savedCurrentValues: true, retainedEngines: true, stacked: width===390 });
    }
  }
  await call('Network.clearBrowserCookies');
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  try {
    await call('Page.navigate', { url: base + '/widgets' });
    await until(`document.readyState==='complete'&&document.querySelectorAll('[data-widget-form]').length===2`);
    assert.ok(await evaluate(`[...document.querySelectorAll('[data-widget-form]')].every(form=>{
      const data=new FormData(form);return ['document','graph','layout'].every(name=>{const field=form.querySelector('textarea[name='+name+']');return !field.hidden&&data.get(name)===field.value&&JSON.parse(field.value);})&&form.querySelectorAll('tbody tr').length===2&&form.textContent.includes('Visits');})`), 'Script-disabled consumers retain native JSON values and a chart data table');
  } finally { await call('Emulation.setScriptExecutionDisabled', { value: false }); }
  await writeFile(join(artifacts, `widgets-${mode}.json`), JSON.stringify(reports, null, 2));
}
