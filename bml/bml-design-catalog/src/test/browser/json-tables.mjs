import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Virtual rows keep logical positions and pending text through local and caller-owned mode changes. */
export async function checkJsonTables({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const measurements=[];
  const cookies=await call('Network.getAllCookies');
  const key=async(key,modifiers=0)=>{
    for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key,modifiers,windowsVirtualKeyCode:{a:65,Enter:13}[key]});
  };
  try {
    for(const width of [1440,390])for(const id of ['dark','light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
      await navigate('/json');
      const root=`document.querySelector('[data-catalog-json-editor=${id}]')`;
      const table=`${root}.querySelector('table')`;
      const contents=`${root}.querySelector('.jse-table-mode .jse-contents')`;
      const field=`${root}.querySelector('textarea')`;
      await until(`${root}?.hasAttribute('data-json-ready')`);
      const replace=async value=>{
        await evaluate(`${root}.querySelector('.cm-content').focus()`);
        await key('a',await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'));
        await call('Input.insertText',{text:value});
      };
      const snapshot=()=>evaluate(`(()=>{const t=${table};return {count:t.getAttribute('aria-rowcount'),
        rows:[...t.rows].filter(r=>r.classList.contains('jse-table-row')).map(r=>({index:r.getAttribute('aria-rowindex'),label:r.querySelector('.jse-table-cell-gutter')?.firstChild.textContent.trim()})),
        spacers:[...t.rows].filter(r=>!r.classList.contains('jse-table-row')).map(r=>({hidden:r.getAttribute('aria-hidden'),height:r.getBoundingClientRect().height,columns:[...r.cells].map(c=>c.colSpan)})),
        headers:[...t.rows[0].cells].map(h=>({label:h.textContent.trim(),scope:h.scope})),scrollTop:${contents}.scrollTop};})()`);
      const validate=(state,virtual)=>{
        assert.equal(state.count,virtual?'-1':String(state.rows.length));
        assert.equal(state.rows[0].index,'1');
        for(const row of state.rows.slice(1))assert.equal(Number(row.index),Number(row.label)+2);
        assert.ok(state.rows.every((row,i)=>!i||Number(row.index)>Number(state.rows[i-1].index)));
        assert.ok(state.spacers.every(row=>row.hidden==='true'));
        assert.ok(state.spacers.every(row=>row.columns.length===1&&row.columns[0]===state.headers.length),JSON.stringify(state));
        assert.ok(state.headers.every(header=>header.label&&header.scope==='col'));
      };
      await evaluate(`${root}.querySelector('button[title^="Switch to text"]').click()`);
      await until(`${root}.querySelector('.cm-content')`);
      const large=JSON.stringify(Array.from({length:500},(_,index)=>({component:`Component ${index}`,height:32,meta:{index}})));
      await replace(large);
      // Deliberately switch immediately: mode changes must flush the text engine's pending work.
      await evaluate(`${root}.querySelector('button[title^="Switch to table"]').click()`);
      await until(`${table}`);
      await evaluate(`${root}.scrollIntoView({block:'center'});${contents}.scrollTop=6000`);
      await until(`[...${table}.querySelectorAll('.jse-table-cell-gutter')].some(row=>Number(row.firstChild.textContent.trim())>100)`);
      const scrolled=await snapshot();
      measurements.push({width,id,scrolled});
      await writeFile(join(artifacts,`catalog-${mode}-json-tables.json`),JSON.stringify(measurements,null,2));
      validate(scrolled,true);
      assert.ok(scrolled.rows.length<500);
      assert.deepEqual(await evaluate(`JSON.parse(${field}.value)`),JSON.parse(large));
      const spacer=await call('Runtime.evaluate',{expression:`${table}.querySelector('.jse-table-invisible-start-section')`});
      const ax=await call('Accessibility.getPartialAXTree',{objectId:spacer.result.objectId,fetchRelatives:false});
      await call('Runtime.releaseObject',{objectId:spacer.result.objectId});
      assert.ok(ax.nodes[0].ignored,'Virtual spacers are absent from the accessibility table');
      await evaluate(`${contents}.scrollTop=0`);
      await until(`${table}.querySelector('.jse-table-cell-gutter')?.firstChild.textContent.trim()==='0'`);
      validate(await snapshot(),true);

      // Caller actions switch the same retained editor; the next local text draft must survive too.
      await evaluate(`document.querySelector('[data-json-mode=${id}]').click()`);
      await until(`${root}.dataset.mode==='text'&&${root}.querySelector('.cm-content')`);
      const small='[{"component":"First","meta":{"enabled":true}},{"component":"Second","meta":{}}]';
      await replace(small);
      await evaluate(`document.querySelector('[data-json-mode=${id}]').click()`);
      await until(`${root}.dataset.mode==='table'&&${table}?.querySelectorAll('.jse-table-cell-gutter').length===2`);
      validate(await snapshot(),false);
      assert.deepEqual(await evaluate(`JSON.parse(new FormData(document.querySelector('[data-catalog-json=${id}]')).get('settings'))`),JSON.parse(small));
      const cell=await evaluate(`(()=>{const e=${table}.querySelector('[data-path="%2F0%2Fcomponent"] .jse-value');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
      for(const type of ['mousePressed','mouseReleased'])await call('Input.dispatchMouseEvent',{type,button:'left',clickCount:2,...cell});
      await until(`${table}.querySelector('.jse-editable-div')`);
      await evaluate(`(()=>{const e=${table}.querySelector('.jse-editable-div');e.focus();const range=document.createRange();range.selectNodeContents(e);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);})()`);
      await call('Input.insertText',{text:'Edited component'});await key('Enter');
      await until(`JSON.parse(${field}.value)[0].component==='Edited component'`);
      const smallState=await snapshot();validate(smallState,false);
      const colorSelector=`[data-catalog-json-editor=${id}] table td button, [data-catalog-json-editor=${id}] table .jse-value`;
      const colors=await evaluate(`(${contrastSamples.toString()})(${JSON.stringify(colorSelector)})`);
      measurements.at(-1).colors=colors.text;
      const hints=await evaluate(`(${contrastSamples.toString()})('[data-catalog-json-editor=${id}] table .jse-empty','::after')`);
      measurements.at(-1).hints=hints.text;
      await writeFile(join(artifacts,`catalog-${mode}-json-tables.json`),JSON.stringify(measurements,null,2));
      for(const sample of colors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
      assert.ok(hints.text.length>0,'Empty table values retain visible editing hints');
      for(const sample of hints.text)assert.ok(sample.ratio>=4.5,`Empty value hint: ${JSON.stringify(sample)}`);
      const edited=await evaluate(`${field}.value`),edges=[];
      for(const value of ['[]','[{}]','[{"component":"Only","width":99,"enabled":true}]',edited]) {
        await evaluate(`${root}.querySelector('button[title^="Switch to text"]').click()`);
        await until(`${root}.querySelector('.cm-content')`);await replace(value);
        await evaluate(`${root}.querySelector('button[title^="Switch to table"]').click()`);
        await until(`${root}.querySelector('.jse-table-mode')`);
        assert.deepEqual(await evaluate(`JSON.parse(${field}.value)`),JSON.parse(value));
        if(value==='[]'||value==='[{}]') {
          assert.ok(await evaluate(`!${table}&&${root}.querySelector('.jse-table-mode').textContent.includes('cannot be opened in table mode')`));
          edges.push({value,table:false});
        } else {
          const state=await snapshot();validate(state,false);
          assert.equal(state.headers.length,value===edited?smallState.headers.length:4);
          edges.push({value,table:true,...state});
        }
      }
      measurements.at(-1).edges=edges;
      assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth`));
      measurements.at(-1).small=smallState;
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-json-table-${id}-${width}.png`),Buffer.from(shot.data,'base64'));
    }
  } finally {
    await call('Network.clearBrowserCookies');
    if(cookies.cookies.length)await call('Network.setCookies',{cookies:cookies.cookies});
    await writeFile(join(artifacts,`catalog-${mode}-json-tables.json`),JSON.stringify(measurements,null,2));
  }
}
