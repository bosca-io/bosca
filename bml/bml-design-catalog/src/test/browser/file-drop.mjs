import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Exercises the exported control through an independent consumer in both palettes. */
export async function checkFileDrops({navigate,evaluate,until,call,artifacts,mode}) {
  const directory = await mkdtemp(join(tmpdir(), 'bosca-file-drop-'));
  const first = join(directory, 'first.zip'), second = join(directory, 'second.zip');
  await writeFile(first, 'first'); await writeFile(second, 'second');
  try {
    for (const width of [1280, 390]) {
      await call('Emulation.setDeviceMetricsOverride', {width,height:1000,deviceScaleFactor:1,mobile:false});
      await navigate('/foundation');
      await until(`document.querySelector('[data-file-example=light] [data-file-drop]')?.getAttribute('aria-disabled') === 'false'`);
      for (const palette of ['dark', 'light']) {
        const owner = `[data-file-example=${palette}]`;
        const selector = kind => `${owner} [data-example-drop=${kind}]`;
        const root = kind => `document.querySelector(${JSON.stringify(selector(kind))})`;
        await evaluate(`window.fileSelections=[]; document.querySelector(${JSON.stringify(owner)}).addEventListener('filesselected', e => window.fileSelections.push(e.detail.map(f => f.name)))`);
        const choose = async files => {
          const document = await call('DOM.getDocument');
          const input = await call('DOM.querySelector', {nodeId:document.root.nodeId,selector:selector('multiple')+' input[type=file]'});
          await call('DOM.setFileInputFiles', {nodeId:input.nodeId,files});
        };
        await choose([first,second]);
        await until(`window.fileSelections.length === 1`);
        assert.deepEqual(await evaluate('window.fileSelections[0]'), ['first.zip','second.zip']);
        assert.equal(await evaluate(`${root('multiple')}.querySelector('input').value`), '');
        await choose([first,second]);
        await until(`window.fileSelections.length === 2`);
        assert.equal(await evaluate(`document.querySelector(${JSON.stringify(owner)}).querySelector('[role=status]').textContent`), 'first.zip, second.zip');
        assert.equal(await evaluate(`${root('single')}.querySelector('input').accept`), '.zip');
        const drop = async kind => {
          const rect = await evaluate(`(() => {const e=${root(kind)}; e.scrollIntoView({block:'center'}); const r=e.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
          for (const type of ['dragEnter','dragOver','drop']) await call('Input.dispatchDragEvent', {type,...rect,data:{items:[],files:[first,second],dragOperationsMask:1}});
        };
        await drop('single');
        await until('window.fileSelections.length === 3');
        assert.deepEqual(await evaluate('window.fileSelections[2]'), ['first.zip']);
        await drop('multiple');
        await until('window.fileSelections.length === 4');
        assert.deepEqual(await evaluate('window.fileSelections[3]'), ['first.zip','second.zip']);
        await drop('disabled');
        assert.equal(await evaluate('window.fileSelections.length'),4);
        await evaluate(`${root('multiple')}.setAttribute('data-disabled','')`);
        await until(`${root('multiple')}.querySelector('input').disabled`);
        await drop('multiple');
        assert.equal(await evaluate('window.fileSelections.length'),4);
        assert.equal(await evaluate(`${root('multiple')}.getAttribute('aria-disabled')`),'true');
        await evaluate(`${root('multiple')}.removeAttribute('data-disabled')`);
        await until(`!${root('multiple')}.querySelector('input').disabled`);
        await drop('multiple');
        await until('window.fileSelections.length === 5');
        const textDrop = await evaluate(`(() => {const root=${root('multiple')}; const data=new DataTransfer();data.setData('text/plain','example');const e=new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:data});root.dispatchEvent(e);return {prevented:e.defaultPrevented,dragging:root.hasAttribute('data-dragging'),count:window.fileSelections.length};})()`);
        assert.deepEqual(textDrop,{prevented:false,dragging:false,count:5});
        await evaluate(`${root('multiple')}.querySelector('input').focus()`);
        assert.equal(await evaluate(`document.activeElement === ${root('multiple')}.querySelector('input')`),true);
        assert.ok(await evaluate(`(() => {const r=${root('multiple')}.getBoundingClientRect();return r.width>0&&r.right<=innerWidth;})()`));
        const capture = await call('Page.captureScreenshot',{format:'png'});
        await writeFile(join(artifacts,`catalog-${mode}-file-drop-${palette}-${width}.png`),Buffer.from(capture.data,'base64'));
      }
    }
  } finally { await rm(directory,{recursive:true,force:true}); }
}
