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
      const chart = `${root}.querySelector('[data-bml-chart]')`;
      await evaluate(`window.consumerChartConfig=${chart}.dataset.config;window.consumerCanvas=${chart}.querySelector('canvas');${chart}.dataset.config=JSON.stringify({animation:false,geo:{map:'world',roam:true},series:[{type:'scatter',coordinateSystem:'geo',name:'Consumer locations',data:[{name:'Visitor',value:[12,34]}]}]})`);
      await until(`${chart}.querySelectorAll('tbody tr').length===1&&!${chart}.querySelector('[data-bml-ref=surface]').hidden&&!${chart}.querySelector('[data-bml-ref=mapControls]').hidden`);
      assert.ok(await evaluate(`JSON.parse(${chart}.dataset.maps).world.features.length>100&&${chart}.querySelector('canvas')===window.consumerCanvas`),'The compiled consumer loads shared geometry from the library and reuses its renderer');
      await evaluate(`${chart}.scrollIntoView({block:'center'});window.consumerMapInitial=${chart}.querySelector('canvas').toDataURL();${chart}.querySelector('[data-bml-ref=mapZoomIn]').click()`);
      await until(`${chart}.querySelector('canvas').toDataURL()!==window.consumerMapInitial`);
      await evaluate(`(()=>{const option=JSON.parse(${chart}.dataset.config);option.series[0].data[0].value=[13,35];${chart}.dataset.config=JSON.stringify(option)})()`);
      await until(`${chart}.querySelector('tbody tr td:nth-of-type(2)')?.textContent==='13, 35'`);
      await evaluate(`${chart}.dataset.config=window.consumerChartConfig`);
      await until(`${chart}.querySelectorAll('tbody tr').length===2&&${chart}.querySelector('[data-bml-ref=mapControls]').hidden`);
      assert.ok(await evaluate(`${chart}.querySelector('canvas')===window.consumerCanvas`),'Replacing native map options with bars removes map controls on the retained renderer');
      const values = () => evaluate(`Object.fromEntries(new FormData(${form}))`);
      const initialDocument = JSON.parse((await values()).document);
      assert.deepEqual(Object.keys(initialDocument).sort(), ['attrs','content','type']);
      assert.equal(initialDocument.content[0].content[0].text, 'Consumer document');
      assert.ok(!(await values()).document.includes('Consumer article'), 'Only the record body reaches the native field');
      assert.deepEqual(initialDocument.content.filter(node=>node.type==='heading').map(node=>node.attrs.level), [1,2]);
      const container = initialDocument.content.find(node=>node.type==='container');
      assert.deepEqual(container.attrs.references, ['GEN.1']);
      assert.equal(container.attrs.metadataId, '00000000-0000-4000-8000-000000000004');
      assert.equal(container.attrs.renderer, 'card');
      assert.equal(container.content[0].content[1].attrs.mentionSuggestionChar, '@');
      assert.equal(initialDocument.content.find(node=>node.type==='bible').attrs.metadataId, 'scripture-content');
      assert.equal(initialDocument.content.find(node=>node.type==='codeBlock').attrs.language, 'typescript');
      assert.equal(await evaluate(`${root}.querySelector('[data-document-container-view] strong').textContent`), 'Main content');
      await until(`${root}.querySelector('[data-document-container=RELATED] .document-reference-selected')?.textContent.includes('Consumer resource')&&${root}.querySelector('[data-document-container=PASSAGE] .document-scripture-attribution')?.textContent.includes('Independent consumer fixture')`);
      assert.equal(await evaluate(`${root}.querySelector('[data-document-container=PASSAGE] .scripture-verse').textContent`), '1');
      assert.equal(await evaluate(`${root}.querySelector('[data-document-container=PASSAGE] .document-scripture-content span[style*=italic]')?.textContent`), 'Consumer example passage.');
      const collapsed = await evaluate(`new FormData(${form}).get('document')`);
      await evaluate(`${root}.querySelector('[data-document-container-view] [aria-expanded]').click()`);
      assert.equal(await evaluate(`${root}.querySelector('.document-container-content').hidden`), true);
      assert.equal(await evaluate(`new FormData(${form}).get('document')`), collapsed);
      await evaluate(`${root}.querySelector('[data-document-container-view] [aria-expanded]').click()`);
      await evaluate(`${form}.requestSubmit()`);
      await until(`${root}.querySelector('[data-widget-save]').textContent.includes('Save 1')`);
      assert.deepEqual(JSON.parse(await evaluate(`${root}.querySelector('[data-saved-document]').textContent`)), initialDocument,
        'The independent consumer saves all stored custom attributes through the native form');
      const sibling = `document.querySelector('[data-widget-form=${id==='light'?'purple':'light'}]')`;
      const siblingBefore = await evaluate(`Object.fromEntries(new FormData(${sibling}))`);
      const paragraph = await evaluate(`(()=>{const element=${editor}.querySelector('p');element.scrollIntoView({block:'center'});const bounds=element.getBoundingClientRect();return {x:bounds.left+8,y:bounds.top+bounds.height/2};})()`);
      await call('Input.dispatchMouseEvent', {type:'mousePressed',...paragraph,button:'left',clickCount:1});
      await call('Input.dispatchMouseEvent', {type:'mouseReleased',...paragraph,button:'left',clickCount:1});
      await key('a', await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
      const draft = `Draft ${id} <strong>stays text</strong>`;
      await call('Input.insertText', { text: draft });
      await until(`new FormData(${form}).get('document').includes(${JSON.stringify(draft)})&&${editor}.textContent===${JSON.stringify(draft)}`);
      assert.equal(await evaluate(`${editor}.textContent`), draft);
      assert.equal(await evaluate(`${editor}.querySelector('strong')`), null);
      await call('Input.insertText', { text: ' @rev' });
      await until(`${root}.querySelectorAll('[role=option]').length===2`);
      await key('Enter');
      const mentioned = JSON.parse((await values()).document).content[0].content.find(node => node.type === 'mention');
      assert.equal(mentioned.attrs.id, 'review-notes'); assert.equal(mentioned.attrs.label, 'Review notes');
      assert.equal(mentioned.attrs.entityType, 'metadata'); assert.equal(mentioned.attrs.mentionSuggestionChar, '@');
      const originalGraph = JSON.parse((await values()).graph);
      await evaluate(`${flow}.scrollIntoView({block:'center',behavior:'instant'})`);
      await until(`(() => {const node=${flow}.querySelector('.vue-flow__node[data-id=start]');
        return node.tabIndex>=0&&getComputedStyle(node).visibility==='visible'&&node.getBoundingClientRect().width>0;})()`);
      await evaluate(`${flow}.querySelector('.vue-flow__node[data-id=start]').focus()`);
      assert.ok(await evaluate(`document.activeElement===${flow}.querySelector('.vue-flow__node[data-id=start]')`),
        'The visible flow node accepts keyboard focus before selection and movement');
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
      assert.equal(JSON.parse(retainedValues.document).content[0].content[0].text, draft + ' ');
      assert.equal(retainedValues.note, 'Retained note');
      await evaluate(`${form}.requestSubmit()`);
      await until(`${root}.querySelector('[data-widget-save]').textContent.includes('Save 2')`);
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
      const picker = `${root}.querySelector('[data-image-picker-ready]')`;
      const panel = `${picker}.querySelector('dialog')`;
      await until(`${picker}`);
      await evaluate(`${root}.querySelector('[data-document-command=image]').click()`); await until(`${panel}.open`);
      await evaluate(`${picker}.querySelector('.select-trigger').click()`);
      await until(`${picker}.querySelector('[role=option]')?.textContent==='Illustrative image'`);
      const option = await evaluate(`(()=>{const element=${picker}.querySelector('[role=option]');element.scrollIntoView({block:'center'});const rect=element.getBoundingClientRect();return {x:rect.left+rect.width/2,y:rect.top+rect.height/2};})()`);
      for (const type of ['mousePressed', 'mouseReleased']) await call('Input.dispatchMouseEvent', {type,...option,button:'left',clickCount:1});
      await until(`!${picker}.querySelector('[data-bml-ref=imageApply]').disabled`);
      await evaluate(`${picker}.querySelector('[data-bml-ref=imageAlt]').value='Consumer image description';${picker}.querySelector('[data-bml-ref=imageApply]').click()`);
      await until(`!${panel}.open&&${root}.querySelector('[data-document-image] img')?.naturalWidth===160`);
      const image = JSON.parse((await values()).document).content.find(node=>node.type==='image');
      assert.equal(image.attrs.metadataId, 'example-image'); assert.equal(image.attrs.alt, 'Consumer image description');
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
