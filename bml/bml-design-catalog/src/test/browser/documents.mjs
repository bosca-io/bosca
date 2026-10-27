import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

export async function checkDocuments({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const reports = [];
  const key = async (key, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', {
      type, key, modifiers, windowsVirtualKeyCode: { a: 65, b: 66, Tab: 9, ArrowLeft: 37, ArrowRight: 39, Home: 36, End: 35, Escape: 27 }[key],
    });
  };
  try {
    for (const width of [1440, 390]) for (const id of ['dark', 'light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await navigate('/documents');
      await until(`document.querySelectorAll('[data-document-ready]').length===4&&document.querySelector('[data-document-form=${id}]')?.dataset.bmlActionBound==='true'`);
      const metadata = await evaluate(`(() => {
        const root=document.querySelector('[data-metadata-document]');
        return {text:root.querySelector('.tiptap').textContent,
          value:JSON.parse(new FormData(document.querySelector('[data-metadata-document-form]')).get('metadataBody'))};
      })()`);
      assert.equal(metadata.text, 'A document from Bosca content.');
      assert.deepEqual(Object.keys(metadata.value).sort(), ['content','type']);
      const root = `document.querySelector('[data-catalog-document=${id}] [data-bml-document-editor]')`;
      const editor = `${root}.querySelector('.tiptap')`, field = `${root}.querySelector('textarea')`;
      const form = `document.querySelector('[data-document-form=${id}]')`;
      const command = name => evaluate(`${root}.querySelector('[data-document-command=${name}]').click()`);
      const json = () => evaluate(`JSON.parse(${field}.value)`);
      const modifier = await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2');
      const originalSibling = await evaluate(`document.querySelector('[data-catalog-document=${id==='dark'?'light':'dark'}] textarea').value`);
      await evaluate(`${editor}.scrollIntoView({block:'center'});${editor}.focus()`);
      await key('a', modifier); await call('Input.insertText', { text: 'Document draft <strong>stays text</strong>' });
      await key('b', modifier); await call('Input.insertText', { text: ' with bold' }); await key('b', modifier);
      let value = await json();
      assert.ok(JSON.stringify(value).includes('Document draft <strong>stays text</strong>'));
      assert.ok(JSON.stringify(value).includes('"type":"bold"'));
      assert.equal(await evaluate(`${editor}.querySelector('strong')?.textContent`), ' with bold');
      await evaluate(`window.documentRoot=${root};window.documentContent=${editor};window.documentDraft=${field}.value;
        document.querySelector('[data-catalog-document=${id}] details').open=true;
        document.querySelector('[data-document-refresh=${id}]').click()`);
      await until(`document.querySelector('[data-document-toast=${id}]')?.matches(':popover-open')`);
      assert.ok(await evaluate(`${root}===window.documentRoot&&${editor}===window.documentContent&&${field}.value===window.documentDraft&&document.activeElement===${editor}`));
      await evaluate(`document.querySelector('[data-document-reject=${id}]').click()`);
      await until(`document.querySelector('[data-document-reject=${id}]').hasAttribute('checked')`);
      await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{
        if(String(args[0]).includes('/_bml/action')) {window.fetch=original;await new Promise(resolve=>window.releaseDocumentSave=resolve);}
        return original(...args);};${form}.requestSubmit();})()`);
      await until(`${form}.hasAttribute('data-bml-action-pending')&&${editor}.getAttribute('contenteditable')==='false'`);
      assert.equal(await evaluate(`${field}.readOnly`), true);
      await evaluate('window.releaseDocumentSave()');
      await until(`${root}.getAttribute('aria-invalid')==='true'&&!${form}.hasAttribute('data-bml-action-pending')`);
      assert.equal(await evaluate(`${field}.value`), await evaluate('window.documentDraft'));
      assert.ok(await evaluate(`${root}===window.documentRoot&&${editor}===window.documentContent`));
      await evaluate(`document.querySelector('[data-document-reject=${id}]').click()`);
      await until(`!document.querySelector('[data-document-reject=${id}]').hasAttribute('checked')`);
      await evaluate(`${form}.requestSubmit()`);
      await until(`${root}.getAttribute('aria-invalid')==='false'&&document.querySelector('[data-document-toast=${id}] [role=status]')?.textContent==='Document saved.'`);
      await evaluate(`${editor}.focus()`); await call('Input.insertText', { text: ' unsaved' });
      await evaluate(`${form}.reset()`);
      await until(`${field}.value===window.documentDraft`);
      await evaluate(`${editor}.focus()`); await key('a', modifier);
      for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key:'Backspace',windowsVirtualKeyCode:8});
      assert.equal(await evaluate(`${field}.validity.valid`), false, 'Required checks document content, not nonempty JSON');
      await evaluate(`${form}.requestSubmit()`);
      assert.ok(await evaluate(`${root}.querySelector('[role=status]').textContent.includes('Enter document content')`));
      await evaluate(`${form}.reset()`); await until(`${field}.value===window.documentDraft`);
      await command('bulletList'); assert.ok(JSON.stringify(await json()).includes('"type":"bulletList"'));
      await command('undo'); assert.ok(!JSON.stringify(await json()).includes('"type":"bulletList"'));
      await command('redo'); assert.ok(JSON.stringify(await json()).includes('"type":"bulletList"'));
      await evaluate(`document.querySelector('[data-document-replace=${id}]').click()`);
      await until(`${editor}.textContent==='Replacement document.'&&${root}!==window.documentRoot`);
      await evaluate(`${editor}.focus()`); await key('a', modifier);
      // Every mark command changes the selected document, not only toolbar appearance.
      for (const mark of ['italic','underline','strike','code','superscript','subscript']) {
        await command(mark);
        assert.ok(JSON.stringify(await json()).includes('"type":"'+mark+'"'), mark);
        await command(mark);
      }
      await command('link');
      await evaluate(`${root}.querySelector('[data-bml-ref=url]').value='/components';${root}.querySelector('[data-bml-ref=apply]').click()`);
      assert.ok(JSON.stringify(await json()).includes('"href":"/components"'));
      await command('table');
      await until(`${editor}.querySelectorAll('tr').length===3`);
      await command('addRowAfter'); assert.equal(await evaluate(`${editor}.querySelectorAll('tr').length`), 4);
      await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))`);
      await evaluate(`${root}.querySelector('[data-bml-ref=block]').focus()`);
      await key('ArrowRight');
      assert.equal(await evaluate('document.activeElement.dataset.documentCommand'), 'bold', 'Keyboard entry reaches the formatting buttons from the initial select');
      await key('ArrowRight');
      assert.equal(await evaluate('document.activeElement.dataset.documentCommand'), 'italic');
      await key('Home');
      assert.equal(await evaluate(`document.activeElement===${root}.querySelector('[data-bml-ref=block]')`), true);
      assert.equal(await evaluate(`${root}.querySelectorAll('[role=toolbar] [tabindex="0"]').length`), 1);
      await key('Tab'); assert.equal(await evaluate(`document.activeElement===${editor}`), true,
        `Toolbar has one Tab stop: ${await evaluate('document.activeElement.outerHTML.slice(0,1000)')}`);
      await key('Escape'); await key('Tab');
      assert.equal(await evaluate(`${root}.contains(document.activeElement)`), false, 'Escape then Tab leaves the editor from a table');
      await evaluate(`document.querySelector('[data-document-readonly=${id}]').click()`);
      await until(`${field}.readOnly&&${editor}.getAttribute('aria-readonly')==='true'`);
      assert.equal(await evaluate(`new FormData(${form}).has('document')`), true);
      await evaluate(`document.querySelector('[data-document-disabled=${id}]').click()`);
      await until(`${field}.disabled`);
      assert.equal(await evaluate(`new FormData(${form}).has('document')`), false);
      await evaluate(`document.querySelector('[data-document-disabled=${id}]').click()`);
      await until(`!${field}.disabled`);
      await evaluate(`document.querySelector('[data-document-readonly=${id}]').click()`);
      await until(`!${field}.readOnly&&!${field}.disabled`);
      await evaluate(`window.oldDocumentContent=${editor};document.querySelector('[data-document-visible=${id}]').click()`);
      await until(`!window.oldDocumentContent.isConnected`);
      await evaluate(`document.querySelector('[data-document-visible=${id}]').click()`);
      await until(`${root}?.hasAttribute('data-document-ready')`);
      assert.equal(await evaluate(`${editor}.textContent`), 'Replacement document.');
      assert.equal(await evaluate(`document.querySelector('[data-catalog-document=${id==='dark'?'light':'dark'}] textarea').value`), originalSibling);
      const unsupported = await evaluate(`(()=>{const root=document.querySelector('[data-document-unsupported]');return {
        value:root.querySelector('textarea').value,error:root.querySelector('[role=status]').textContent,
        editable:root.querySelector('.tiptap').isContentEditable};})()`);
      assert.ok(unsupported.value.includes('customBlock') && unsupported.error.includes('Unsupported document node') && !unsupported.editable);
      await evaluate('document.fonts.ready');
      const colors = await evaluate(`(${contrastSamples.toString()})('[data-document-palette=${id}] .tiptap p,[data-document-palette=${id}] .button-content')`);
      for (const text of colors.text) assert.ok(text.ratio >= 4.5, JSON.stringify(text));
      assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'));
      reports.push({ width, id, value: await json(), unsupported, colors: colors.text });
      await evaluate(`${editor}.scrollIntoView({block:'center'})`);
      const screenshot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `catalog-${mode}-document-${id}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
    }
    await call('Emulation.setScriptExecutionDisabled', { value: true });
    await navigate('/documents');
    assert.ok(await evaluate(`(()=>{const field=document.querySelector('[data-document-form=dark] textarea');return !field.hidden&&JSON.parse(field.value).type==='doc'&&!document.querySelector('.tiptap');})()`));
  } finally {
    await call('Emulation.setScriptExecutionDisabled', { value: false });
    await writeFile(join(artifacts, `catalog-${mode}-documents.json`), JSON.stringify(reports, null, 2));
  }
}
