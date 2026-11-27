import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import {launchTestBrowser} from '../../../../../gradle/bml-test-browser.mjs';
const require=createRequire(resolve('../../bml/bml-runtime/package.json'));
const {build}=require('esbuild');
const server=new URL(process.env.WORKOPS_COLLABORATION_URL),api=process.env.WORKOPS_CONTENT_URL;
assert.equal(server.hostname,'127.0.0.1');
const origin=`http://${server.host}`,room='11111111-2222-4333-8444-555555555555.3';
const bundle=await build({stdin:{contents:`import {mountDocumentEditor} from './src/main/client/helpers/document-editor.ts';
 const root=document.querySelector('[data-bml-document-editor]'),abort=new AbortController(),unmount=[],updates=[];
 window.submissions=0;document.querySelector('form').addEventListener('submit',event=>{event.preventDefault();window.submissions++;});
 root.addEventListener('documenttokenrequest',event=>event.detail.respondWith(window.pendingCredential??Promise.resolve(window.credential)));
 window.inputs=[];root.addEventListener('beforeinput',event=>window.inputs.push({data:event.data,type:event.inputType,anchor:document.getSelection()?.anchorNode?.nodeName,offset:document.getSelection()?.anchorOffset}));
 const ctx={root,id:'fixture',ref:name=>root.querySelector('[data-ref="'+name+'"]'),
 on:(target,name,handler,options)=>target.addEventListener(name,handler,typeof options==='boolean'?{capture:options,signal:abort.signal}:{...options,signal:abort.signal}),
 onUpdate:callback=>updates.push(callback),onUnmount:callback=>unmount.push(callback)};
 window.patch=attributes=>{for(const [key,value]of Object.entries(attributes))root.dataset[key]=value;updates.forEach(callback=>callback());};
 window.removeEditor=()=>{unmount.forEach(callback=>callback());abort.abort();root.remove();};
 mountDocumentEditor(ctx);`,resolveDir:resolve('../../bml/bml-design')},bundle:true,write:false,outfile:'editor.js',format:'iife',platform:'browser'});
const js=bundle.outputFiles.find(file=>file.path.endsWith('.js')).text,css=bundle.outputFiles.find(file=>file.path.endsWith('.css')).text;
const owned=await launchTestBrowser(0);await owned.connection.contexts()[0].close();
const context=await owned.connection.newContext(),errors=[];
const page=async(name,credential,delayed=false)=>{
 const tab=await context.newPage();
 tab.on('pageerror',error=>errors.push(error.message));tab.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
 const document=`<!doctype html><html><head><link rel="icon" href="data:,"><style>${css}</style></head><body><form>
 <div class="document-editor" data-bml-document-editor data-collaboration-room="${room}" data-collaboration-url="/collaboration" data-collaborator-name="${name}" data-name="document" data-value='{"type":"doc","content":[{"type":"paragraph"}]}' data-single-title>
 <p data-ref="connection" hidden></p><input data-ref="revision" type="hidden" name="documentRevision">
 <div data-ref="toolbar"><button type="button" data-document-command="bold">Bold</button><button type="button" data-document-command="undo">Undo</button><button type="button" data-document-command="redo">Redo</button><select data-ref="block"><option value="paragraph">Paragraph</option><option value="1">Heading 1</option><option value="2">Heading 2</option></select><select data-ref="alignment"><option value="left">Left</option></select></div>
 <div data-ref="insert" hidden><input data-ref="url"></div><textarea data-ref="value" name="document"></textarea><div data-ref="editor"></div><p data-ref="feedback" hidden></p></div>
 <button type="submit">Save</button></form><script>window.credential=${JSON.stringify(credential)};${delayed?'window.pendingCredential=new Promise(resolve=>window.releaseCredential=resolve);':''}</script><script>${js.replaceAll('</script','<\\/script')}</script></body></html>`;
 assert.equal((await fetch(origin+'/editor',{method:'POST',body:document})).status,202);
 await tab.goto(origin+'/editor');
 if(delayed){
  await tab.locator('[data-document-connection=connecting]').waitFor();
  await tab.getByRole('button',{name:'Save',exact:true}).click();
  assert.equal(await tab.evaluate(()=>window.submissions),0,'a connecting editor blocks native form submission');
  await tab.evaluate(()=>window.releaseCredential(window.credential));
 }
 try{await tab.locator('[data-document-ready]').waitFor({timeout:8000});}
 catch(error){throw new Error(`${error.message}\n${await tab.locator('body').innerText()}\n${JSON.stringify(errors)}`);}
 return tab;
};
const value=tab=>tab.evaluate(()=>JSON.parse(new FormData(document.querySelector('form')).get('document')));
const until=async(check,label)=>{const deadline=Date.now()+8000;while(Date.now()<deadline){if(await check())return;await new Promise(resolve=>setTimeout(resolve,20));}
 throw new Error('Timed out: '+label+'\n'+JSON.stringify(await Promise.all(context.pages().map(async tab=>({value:await value(tab),status:await tab.locator('[data-ref=connection]').textContent(),feedback:await tab.locator('[data-ref=feedback]').textContent()}))))+'\n'+JSON.stringify(errors));};
try{
 const a=await page('Alice','editor-a-new',true),b=await page('Carol','editor-c');
 for(const tab of [a,b]){
  const doc=await value(tab);assert.equal(doc.content.length,3);assert.equal(doc.content[0].content[0].text,'Existing document');assert.deepEqual(doc.content[2].attrs.references,['one','two']);
  assert.equal(await tab.locator('[data-document-dirty]').count(),0,'initial sync and normalization preserve clean state');
 }
 await a.bringToFront();await a.locator('.ProseMirror > p').click();await a.keyboard.press('End');await a.keyboard.type(' from Alice');
 await until(async()=>JSON.stringify(await value(b)).includes('from Alice'),'remote document edit');
 for(const tab of [a,b])await tab.locator('[data-document-dirty]').waitFor();
 const captured=await a.locator('input[name=documentRevision]').inputValue();assert.notEqual(captured,'{}');
 await b.bringToFront();await b.locator('.ProseMirror > p').click();await b.keyboard.press('End');await b.keyboard.type(' from Carol');
 assert.ok(JSON.stringify(await value(b)).includes('from Carol'),`Local browser edit: ${await b.locator('body').innerText()}; ${await b.evaluate(()=>JSON.stringify({inputs:window.inputs,selection:document.getSelection()?.toString(),anchor:document.getSelection()?.anchorNode?.parentElement?.outerHTML,offset:document.getSelection()?.anchorOffset}))}`);
 await until(async()=>JSON.stringify(await value(a)).includes('from Carol'),'concurrent save edit');
 await a.evaluate(revision=>window.patch({savedRevision:revision}),captured);
 assert.equal(await a.locator('[data-document-dirty]').count(),1,'acknowledging an older save retains newer edits');
 const current=await a.locator('input[name=documentRevision]').inputValue();
 await a.evaluate(revision=>window.patch({savedRevision:revision}),current);
 await until(async()=>await b.locator('[data-document-dirty]').count()===0,'remote save revision acknowledgement');
 await a.evaluate(()=>{document.querySelector('form').setAttribute('data-bml-action-pending','');});
 await until(async()=>await a.locator('.ProseMirror').getAttribute('contenteditable')==='false','pending locks local editor');
 await b.bringToFront();await b.locator('.ProseMirror > p').click();await b.keyboard.press('End');await b.keyboard.type(' while saving');
 await until(async()=>JSON.stringify(await value(a)).includes('while saving'),'remote updates still arrive while pending');
 await a.evaluate(()=>document.querySelector('form').removeAttribute('data-bml-action-pending'));
 await until(async()=>await a.locator('.ProseMirror').getAttribute('contenteditable')==='true','pending releases local editor');
 await b.evaluate(()=>window.removeEditor());await b.close();
 await a.evaluate(()=>{window.credential='editor-a-new';document.querySelector('[data-bml-document-editor]').dispatchEvent(new Event('documentcredentialschange'));});
 await a.locator('[data-document-connection=connected]').waitFor();
 await fetch(api+'/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tokens:['editor-c']})});
 await a.locator('[data-document-connection=denied]').waitFor();
 assert.equal(await a.locator('.ProseMirror').getAttribute('contenteditable'),'false','permission loss locks the document');
 await fetch(api+'/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tokens:['editor-a-new','editor-c']})});
 await a.evaluate(()=>{window.pendingCredential=new Promise(resolve=>window.releaseCredential=resolve);document.querySelector('[data-bml-document-editor]').dispatchEvent(new Event('documentcredentialschange'));});
 assert.equal(await a.locator('.ProseMirror').getAttribute('contenteditable'),'false','credential refresh keeps a denied document locked until authorization succeeds');
 await a.evaluate(()=>window.releaseCredential(window.credential));
 await a.locator('[data-document-connection=connected]').waitFor();
 assert.ok(JSON.stringify(await value(a)).includes('while saving'),'re-authentication preserves edits');
 await a.evaluate(()=>window.removeEditor());
 await until(async()=>(await(await fetch(api+'/control')).json()).rooms===0,'unmount releases the server room');
 assert.deepEqual(errors,[]);process.stdout.write('Shared BML document editor: real WorkOps server, concurrent edits, save revisions, pending, credentials, revocation and unmount passed.\n');
}finally{await context.close();await owned.close();}
