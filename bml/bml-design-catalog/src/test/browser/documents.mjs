import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

export async function checkDocuments({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const reports = [];
  const pointerClick = async expression => {
    const point = await evaluate(`(()=>{const element=${expression};element.scrollIntoView({block:'center'});const rect=element.getBoundingClientRect();return {x:rect.left+rect.width/2,y:rect.top+rect.height/2};})()`);
    for (const type of ['mousePressed', 'mouseReleased']) await call('Input.dispatchMouseEvent', { type, ...point, button: 'left', clickCount: 1 });
  };
  const key = async (key, modifiers = 0) => {
    for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', {
      type, key, modifiers, windowsVirtualKeyCode: { a: 65, b: 66, z: 90, Tab: 9, Enter: 13, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39, Home: 36, End: 35, Escape: 27 }[key],
    });
  };
  try {
    for (const width of [1440, 390]) for (const id of ['dark', 'light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await navigate('/documents');
      await until(`document.querySelectorAll('[data-document-ready]:not([data-document-reference-example])').length===8&&document.querySelector('[data-document-form=${id}]')?.dataset.bmlActionBound==='true'`);
      assert.equal(await evaluate(`document.querySelectorAll('[data-bml-document-editor] [role=textbox][aria-expanded]').length`), 0);
      const metadata = await evaluate(`(() => {
        const root=document.querySelector('[data-metadata-document]');
        return {text:root.querySelector('.tiptap').textContent,
          value:JSON.parse(new FormData(document.querySelector('[data-metadata-document-form]')).get('metadataBody'))};
      })()`);
      assert.equal(metadata.text, 'A document from Bosca content.');
      assert.deepEqual(Object.keys(metadata.value).sort(), ['attrs','content','type']);
      const root = `document.querySelector('[data-catalog-document=${id}] [data-bml-document-editor]')`;
      const editor = `${root}.querySelector('.tiptap')`, field = `${root}.querySelector('textarea')`;
      const form = `document.querySelector('[data-document-form=${id}]')`;
      const command = name => evaluate(`${root}.querySelector('[data-document-command=${name}]').click()`);
      const json = () => evaluate(`JSON.parse(${field}.value)`);
      const modifier = await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2');
      const originalSibling = await evaluate(`document.querySelector('[data-catalog-document=${id==='dark'?'light':'dark'}] textarea').value`);
      await evaluate(`${editor}.scrollIntoView({block:'center'});${editor}.focus()`);
      await key('a', modifier); await call('Input.insertText', { text: '@av' });
      await until(`${root}.querySelectorAll('[role=option]').length===1`);
      await key('Escape');
      assert.ok(await evaluate(`${root}.querySelector('.bml-mention-menu').hidden&&document.activeElement===${editor}`));
      assert.equal((await json()).content[0].content[0].text, '@av');
      await key('a', modifier); await call('Input.insertText', { text: '@la' });
      await until(`${root}.querySelectorAll('[role=option]').length===2`);
      await key('ArrowDown'); await key('Enter');
      const mention = (await json()).content[0].content.find(node => node.type === 'mention');
      assert.equal(mention.attrs.id, 'example-collection'); assert.equal(mention.attrs.entityType, 'collection');
      assert.equal(mention.attrs.label, 'Launch collection'); assert.equal(mention.attrs.mentionSuggestionChar, '@');
      assert.ok(await evaluate(`${root}.querySelector('.bml-mention-menu').hidden&&document.activeElement===${editor}`));
      await evaluate(`${root}.style.setProperty('--document-height','140px');${root}.style.setProperty('--document-max-height','140px');${root}.scrollIntoView({block:'start'});${editor}.focus()`);
      await key('a', modifier); await call('Input.insertText', { text: '/' });
      await until(`${root}.querySelector('.document-commands')&&!${root}.querySelector('.document-commands').hidden`);
      await key('ArrowUp');
      assert.ok(await evaluate(`(()=>{const option=${root}.querySelector('.document-commands [aria-selected=true]'),box=option.getBoundingClientRect();return option.contains(document.elementFromPoint(box.x+box.width/2,box.y+box.height/2));})()`), 'The complete slash palette remains clickable outside a compact card');
      await key('Escape');
      await evaluate(`${root}.style.removeProperty('--document-height');${root}.style.removeProperty('--document-max-height')`);
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
      assert.equal(await evaluate(`${root}.querySelector('.document-save-status').textContent`), 'Draft');
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
      assert.ok(await evaluate(`${root}.querySelector('[id$="-error"]').textContent.includes('Enter document content')`));
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
        value:root.querySelector('textarea').value,error:root.querySelector('[id$="-error"]').textContent,
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
    for (const width of [1920, 1024, 768, 390]) for (const palette of ['dark', 'light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await navigate('/documents');
      const root = `document.querySelector('[data-catalog-document=stored-${palette}] [data-bml-document-editor]')`;
      const form = `document.querySelector('[data-document-form=stored-${palette}]')`;
      await until(`${root}?.hasAttribute('data-document-ready')&&${form}?.dataset.bmlActionBound==='true'`);
      let value = await evaluate(`JSON.parse(new FormData(${form}).get('document'))`);
      assert.deepEqual(value.content.filter(node=>node.type==='heading').map(node=>node.attrs.level), [1,2]);
      const container = value.content.find(node=>node.type==='container');
      assert.equal(container.attrs.version, 4);
      assert.deepEqual(container.attrs.references, ['GEN.1']);
      assert.equal(container.attrs.metadataId, 'related-content');
      assert.equal(container.attrs.renderer, 'card');
      assert.equal(container.content[0].content[1].attrs.mentionSuggestionChar, '@');
      assert.deepEqual(value.content.find(node=>node.type==='bible').attrs.usfm, ['GEN.1.1']);
      assert.equal(value.content.find(node=>node.type==='codeBlock').attrs.language, 'mermaid');
      const code = `${root}.querySelector('[data-document-code]')`;
      await until(`${code}?.hasAttribute('data-document-code-ready')`);
      await until(`${code}.querySelector('.document-diagram svg')`);
      const containerView = `${root}.querySelector('[data-document-container-view]')`;
      assert.equal(await evaluate(`${containerView}.querySelector('strong').textContent`), 'Main content');
      const expandedValue = await evaluate(`new FormData(${form}).get('document')`);
      await evaluate(`${containerView}.querySelector('[aria-expanded]').click()`);
      assert.equal(await evaluate(`${containerView}.querySelector('.document-container-content').hidden`), true);
      assert.equal(await evaluate(`new FormData(${form}).get('document')`), expandedValue);
      await evaluate(`${containerView}.querySelector('[aria-expanded]').click()`);
      await evaluate(`${code}.scrollIntoView({block:'center'});${code}.querySelector('.cm-content').focus()`);
      await call('Input.insertText', {text:'// Shared code\n'});
      const codeText = await evaluate(`JSON.parse(new FormData(${form}).get('document')).content.find(node=>node.type==='codeBlock').content[0].text`);
      assert.ok(codeText.includes('// Shared code\n'), 'CodeMirror edits update the document form value');
      const modifier = await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2');
      await key('z', modifier);
      await until(`!JSON.stringify(JSON.parse(new FormData(${form}).get('document')).content.find(node=>node.type==='codeBlock')).includes('// Shared code')`);
      assert.equal(await evaluate(`${root}.querySelector('[data-document-command=undo]').disabled`), true, 'Code undo consumes the outer document history');
      await key('z', modifier|8);
      await until(`JSON.stringify(JSON.parse(new FormData(${form}).get('document')).content.find(node=>node.type==='codeBlock')).includes('// Shared code')`);
      await evaluate(`${code}.querySelector('select').value='typescript';${code}.querySelector('select').dispatchEvent(new Event('change',{bubbles:true}))`);
      await until(`JSON.parse(new FormData(${form}).get('document')).content.find(node=>node.type==='codeBlock').attrs.language==='typescript'`);
      await evaluate(`${code}.querySelector('.cm-content').focus()`);
      await key('Escape'); await key('Tab');
      assert.ok(await evaluate(`document.activeElement===${form}.querySelector('button[type=submit]')`), 'Code editor exits to the form action');
      value = await evaluate(`JSON.parse(new FormData(${form}).get('document'))`);
      await evaluate(`window.storedRoot=${root};window.storedSurface=${root}.querySelector('.tiptap');
        window.storedMention=null;${root}.addEventListener('documentmentionactivate',event=>window.storedMention=event.detail);
        ${root}.querySelector('[data-mention-id]').click()`);
      assert.deepEqual(await evaluate('window.storedMention'), {id:'profile-reference',entityType:'profile'});
      await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{
        if(String(args[0]).includes('/_bml/action')) {window.fetch=original;await new Promise(resolve=>window.releaseStoredDocumentSave=resolve);}
        return original(...args);};${form}.requestSubmit();})()`);
      await until(`${code}.querySelector('.cm-content').getAttribute('contenteditable')==='false'&&${code}.querySelector('select').disabled`);
      await evaluate(`${code}.querySelector('.cm-content').focus()`);
      await key('z', modifier);
      assert.deepEqual(await evaluate(`JSON.parse(new FormData(${form}).get('document'))`), value, 'Pending code history cannot mutate the submitted document');
      await evaluate('window.releaseStoredDocumentSave()');
      await until(`document.querySelector('[data-catalog-document=stored-${palette}] [data-stored-document-saved]')`);
      const saved = await evaluate(`JSON.parse(document.querySelector('[data-catalog-document=stored-${palette}] [data-stored-document-saved]').textContent)`);
      assert.deepEqual(saved, value);
      await until(`${code}.querySelector('.cm-content').getAttribute('contenteditable')==='true'&&!${code}.querySelector('select').disabled`);
      assert.ok(await evaluate(`${root}===window.storedRoot&&${root}.querySelector('.tiptap')===window.storedSurface`));
      await evaluate(`${root}.scrollIntoView({block:'center'});document.fonts.ready`);
      assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&${root}.getAttribute('aria-invalid')==='false'`));
      const screenshot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `catalog-${mode}-stored-document-${palette}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
      reports.push({width,palette,storedRoundTrip:true,titleNormalized:true,mentionActivation:true});
    }
    for (const width of [1920, 1024, 768, 390]) for (const palette of ['dark', 'light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await navigate('/documents');
      const host = `document.querySelector('[data-catalog-document=references-${palette}]')`;
      const root = `${host}.querySelector('[data-bml-document-editor]')`;
      const metadata = `${root}.querySelector('[data-document-container=RESOURCE]')`;
      const scripture = `${root}.querySelector('[data-document-container=SCRIPTURE]')`;
      const value = () => evaluate(`JSON.parse(${root}.querySelector('textarea').value)`);
      await until(`${metadata}?.textContent.includes('Example article')&&${scripture}?.querySelector('.document-scripture-attribution')?.textContent.includes('Catalog fixture')`);
      assert.equal(await evaluate(`${scripture}.querySelector('.scripture-verse').textContent`), '1');
      assert.equal(await evaluate(`${scripture}.querySelector('.scripture-inline').tagName`), 'SPAN');
      await evaluate(`${metadata}.querySelector('input').value='notes';${metadata}.querySelector('input').dispatchEvent(new Event('input',{bubbles:true}))`);
      await until(`${metadata}.querySelector('select option[value="example-notes"]')`);
      await evaluate(`${metadata}.querySelector('select').value='example-notes';${metadata}.querySelector('select').dispatchEvent(new Event('change',{bubbles:true}))`);
      await until(`${metadata}.querySelector('.document-reference-selected').textContent.includes('Example notes')`);
      let body = await value(), stored = body.content.find(node => node.attrs?.name === 'RESOURCE');
      assert.equal(stored.attrs.metadataId, 'example-notes'); assert.equal(stored.attrs.version, 4); assert.equal(stored.attrs.renderer, 'card');
      await evaluate(`${metadata}.querySelector('input').value='error';${metadata}.querySelector('input').dispatchEvent(new Event('input',{bubbles:true}))`);
      await until(`${metadata}.querySelector('select').getAttribute('aria-invalid')==='true'`);
      assert.deepEqual(await value(), body, 'Lookup errors retain the stored document');
      await evaluate(`${scripture}.querySelector('input').value='Genesis 1:2';${scripture}.querySelector('input').dispatchEvent(new Event('input',{bubbles:true}))`);
      await until(`${scripture}.querySelectorAll('select')[1].querySelector('option[value="GEN.1.2"]')`);
      await evaluate(`${scripture}.querySelectorAll('select')[1].value='GEN.1.2';${scripture}.querySelectorAll('select')[1].dispatchEvent(new Event('change',{bubbles:true}))`);
      await until(`${scripture}.querySelectorAll('.document-scripture-reference').length===2`);
      assert.deepEqual((await value()).content.find(node => node.attrs?.name === 'SCRIPTURE').attrs.references, ['GEN.1.1', 'GEN.1.2']);
      await evaluate(`${scripture}.querySelector('[aria-label="Remove GEN.1.1 reference"]').click()`);
      await until(`${scripture}.querySelectorAll('.document-scripture-reference').length===1`);
      body = await value();
      await evaluate(`${host}.querySelector('details').open=true;${host}.querySelector('[data-document-readonly]').click()`);
      await until(`${root}.querySelector('.tiptap').getAttribute('contenteditable')==='false'&&${metadata}.querySelector('.document-reference-controls').hidden`);
      assert.deepEqual(await value(), body);
      assert.ok(await evaluate(`[...${scripture}.querySelectorAll('[data-reference-edit]')].every(button=>button.hidden&&button.disabled)`));
      await evaluate(`${scripture}.scrollIntoView({block:'center'})`);
      assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&${scripture}.getBoundingClientRect().width>0`));
      const screenshot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `catalog-${mode}-reference-document-${palette}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
      reports.push({ width, palette, metadataSelection:true, retainedVersion:true, scriptureReferences:true, suppliedAttribution:true, readonly:true });
    }
    for (const width of [1920, 1024, 768, 390]) for (const palette of ['dark', 'light']) {
      await call('Network.clearBrowserCookies');
      await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await navigate('/documents');
      const host = `document.querySelector('[data-catalog-document=images-${palette}]')`;
      const root = `${host}.querySelector('[data-document-ready]')`;
      const picker = `${root}.querySelector('[data-image-picker-ready]')`;
      const panel = `${picker}.querySelector('dialog')`;
      const ref = name => `${picker}.querySelector('[data-bml-ref=image${name}]')`;
      await until(`${picker}&&!${root}.querySelector('[data-document-command=image]').disabled`);
      await evaluate(`${root}.querySelector('[data-document-command=image]').click()`); await until(`${panel}.open`);
      await evaluate(`${picker}.querySelector('.select-trigger').click()`);
      await until(`${picker}.querySelector('[role=option]')?.textContent==='Illustrative diagram'`);
      await pointerClick(`${picker}.querySelector('[role=option]')`);
      await until(`!${ref('Apply')}.disabled&&${ref('Image')}.naturalWidth===320`);
      assert.equal(await evaluate(`${ref('CancelUpload')}.checkVisibility()`), false, 'No cancel-upload control is shown for a library selection');
      assert.equal(await evaluate(`${picker}.querySelector('.select-trigger').disabled`), false);
      await evaluate(`${picker}.querySelector('.select-trigger').click()`);
      await until(`${picker}.querySelector('[role=option]')?.checkVisibility()`);
      await pointerClick(`${picker}.querySelector('[role=option]')`);
      await until(`!${ref('Apply')}.disabled&&!${picker}.querySelector('.select-trigger').disabled`);
      await evaluate(`${ref('Alt')}.value='Illustrative workflow';${ref('Title')}.value='Workflow diagram';${panel}.scrollIntoView({block:'center'})`);
      assert.ok(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&${panel}.getBoundingClientRect().left>=0&&${panel}.getBoundingClientRect().right<=innerWidth`));
      const screenshot = await call('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(artifacts, `catalog-${mode}-image-picker-${palette}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
      await evaluate(`${ref('Apply')}.click()`); await until(`!${panel}.open&&${root}.querySelector('[data-document-image] img')?.naturalWidth===320`);
      const image = await evaluate(`JSON.parse(${root}.querySelector('textarea').value).content.find(node=>node.type==='image')`);
      assert.equal(image.attrs.metadataId, 'catalog-diagram'); assert.equal(image.attrs.alt, 'Illustrative workflow'); assert.equal(image.attrs.title, 'Workflow diagram');
      await evaluate(`${root}.querySelector('[data-document-command=undo]').click()`);
      await until(`!JSON.parse(${root}.querySelector('textarea').value).content.some(node=>node.type==='image')`);
      await evaluate(`${root}.querySelector('[data-document-command=redo]').click()`); await until(`${root}.querySelector('[data-document-image] img')?.naturalWidth===320`);
      await evaluate(`${root}.querySelector('[data-document-command=image]').click()`); await until(`${panel}.open`);
      await evaluate(`${host}.querySelector('form').requestSubmit()`);
      await until(`!${panel}.open&&!${host}.querySelector('form').hasAttribute('data-bml-action-pending')&&!${root}.querySelector('[data-document-command=image]').disabled`);
      await evaluate(`${root}.querySelector('[data-document-command=image]').click()`); await until(`${panel}.open`);
      await evaluate(`${host}.querySelector('details[data-example-states]').open=true;${host}.querySelector('[data-document-readonly]').click()`);
      await until(`!${panel}.open&&${root}.querySelector('.tiptap').getAttribute('contenteditable')==='false'`);
      reports.push({width,palette,imagePicker:true,selectedIdentity:true,description:true,undo:true});
    }
    await call('Emulation.setScriptExecutionDisabled', { value: true });
    await navigate('/documents');
    assert.ok(await evaluate(`(()=>{const field=document.querySelector('[data-document-form=dark] textarea');return !field.hidden&&JSON.parse(field.value).type==='doc'&&!document.querySelector('.tiptap');})()`));
  } finally {
    await call('Emulation.setScriptExecutionDisabled', { value: false });
    await writeFile(join(artifacts, `catalog-${mode}-documents.json`), JSON.stringify(reports, null, 2));
  }
}
