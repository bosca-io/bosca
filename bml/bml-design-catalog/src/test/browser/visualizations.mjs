import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Real VueFlow gestures and Chart.js canvas rendering in ordinary BML consumers. */
export async function checkVisualizations({ navigate, evaluate, until: poll, call, key, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`), reports = [];
  for (const width of [1440, 390]) for (const id of ['dark', 'light']) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await navigate('/flows');
    await until(`document.querySelectorAll('[data-flow-ready] .vue-flow__node').length===6&&document.querySelector('[data-flow-form=${id}]')?.dataset.bmlActionBound==='true'`);
    const example = `document.querySelector('[data-catalog-flow=${id}]')`;
    const root = `${example}.querySelector('[data-bml-flow-editor]')`, field = `${root}.querySelector('textarea')`;
    const form = `document.querySelector('[data-flow-form=${id}]')`;
    const node = `${root}.querySelector('.vue-flow__node[data-id=source]')`;
    const action = ref => `${root}.querySelector('[data-bml-ref=${ref}]')`;
    const state = suffix => `${example}.querySelector('[data-flow-${suffix}]')`;
    const originalSibling = await evaluate(`document.querySelector('[data-catalog-flow=${id==='dark'?'light':'dark'}] textarea').value`);
    const flowScreenshot = await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-flows-${width}-${id}.png`),Buffer.from(flowScreenshot.data,'base64'));
    const before = await evaluate(`JSON.parse(${field}.value)`);
    assert.ok(await evaluate(`(() => {const n=${node},text=n.querySelector('strong');return parseFloat(getComputedStyle(text).fontSize)*n.getBoundingClientRect().width/n.offsetWidth>=11;})()`), 'Initial graph labels remain readable');
    await evaluate(`${node}.scrollIntoView({block:'center'})`);
    const box = await evaluate(`(() => {const r=${node}.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await call('Input.dispatchMouseEvent', { type: 'mouseMoved', ...box });
    await call('Input.dispatchMouseEvent', { type: 'mousePressed', ...box, button: 'left', clickCount: 1 });
    for (let step = 1; step <= 5; step++) await call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x + step * 8, y: box.y + step * 4, button: 'left', buttons: 1 });
    await call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x + 40, y: box.y + 20, button: 'left', clickCount: 1 });
    await until(`JSON.parse(${field}.value).nodes.find(node=>node.id==='source').position.x!==${before.nodes[0].position.x}`);
    // Wait for the native release gesture to finish before the next control action.
    await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    assert.equal(await evaluate(`JSON.parse(${field}.value).nodes[0].data.payload.customValue`), 'retained');
    const oldZoom = await evaluate(`JSON.parse(${field}.value).viewport.zoom`);
    await evaluate(`${action('zoomIn')}.click()`);
    await until(`JSON.parse(${field}.value).viewport?.zoom!==${oldZoom}`);
    await evaluate(`window.flowRoot=${root};window.flowCanvas=${root}.querySelector('.vue-flow');window.flowTransform=${root}.querySelector('.vue-flow__transformationpane').style.transform;window.flowDraft=${field}.value;${example}.querySelector('[data-example-states]').open=true;${state('refresh')}.click()`);
    await until(`document.querySelector('[data-flow-toast=${id}]')?.matches(':popover-open')`);
    assert.ok(await evaluate(`${root}===window.flowRoot&&${root}.querySelector('.vue-flow')===window.flowCanvas&&${root}.querySelector('.vue-flow__transformationpane').style.transform===window.flowTransform&&${field}.value===window.flowDraft`));
    await evaluate(`${root}.querySelector('details').open=true;${action('source')}.value=JSON.stringify({node:'process',handle:'result'});${action('target')}.value=JSON.stringify({node:'output',handle:'result'});${state('connections')}.click()`);
    await until(`${state('connections')}.hasAttribute('checked')`);
    await evaluate(`${action('connect')}.click()`);
    assert.equal(await evaluate(`JSON.parse(${field}.value).edges.length`), 1, 'Consumer cancellation must reject the connection');
    await evaluate(`${state('connections')}.click()`); await until(`!${state('connections')}.hasAttribute('checked')`);
    await evaluate(`${action('connect')}.click()`); await until(`JSON.parse(${field}.value).edges.length===2`);
    assert.ok(await evaluate(`JSON.parse(${field}.value).edges.some(edge=>edge.source==='process'&&edge.sourceHandle==='result'&&edge.target==='output'&&edge.targetHandle==='result')`));
    await evaluate(`${state('reject')}.click()`); await until(`${state('reject')}.hasAttribute('checked')`);
    await evaluate(`window.rejectedFlow=${field}.value;(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseFlowSave=resolve);}return original(...args);};${form}.requestSubmit();})()`);
    await until(`${form}.hasAttribute('data-bml-action-pending')&&typeof window.releaseFlowSave==='function'`);
    assert.ok(await evaluate(`${field}.readOnly&&${action('connect')}.disabled&&${action('remove')}.disabled`));
    await evaluate('window.releaseFlowSave()');
    await until(`${root}.querySelector('[data-bml-ref=feedback]').textContent.includes('rejected')`);
    assert.equal(await evaluate(`${field}.value`), await evaluate('window.rejectedFlow'));
    await evaluate(`${state('readonly')}.click()`); await until(`${root}.hasAttribute('data-readonly')`);
    assert.ok(await evaluate(`${field}.readOnly&&!${field}.disabled&&new FormData(${form}).has('graph')&&${action('connect')}.disabled&&${action('remove')}.hidden`));
    await evaluate(`${state('disabled')}.click()`); await until(`${root}.hasAttribute('data-disabled')`);
    assert.ok(await evaluate(`${field}.disabled&&!new FormData(${form}).has('graph')&&${action('fit')}.disabled`));
    await evaluate(`${state('disabled')}.click()`); await until(`!${root}.hasAttribute('data-disabled')`);
    await evaluate(`${state('readonly')}.click()`); await until(`!${root}.hasAttribute('data-readonly')`);
    await evaluate(`${form}.reset()`); await until(`JSON.parse(${field}.value).edges.length===1`);
    await evaluate(`${state('reject')}.click()`); await until(`!${state('reject')}.hasAttribute('checked')`);
    await evaluate(`${form}.requestSubmit()`); await until(`document.querySelector('[data-flow-toast=${id}] [role=status]')?.textContent==='Graph saved.'`);
    const savedGraph = await evaluate(`${field}.value`);
    assert.equal(JSON.parse(savedGraph).nodes[0].data.payload.customValue, 'retained');
    await evaluate(`${root}.querySelector('details').open=true;${root}.querySelector('[data-flow-kind=node][data-flow-id=source]').focus()`);
    await key('Enter'); await evaluate(`${action('remove')}.click()`);
    await until(`JSON.parse(${field}.value).nodes.length===2&&JSON.parse(${field}.value).edges.length===0`);
    await evaluate(`${form}.reset()`); await until(`${field}.value===${JSON.stringify(savedGraph)}`);
    await evaluate(`(() => {
      const root=${root}, form=${form};
      window.flowServerValue=root.dataset.value;
      window.patchFlowValue=value=>{
        root.dataset.value=value;
        form.dispatchEvent(new CustomEvent('bml:update',{bubbles:true,detail:{target:form,added:[],retained:[root]}}));
      };
      const graph=JSON.parse(window.flowServerValue);
      graph.edges=[];
      Object.assign(graph.nodes.find(node=>node.id==='source'),{connectable:false,selectable:false});
      window.patchFlowValue(JSON.stringify(graph));
    })()`);
    await until(`${root}.querySelector('[data-flow-id=source]').disabled`);
    assert.ok(await evaluate(`[...${action('source')}.options].every(option=>JSON.parse(option.value).node!=='source')`),
      'Non-connectable source nodes are omitted from keyboard connection choices');
    await evaluate(`(() => {
      const root=${root};window.flowSelections=0;
      root.addEventListener('flowselect',()=>window.flowSelections++);
      root.querySelector('[data-flow-kind=node][data-flow-id=process]').click();
      const blocked=root.querySelector('[data-flow-kind=node][data-flow-id=source]');
      blocked.disabled=false;blocked.click();blocked.disabled=true;
      const source=${action('source')};
      source.add(new Option('Stale source',JSON.stringify({node:'source',handle:'items'})));
      source.value=JSON.stringify({node:'source',handle:'items'});
      ${action('target')}.value=JSON.stringify({node:'output',handle:'result'});
      ${action('connect')}.click();
    })()`);
    await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    assert.ok(await evaluate(`window.flowSelections===1&&${root}.querySelector('[data-flow-id=source]').getAttribute('aria-pressed')==='false'&&${root}.querySelector('[data-flow-id=process]').getAttribute('aria-pressed')==='true'`),
      'A non-selectable outline node cannot replace the current selection, even through a stale control');
    assert.equal(await evaluate(`JSON.parse(${field}.value).edges.length`),0,'Connection handlers reject a stale blocked-source choice');
    await evaluate(`(() => {
      const graph=JSON.parse(window.flowServerValue);graph.edges=[];
      graph.nodes.find(node=>node.id==='process').connectable=false;
      window.patchFlowValue(JSON.stringify(graph));
    })()`);
    assert.ok(await evaluate(`[...${action('target')}.options].every(option=>JSON.parse(option.value).node!=='process')`),
      'Non-connectable target nodes are omitted from keyboard connection choices');
    await evaluate(`(() => {
      const target=${action('target')};
      target.add(new Option('Stale target',JSON.stringify({node:'process',handle:'items'})));
      target.value=JSON.stringify({node:'process',handle:'items'});
      ${action('source')}.value=JSON.stringify({node:'source',handle:'items'});
      ${action('connect')}.click();
    })()`);
    await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    assert.equal(await evaluate(`JSON.parse(${field}.value).edges.length`),0,'Connection handlers reject a stale blocked-target choice');
    for (const invalid of [
      {nodes:[{id:'duplicate',position:{x:0,y:0}},{id:'duplicate',position:{x:100,y:0}}],edges:[]},
      {nodes:[],edges:[{id:'missing',source:'missing',target:'missing'}]},
    ]) {
      await evaluate(`window.patchFlowValue(${JSON.stringify(JSON.stringify(invalid))})`);
      // A second patch also covers an editor mounted with an already-invalid value.
      await evaluate(`window.patchFlowValue(${JSON.stringify(JSON.stringify(invalid))})`);
      assert.ok(await evaluate(`!${field}.readOnly&&${field}.willValidate&&${field}.validity.customError&&!${field}.hidden&&!${form}.checkValidity()`),
        'Invalid graph JSON remains subject to native validation after repeated patches');
      assert.ok(await evaluate(`document.activeElement===${field}`),'Invalid graph feedback focuses its visible native field');
      assert.equal(await evaluate(`(() => {
        const form=${form};let submitted=0;
        const prevent=event=>{submitted++;event.preventDefault();event.stopImmediatePropagation();};
        form.addEventListener('submit',prevent,true);form.requestSubmit();form.removeEventListener('submit',prevent,true);
        return submitted;
      })()`),0,'Invalid graphs cannot reach the consuming form action');
      await evaluate('window.patchFlowValue(window.flowServerValue)');
      await until(`${root}.querySelectorAll('.vue-flow__node').length===3&&${field}.hidden`);
      assert.ok(await evaluate(`!${field}.readOnly&&${form}.checkValidity()`),'A valid replacement immediately restores the editable form');
    }
    await evaluate(`${state('replace')}.click()`); await until(`${root}.querySelectorAll('.vue-flow__node').length===1&&${field}.value.includes('replacement')`);
    assert.ok(await evaluate(`${root}===window.flowRoot`), 'Controlled graph replacement retains the canvas');
    for (let cycle=0;cycle<2;cycle++) {
      await evaluate(`${state('visible')}.click()`); await until(`!${example}.querySelector('[data-flow-ready]')`);
      await evaluate(`${state('visible')}.click()`); await until(`${example}.querySelector('[data-flow-ready] .vue-flow__node')`);
    }
    assert.equal(await evaluate(`document.querySelector('[data-catalog-flow=${id==='dark'?'light':'dark'}] textarea').value`), originalSibling);
    assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth`));
    reports.push({ component:'flow-editor', width, palette:id, drag:true, cancel:true, replacement:true, disposal:true });

    await navigate('/charts');
    await until(`document.querySelectorAll('[data-chart-ready] canvas:not([hidden])').length===2&&[...document.querySelectorAll('[data-bml-method]')].every(node=>node.dataset.bmlActionBound==='true')`);
    const chartExample = `document.querySelector('[data-catalog-chart=${id}]')`, chart = `${chartExample}.querySelector('[data-bml-chart]')`;
    const chartState = suffix => `${chartExample}.querySelector('[data-chart-${suffix}]')`;
    await evaluate('document.fonts.ready');
    await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    await evaluate(`window.chartRoot=${chart};window.chartCanvas=${chart}.querySelector('canvas');window.chartPixels=window.chartCanvas.toDataURL();${chartExample}.querySelector('[data-example-states]').open=true;${chartState('refresh')}.click()`);
    await until(`document.querySelector('[data-chart-toast=${id}]')?.matches(':popover-open')`);
    assert.ok(await evaluate(`${chart}===window.chartRoot&&${chart}.querySelector('canvas')===window.chartCanvas&&window.chartCanvas.toDataURL()===window.chartPixels`));
    await evaluate(`${chartState('replace')}.click()`); await until(`${chart}.querySelector('tbody').textContent.includes('12')`);
    assert.ok(await evaluate(`window.chartCanvas.toDataURL()!==window.chartPixels`));
    await evaluate(`${chart}.querySelector('details').open=true;${chart}.querySelector('[data-chart-dataset="0"][data-chart-index="2"]').focus()`);
    await key('Enter'); await until(`document.querySelector('[data-chart-toast=${id}] [role=status]')?.textContent.includes('point 3')`);
    for (const type of ['line','scatter','bubble','pie','doughnut','radar','polarArea','bar']) {
      await evaluate(`${chartExample}.querySelector('select[name=chartType]').value=${JSON.stringify(type)};${chartExample}.querySelector('form').requestSubmit()`);
      await until(`JSON.parse(${chart}.dataset.config).type===${JSON.stringify(type)}&&!${chart}.querySelector('canvas').hidden`);
      assert.ok(await evaluate(`${chart}.querySelector('canvas').width>0&&${chart}.querySelector('canvas')===window.chartCanvas`));
      if (['line','radar'].includes(type)) {
        for (const color of ['oklch(65% 0.25 330)','color-mix(in srgb, #ff00ff 75%, #0000ff)']) {
          await evaluate(`${chart}.style.setProperty('--chart-series-1',${JSON.stringify(color)});new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
          assert.ok(await evaluate(`!${chart}.querySelector('canvas').hidden&&${chart}.querySelector('[data-bml-ref=state]').hidden`),
            `${type} renders a CSS ${color} palette without a color-conversion error`);
          assert.ok(await evaluate(`(() => {const c=${chart}.querySelector('canvas'),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let matches=0;for(let i=0;i<p.length;i+=4)if(p[i]>80&&p[i+1]<80&&p[i+2]>100&&p[i+3]===255)matches++;return matches>20;})()`),
            'Modern CSS colors reach the rendered chart, including after a palette change');
        }
        await evaluate(`${chart}.style.setProperty('--chart-series-1','invalid-chart-color');new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
        assert.ok(await evaluate(`${chart}.querySelector('canvas').hidden&&${chart}.querySelector('[data-bml-ref=state]').textContent.includes('Invalid chart color')`),
          'An invalid CSS palette produces component feedback');
        await evaluate(`${chart}.style.removeProperty('--chart-series-1');new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
        assert.ok(await evaluate(`!${chart}.querySelector('canvas').hidden&&${chart}.querySelectorAll('tbody tr').length===3`),
          'Repairing a CSS palette restores both the chart and its accessible data table');
      }
    }
    await evaluate(`${chart}.style.setProperty('--chart-series-1','#ff00ff')`);
    await until(`(() => {const c=${chart}.querySelector('canvas'),pixels=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let matches=0;for(let i=0;i<pixels.length;i+=4)if(pixels[i]===255&&pixels[i+1]===0&&pixels[i+2]===255&&pixels[i+3]===255)matches++;return matches>50;})()`);
    const previousNotice = await evaluate(`Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence)`);
    await evaluate(`${chart}.scrollIntoView({block:'center'});window.canvasSelection=null;${chart}.addEventListener('chartselect',event=>window.canvasSelection=event.detail,{once:true})`);
    const point = await evaluate(`(() => {const c=${chart}.querySelector('canvas'),r=c.getBoundingClientRect(),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;for(let y=30;y<c.height*.7;y++)for(let x=30;x<c.width;x++){const i=(y*c.width+x)*4;if(p[i]===255&&p[i+1]===0&&p[i+2]===255&&p[i+3]===255)return {x:r.x+(x+2)*r.width/c.width,y:r.y+(y+4)*r.height/c.height};}throw new Error('No rendered bar');})()`);
    await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
    await call('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});
    await until(`window.canvasSelection?.datasetIndex===0&&Number.isInteger(window.canvasSelection.index)`);
    await until(`Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence)>${previousNotice}`);
    await evaluate(`${chart}.style.removeProperty('--chart-series-1')`);
    await until(`(() => {const c=${chart}.querySelector('canvas'),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;for(let i=0;i<p.length;i+=4)if(p[i]===255&&p[i+1]===0&&p[i+2]===255)return false;return true;})()`);
    await evaluate(`window.chartThemeStyle=document.createElement('style');window.chartThemeStyle.textContent='.catalog-palette-${id}[data-theme=custom] {--chart-series-1:#00ffff}';document.head.append(window.chartThemeStyle);${chart}.closest('.catalog-palette-${id}').dataset.theme='custom'`);
    await until(`(() => {const c=${chart}.querySelector('canvas'),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let count=0;for(let i=0;i<p.length;i+=4)if(p[i]===0&&p[i+1]===255&&p[i+2]===255&&p[i+3]===255)count++;return count>50;})()`);
    await evaluate(`window.chartThemeStyle.remove();${chart}.closest('.catalog-palette-${id}').removeAttribute('data-theme')`);
    const height = await evaluate(`${chart}.querySelector('.chart-frame').getBoundingClientRect().height`);
    await evaluate(`${chartState('loading')}.click()`); await until(`${chart}.getAttribute('aria-busy')==='true'&&${chart}.querySelector('canvas').hidden`);
    assert.equal(await evaluate(`${chart}.querySelector('.chart-frame').getBoundingClientRect().height`), height);
    await evaluate(`${chartState('loading')}.click()`); await until(`${chart}.getAttribute('aria-busy')==='false'&&!${chart}.querySelector('canvas').hidden`);
    await evaluate(`${chartState('failed')}.click()`); await until(`${chart}.querySelector('[data-bml-ref=state]').textContent.includes('could not load')`);
    await evaluate(`${chartState('failed')}.click()`); await until(`!${chart}.querySelector('canvas').hidden`);
    await evaluate(`${chartState('empty')}.click()`); await until(`${chart}.querySelector('[data-bml-ref=state]').textContent==='No chart data.'&&${chart}.querySelectorAll('tbody tr').length===0`);
    await evaluate(`${chartState('empty')}.click()`); await until(`!${chart}.querySelector('canvas').hidden&&${chart}.querySelectorAll('tbody tr').length===3`);
    for (let cycle=0;cycle<2;cycle++) {
      await evaluate(`${chartState('visible')}.click()`); await until(`!${chartExample}.querySelector('[data-chart-ready]')`);
      await evaluate(`${chartState('visible')}.click()`); await until(`${chartExample}.querySelector('[data-chart-ready] canvas:not([hidden])')`);
    }
    assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&${chart}.querySelector('canvas').getBoundingClientRect().width<=${chart}.getBoundingClientRect().width+1`));
    reports.push({ component:'chart', width, palette:id, types:8, cssColors:true, nativeSelection:true, replacement:true, disposal:true });
    const screenshot = await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-charts-${width}-${id}.png`),Buffer.from(screenshot.data,'base64'));
  }
  await call('Network.clearBrowserCookies');
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await navigate('/charts');
  assert.ok(await evaluate(`document.querySelectorAll('[data-bml-chart] tbody tr').length===6&&[...document.querySelectorAll('[data-bml-chart] canvas')].every(canvas=>canvas.hidden)`));
  await navigate('/flows');
  assert.ok(await evaluate(`[...document.querySelectorAll('[data-bml-flow-editor] textarea')].every(field=>!field.hidden&&JSON.parse(field.value).nodes.length===3)`));
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-visualizations.json`),JSON.stringify(reports,null,2));
}
