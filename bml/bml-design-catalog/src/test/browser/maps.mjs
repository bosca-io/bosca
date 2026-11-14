import assert from 'node:assert/strict';
import {writeFile,readFile} from 'node:fs/promises';
import {join} from 'node:path';

/** Real geographic rendering, viewport gestures, keyboard actions and retained map state. */
export async function checkMaps({navigate,evaluate,until:poll,call,key,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`),reports=[];
  const axe=await readFile(new URL('../../../node_modules/axe-core/axe.min.js',import.meta.url),'utf8');
  for(const width of [1440,390])for(const id of ['dark','light']) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await navigate('/charts');
    await until(`document.querySelectorAll('[data-chart-ready] canvas').length===2&&[...document.querySelectorAll('[data-bml-method]')].every(node=>node.dataset.bmlActionBound==='true')`);
    const example=`document.querySelector('[data-catalog-chart=${id}]')`,root=`${example}.querySelector('[data-bml-chart]')`;
    const surface=`${root}.querySelector('[data-bml-ref=surface]')`,canvas=`${surface}.querySelector('canvas')`;
    const action=name=>`${root}.querySelector('[data-bml-ref=${name}]')`;
    await evaluate(`${example}.querySelector('select[name=chartType]').value='map';${example}.querySelector('form').requestSubmit()`);
    await until(`${example}.dataset.chartKind==='map'&&!${surface}.hidden&&${root}.querySelectorAll('tbody tr').length===12`);
    assert.equal(await evaluate(`${example}.querySelector('[role=combobox]').textContent.trim()`),'map','The chart-type picker retains the applied map choice');
    await evaluate('document.fonts.ready');
    await evaluate(`${surface}.scrollIntoView({block:'center'});new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:1,y:1});
    await evaluate(`window.mapRoot=${root};window.mapCanvas=${canvas};window.mapInitial=${canvas}.toDataURL()`);
    assert.ok(await evaluate(`${surface}.tabIndex===0&&!${action('mapControls')}.hidden&&!${action('mapZoomIn')}.disabled`));
    await evaluate(`${surface}.focus()`);await key('ArrowRight');
    await until(`${canvas}.toDataURL()!==window.mapInitial`);
    await evaluate(`window.mapPanned=${canvas}.toDataURL()`);await key('+');
    await until(`${canvas}.toDataURL()!==window.mapPanned`);
    await key('Home');await until(`${canvas}.toDataURL()===window.mapInitial`);
    await evaluate(`${action('mapZoomIn')}.click()`);await until(`${canvas}.toDataURL()!==window.mapInitial`);
    await evaluate(`${action('mapReset')}.click()`);await until(`${canvas}.toDataURL()===window.mapInitial`);
    const center=await evaluate(`(()=>{const r=${surface}.getBoundingClientRect();return {x:r.x+r.width*.5,y:r.y+r.height*.4};})()`);
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',...center});
    await call('Input.dispatchMouseEvent',{type:'mousePressed',...center,button:'left',clickCount:1});
    for(let step=1;step<=5;step++)await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:center.x+step*6,y:center.y+step*2,button:'left',buttons:1});
    await call('Input.dispatchMouseEvent',{type:'mouseReleased',x:center.x+30,y:center.y+10,button:'left',clickCount:1});
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:1,y:1});
    await until(`${canvas}.toDataURL()!==window.mapInitial`);
    // Rich-text tooltips remain painted until ECharts' mouseout hideDelay expires.
    await evaluate(`new Promise(resolve=>setTimeout(()=>requestAnimationFrame(()=>requestAnimationFrame(resolve)),JSON.parse(${root}.dataset.config).tooltip?.hideDelay??100))`);
    await evaluate(`window.mapViewport=${canvas}.toDataURL();${example}.querySelector('[data-example-states]').open=true;${example}.querySelector('[data-chart-refresh]').click()`);
    await until(`document.querySelector('[data-chart-toast=${id}]')?.matches(':popover-open')`);
    assert.ok(await evaluate(`${root}===window.mapRoot&&${canvas}===window.mapCanvas&&${canvas}.toDataURL()===window.mapViewport`),'Surrounding updates retain the geographic viewport');
    await evaluate(`${action('mapReset')}.click()`);await until(`${canvas}.toDataURL()===window.mapInitial`);
    await call('Input.dispatchMouseEvent',{type:'mouseWheel',...center,deltaX:0,deltaY:-120});
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:1,y:1});
    await until(`${canvas}.toDataURL()!==window.mapInitial`);
    await evaluate(`${action('mapReset')}.click()`);await until(`${canvas}.toDataURL()===window.mapInitial`);
    await evaluate(`window.mapNotice=Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence);window.regionSelection=null;${root}.addEventListener('chartselect',event=>window.regionSelection=event.detail);${root}.querySelector('details').open=true;${root}.querySelector('[data-chart-index="0"]').focus()`);
    await key('Enter');await until(`window.regionSelection?.label==='United States of America'&&window.regionSelection.value===13`);
    await until(`Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence)>window.mapNotice`);
    await evaluate(`${action('mapReset')}.click();${root}.querySelector('details').open=false;window.mapNotice=Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence);window.regionSelection=null;${surface}.scrollIntoView({block:'center'})`);
    const point=await evaluate(`(()=>{const c=${canvas},r=c.getBoundingClientRect(),p=c.getContext('2d').getImageData(0,0,c.width,c.height).data;for(let y=25;y<c.height-65;y++)for(let x=15;x<c.width-15;x++){const i=(y*c.width+x)*4;if(p[i+2]>p[i]+35&&p[i+2]>p[i+1]+10&&p[i+3]===255){let inside=true;for(let dy=-3;dy<=3;dy++)for(let dx=-3;dx<=3;dx++){const j=((y+dy)*c.width+x+dx)*4;if(p[j+2]<=p[j]+35||p[j+2]<=p[j+1]+10||p[j+3]!==255)inside=false;}if(inside)return {x:r.x+(x+.5)*r.width/c.width,y:r.y+(y+.5)*r.height/c.height};}}throw new Error('No colored geographic region');})()`);
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',...point});
    await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
    // A surrounding island update must retain the map paths throughout a native click.
    await evaluate(`${example}.dispatchEvent(new CustomEvent('bml:update',{bubbles:true,detail:{target:${example},added:[],retained:[${root}]}}));new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`);
    await call('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});
    await until(`window.regionSelection`);
    assert.ok(await evaluate(`Number.isFinite(window.regionSelection.value)`),JSON.stringify(await evaluate(`window.regionSelection`)));
    assert.ok(await evaluate(`JSON.parse(${root}.dataset.config).series[0].data.some(point=>point.name===window.regionSelection.label&&point.value===window.regionSelection.value)`));
    await until(`Number(document.querySelector('[data-chart-toast=${id}]').dataset.sequence)>window.mapNotice`);
    await evaluate(`window.validMaps=${root}.dataset.maps;${root}.dataset.maps='{}'`);
    await until(`${surface}.hidden&&${action('state')}.textContent.includes('GeoJSON FeatureCollection')`);
    assert.equal(await evaluate(`${root}.querySelectorAll('tbody tr').length`),12);
    await evaluate(`${root}.dataset.maps=window.validMaps`);await until(`!${surface}.hidden&&${canvas}.width>0`);
    await evaluate(`${example}.querySelector('[data-chart-empty]').click()`);
    await until(`${root}.querySelectorAll('tbody tr').length===0&&!${surface}.hidden`);
    await evaluate(`${example}.querySelector('[data-chart-empty]').click()`);await until(`${root}.querySelectorAll('tbody tr').length===12`);
    await evaluate(`${example}.querySelector('[data-chart-loading]').click()`);await until(`${surface}.hidden&&${action('mapZoomIn')}.disabled`);
    await evaluate(`${example}.querySelector('[data-chart-loading]').click()`);await until(`!${surface}.hidden&&!${action('mapZoomIn')}.disabled`);
    await evaluate(`${root}.style.maxWidth='300px';${root}.style.setProperty('--chart-height','340px')`);
    await until(`${canvas}.getBoundingClientRect().height===340&&Math.abs(${canvas}.getBoundingClientRect().width-${surface}.clientWidth)<1`);
    await evaluate(axe);
    const accessibility=await evaluate(`axe.run(${root},{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}}).then(result=>result.violations)`);
    assert.deepEqual(accessibility,[],'Map controls and data table pass axe checks');
    const screenshot=await call('Page.captureScreenshot',{format:'png'});
    await writeFile(join(artifacts,`catalog-${mode}-map-${width}-${id}.png`),Buffer.from(screenshot.data,'base64'));
    await evaluate(`window.removedMap=${surface};${example}.querySelector('[data-chart-visible]').click()`);await until(`!${example}.querySelector('[data-chart-ready]')`);
    await until(`!window.removedMap.getAttribute('_echarts_instance_')&&!window.removedMap.querySelector('canvas')`);
    await evaluate(`${example}.querySelector('[data-chart-visible]').click()`);await until(`${example}.querySelector('[data-chart-ready] canvas')`);
    reports.push({width,palette:id,pan:true,zoom:true,keyboard:true,regionSelection:true,viewportRetention:true,missingGeometryRecovery:true,resize:true,disposal:true,accessibility:true});
  }
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  await navigate('/charts');
  assert.ok(await evaluate(`document.querySelector('[data-chart-kind=map] tbody').textContent.includes('United States of America')&&document.querySelector('[data-chart-kind=map] [data-bml-ref=surface]').hidden`),'Region values remain available without scripts');
  await call('Emulation.setScriptExecutionDisabled',{value:false});
  await writeFile(join(artifacts,`catalog-${mode}-maps.json`),JSON.stringify(reports,null,2));
}
