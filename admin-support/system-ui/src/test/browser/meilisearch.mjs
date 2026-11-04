import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';

let base = process.env.ATLAS_SYSTEM_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use an isolated local Atlas fixture.');
const password = process.env.ATLAS_SYSTEM_TEST_PASSWORD;
assert.ok(password);
const reports = resolve('build/reports/meilisearch-browser');
await mkdir(reports, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'atlas-system-chrome-'));
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const chrome = spawn(process.env.ATLAS_CHROME_BINARY ?? (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome'), ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let ws, launchError, failureCapture;
chrome.on('error', error => { launchError = error; });
const errors = [], actionRequests = [];
try {
  let targets;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launchError) throw launchError;
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); if (targets.some(item => item.type === 'page')) break; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(targets?.some(item => item.type === 'page'), 'Chrome did not start');
  ws = new WebSocket(targets.find(item => item.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  let sequence = 0;
  const pending = new Map();
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Network.requestWillBeSent' && message.params.request.url.endsWith('/_bml/action')) {
      const request = JSON.parse(message.params.request.postData);
      actionRequests.push({method:request.method,args:request.args,stateKey:request.stateKey});
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
    if (!message.id) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id); clearTimeout(request.timeout);
    message.error ? request.reject(message.error) : request.resolve(message.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 30000);
    pending.set(id, { resolve, reject, timeout }); ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  failureCapture=async()=>{await writeFile(join(reports,'failure.png'),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));};
  const until = async (expression, label = expression) => {
    for (let attempt = 0; attempt < 150; attempt++) { if (await evaluate(`Boolean(${expression})`)) return; await new Promise(resolve => setTimeout(resolve, 100)); }
    throw new Error(`Timed out: ${label}. Errors: ${JSON.stringify(errors)}. Actions: ${JSON.stringify(actionRequests.slice(-8))}. Pending: ${await evaluate('Array.from(document.querySelectorAll("[data-bml-action-pending]"), e=>e.outerHTML.slice(0,300))')}. Page: ${await evaluate('document.body.innerText.slice(-6000)')}`);
  };
  const go = async path => {
    await call('Page.navigate', { url: base + path });
    await until(`location.pathname === ${JSON.stringify(path.split('?')[0])} && document.readyState === 'complete' && typeof boscaAuth !== 'undefined'`);
    assert.equal(await evaluate("document.body.innerText.includes('Unable to load this page')"), false, path);
    await evaluate('document.fonts.ready.then(() => true)');
  };
  const click = async selector => {
    await until("!document.querySelector('[data-bml-action-pending]')", 'Previous action finished before the next native interaction');
    await evaluate(`document.querySelector(${JSON.stringify(selector)})?.scrollIntoView({block:"center"})`);
    await evaluate("new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
    const rect = await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); if (!element) throw new Error('Missing ${selector}'); element.scrollIntoView({block:'center'}); const r = element.getBoundingClientRect(); const x=r.x+r.width/2,y=r.y+r.height/2; const hit=document.elementFromPoint(x,y); if(hit!==element&&!element.contains(hit)) throw new Error('Click blocked: '+element.outerHTML.slice(0,300)+' by '+hit?.outerHTML.slice(0,300)); return {x,y}; })()`);
    await call('Input.dispatchMouseEvent', { type: 'mousePressed', ...rect, button: 'left', clickCount: 1 });
    await call('Input.dispatchMouseEvent', { type: 'mouseReleased', ...rect, button: 'left', clickCount: 1 });
  };
  const clickText = async (text, selector = 'button') => {
    await until("!document.querySelector('[data-bml-action-pending]')", 'Previous action finished before locating '+text);
    const target = await evaluate(`(() => { const element = [...document.querySelectorAll(${JSON.stringify(selector)})].find(item => item.textContent.trim() === ${JSON.stringify(text)} && item.getBoundingClientRect().width); if (!element) throw new Error('Missing control: '+${JSON.stringify(text)}); element.dataset.securityTestTarget='true'; return '[data-security-test-target]'; })()`);
    await click(target); await evaluate("document.querySelector('[data-security-test-target]')?.removeAttribute('data-security-test-target')");
  };
  const fill = async (selector, value) => {
    await click(selector);
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: process.platform === 'darwin' ? 4 : 2, windowsVirtualKeyCode: 65, commands: ['selectAll'] });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65 });
    if (value) await call('Input.insertText', { text: value });
    else {
      await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
      await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    }
    const field = await evaluate(`(() => { const input = document.querySelector(${JSON.stringify(selector)}); return {value:input.value,focused:document.activeElement===input,disabled:input.disabled,readOnly:input.readOnly}; })()`);
    assert.equal(field.value, value, `Native field ${selector}: ${JSON.stringify(field)}`);
  };
  const escape = async () => { await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); };
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable'); await call('Page.bringToFront');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await go('/login');
  await fill('input[name=identifier]', 'admin'); await fill('input[name=password]', password); await click('button[type=submit]');
  await until("location.pathname === '/' && document.querySelector('.atlas-home-main')");
  const measurements=[];
  const state=`JSON.parse(document.querySelector('script[data-bml-state-key^="system-meilisearch.model"]').textContent)`;
  const drawer='[data-meili-index-panel] dialog[open]', confirmation='[data-meili-confirmation] dialog[open]';
  const nodeEditor='[data-meili-node-editor] dialog[open]', indexEditor='[data-meili-create-index] dialog[open]', embedderEditor='[data-meili-embedder-editor] dialog[open]', assignment='[data-meili-assign-storage] dialog[open]';
  const meiliUrl=process.env.ATLAS_MEILI_URL, provider=process.env.ATLAS_MEILI_PROVIDER;
  for(const origin of [meiliUrl,provider]) assert.ok(origin&&['localhost','127.0.0.1'].includes(new URL(origin).hostname));
  const read=async path=>{const result=await fetch(meiliUrl+path,{headers:{Authorization:'Bearer '+process.env.ATLAS_MEILI_KEY}});assert.ok(result.ok,await result.clone().text());return result.json();};
  const control=async(path,host)=>{const result=await fetch(`${provider}/${path}?host=${host}`);assert.ok(result.ok);return result.json();};
  const waitTask=async(expected='succeeded')=>{
    const notice=await evaluate(`${state}.operationNotice`),uid=Number([...notice.matchAll(/task #(\d+)/g)].at(-1)?.[1]);assert.ok(Number.isInteger(uid),notice);
    for(let i=0;i<300;i++){const task=await read('/tasks/'+uid);if(task.status===expected){
      const dismiss='[data-meili-notice]:popover-open button[aria-label="Dismiss notification"]';
      if(await evaluate(`document.querySelector(${JSON.stringify(dismiss)})&&!document.querySelector('dialog[open]')`))await click(dismiss);
      return uid;
    }assert.ok(!['failed','canceled'].includes(task.status),JSON.stringify(task));await new Promise(resolve=>setTimeout(resolve,100));}
    throw Error('Task did not complete: '+notice);
  };
  const capture=async(host,name,selector)=>{
    await until(`document.querySelector(${JSON.stringify(selector)})`);
    for(const width of [1920,1024,768,390]){
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<768});
      await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'start'})`);
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      const bounds=await evaluate(`(() => {const panel=[...document.querySelectorAll('dialog[open]')].at(-1), footer=panel?.querySelector('.modal-footer'), box=panel?.getBoundingClientRect(), actions=footer?.getBoundingClientRect();return {width:innerWidth,theme:document.body.dataset.theme,scrollWidth:document.documentElement.scrollWidth,dialogWidth:box?.width??0,drawer:panel?.dataset.placement==='right',drawerTop:box?.top,drawerRight:box?.right,drawerHeight:box?.height,footerVisible:!actions||actions.top>=box.top&&actions.bottom<=Math.min(innerHeight,box.bottom)+2};})()`);
      assert.ok(bounds.scrollWidth<=width+2&&bounds.dialogWidth<=width+2,JSON.stringify(bounds));
      if(bounds.drawer)assert.ok(Math.abs(bounds.drawerTop)<2&&Math.abs(bounds.drawerRight-width)<2&&Math.abs(bounds.drawerHeight-1000)<2,JSON.stringify(bounds));
      assert.ok(bounds.footerVisible,JSON.stringify(bounds));
      assert.ok(await evaluate("[...document.querySelectorAll('[data-resource-name]')].every(name=>!name.closest('button,a,[role=button]')&&!name.closest('td').querySelector('button,a,[role=button]'))"),'Resource names are plain table text with separate actions');
      await writeFile(join(reports,`${host}-${name}-${width}.png`),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));measurements.push({host,name,...bounds});
    }
  };
  const tab=async value=>{await click(`[data-meilisearch-tabs] [data-tab="${value}"]`);await until(`${state}.tab===${JSON.stringify(value)}&&!document.querySelector('#meili-panel-${value}').hidden`);};
  const indexTab=async value=>{await click(`${drawer} [data-tab="${value}"]`);await until(`${state}.indexTab===${JSON.stringify(value)}&&!document.querySelector('#meili-index-panel-${value}').hidden`);};
  const confirm=async()=>{await until(`document.querySelector('${confirmation}')`);await click(`${confirmation} input[name=confirmed]`);await click(`${confirmation} button[type=submit]`);await until(`${state}.operationCompleted&&!document.querySelector('${confirmation}')`);};
  const choose=async value=>{await click(`${embedderEditor} button[aria-label="Source"]`);await clickText(value,'[role=option]');await until(`${state}.embedderDraft.source===${JSON.stringify(value)}&&!document.querySelector('[data-bml-action-pending]')`);};
  const fillJson=async(name,value)=>{
    const selector=`${embedderEditor} [data-bml-json-editor][data-name="${name}"] .cm-content[contenteditable=true]`;
    await until(`document.querySelector(${JSON.stringify(selector)})`);await click(selector);
    await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',modifiers:process.platform==='darwin'?4:2,windowsVirtualKeyCode:65,commands:['selectAll']});
    await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});await call('Input.insertText',{text:value});
    await until(`document.querySelector('${embedderEditor} textarea[name="${name}"]').value===${JSON.stringify(value)}`);
  };
  const saveEditor=async selector=>{await click(`${selector} button[type=submit]`);await until(`${state}.editorCompleted&&!document.querySelector(${JSON.stringify(selector)})`);};
  for(const [host,origin] of [base,process.env.ATLAS_MEILI_STANDALONE_URL].entries()){
    assert.ok(origin&&['localhost','127.0.0.1'].includes(new URL(origin).hostname));base=origin;
    await call('Network.setCookie',{name:'git_ui_appearance',value:host?'light':'dark',url:base});
    await go('/system/meilisearch');await until(`${state}.overview&&document.querySelector('[data-meilisearch-console]')`);
    const uid=`system_ui_meili_${host+2}`,created=`system_ui_browser_created_${host}`,nodeName=`Browser Meilisearch ${host}`;
    await capture(host,'nodes-empty','#meili-panel-nodes');
    await clickText('Add Node');await until(`document.querySelector('${nodeEditor}')`);await fill(`${nodeEditor} input[name=name]`,nodeName);await fill(`${nodeEditor} input[name=description]`,'Browser-owned search node');await fill(`${nodeEditor} input[name=url]`,meiliUrl);await fill(`${nodeEditor} input[name=key]`,process.env.ATLAS_MEILI_KEY);
    await capture(host,'node-create',nodeEditor);await saveEditor(nodeEditor);
    const nodeId=await evaluate(`${state}.overview.nodes.find(node=>node.name===${JSON.stringify(nodeName)}).id`),card=`[data-meili-node-card="${nodeId}"]`;
    await until(`${state}.overview.nodes.find(node=>node.id===${JSON.stringify(nodeId)}).healthy`);await capture(host,'nodes-populated',card);
    await click(`[data-meili-node="${nodeId}"] [data-meili-node-edit]`);await until(`document.querySelector('${nodeEditor}')`);
    assert.equal(await evaluate(`document.querySelector('${nodeEditor} input[name=name]').value`),nodeName);
    await clickText('Cancel',`${nodeEditor} button`);await until(`!document.querySelector('${nodeEditor}')`);
    await clickText('Edit',`${card} button`);await until(`document.querySelector('${nodeEditor}')`);assert.equal(await evaluate(`document.querySelector('${nodeEditor} input[name=key]').value`),'');await fill(`${nodeEditor} input[name=description]`,'Edited without replacing the key');await capture(host,'node-edit',nodeEditor);await saveEditor(nodeEditor);
    assert.ok(await evaluate(`${state}.overview.nodes.find(node=>node.id===${JSON.stringify(nodeId)}).healthy`),'Blank node key retains the encrypted credential');
    await clickText('Assign Storage',`${card} button`);await until(`document.querySelector('${assignment}')`);
    const storage=await evaluate(`${state}.availableStorage?.[0]??${state}.storageSystems.find(storage=>!${state}.overview.nodes.find(node=>node.id===${JSON.stringify(nodeId)}).storageSystemIds.includes(storage.id))`);assert.ok(storage);
    await click(`${assignment} button[aria-label="Storage System"]`);await clickText(storage.name,'[role=option]');await capture(host,'storage-assign',assignment);await saveEditor(assignment);
    assert.ok(await evaluate(`${state}.overview.nodes.find(node=>node.id===${JSON.stringify(nodeId)}).storageSystemIds.includes(${JSON.stringify(storage.id)})`));
    await click(`${card} button[aria-label=${JSON.stringify('Remove '+storage.name+' assignment')}]`);await capture(host,'storage-remove',confirmation);await clickText('Cancel',`${confirmation} button`);await until(`!document.querySelector('${confirmation}')`);
    await click(`${card} button[aria-label=${JSON.stringify('Remove '+storage.name+' assignment')}]`);await confirm();
    assert.ok(await evaluate(`!${state}.overview.nodes.find(node=>node.id===${JSON.stringify(nodeId)}).storageSystemIds.includes(${JSON.stringify(storage.id)})`));
    await tab('indexes');await capture(host,'indexes','#meili-panel-indexes');
    await clickText('Create Index'); await until(`document.querySelector('${indexEditor}')`); await escape();
    await until(`${state}.editor==='' && !document.querySelector('${indexEditor}')`);
    await clickText('Refresh','button'); await until(`!document.querySelector('[data-bml-action-pending]')`);
    assert.ok(!await evaluate(`document.querySelector('${indexEditor}')`),'A dismissed editor does not reopen on refresh');

    await clickText('Create Index');await until(`document.querySelector('${indexEditor}')`);await fill(`${indexEditor} input[name=uid]`,'invalid space');await fill(`${indexEditor} input[name=primaryKey]`,'id');await click(`${indexEditor} button[type=submit]`);
    await until(`${state}.editorFeedback.failed&&document.querySelector('${indexEditor}')`);assert.equal(await evaluate(`document.querySelector('${indexEditor} input[name=uid]').value`),'invalid space');await capture(host,'index-create-error',indexEditor);
    await fill(`${indexEditor} input[name=uid]`,created);await saveEditor(indexEditor);await waitTask();await clickText('Refresh','button');
    await until(`${state}.overview.indexes.some(index=>index.uid===${JSON.stringify(created)})`);
    await clickText('Create Index');await until(`document.querySelector('${indexEditor}')`);await fill(`${indexEditor} input[name=uid]`,created);await fill(`${indexEditor} input[name=primaryKey]`,'id');await saveEditor(indexEditor);const failedTask=await waitTask('failed');
    await click(`[data-meili-index="${created}"] button`);await until(`document.querySelector('${drawer}')&&${state}.indexUid===${JSON.stringify(created)}`);await clickText('Delete Index',`${drawer} button`);await capture(host,'index-delete',confirmation);await clickText('Cancel',`${confirmation} button`);await until(`!document.querySelector('${confirmation}')`);await clickText('Delete Index',`${drawer} button`);await confirm();await waitTask();
    await click(`[data-meili-index="${uid}"] button`);await until(`document.querySelector('${drawer}')&&${state}.documents.length===20`);await capture(host,'index-stats',drawer);
    await indexTab('settings');await capture(host,'index-settings','#meili-index-panel-settings');await clickText('Update Settings',`${drawer} button`);await until(`${state}.operationNotice.startsWith('Settings update queued')`);await waitTask();
    await indexTab('documents');await capture(host,'documents-first','#meili-index-panel-documents');await clickText('Next',`${drawer} button`);await until(`${state}.documentsOffset===20&&${state}.documents.length===5`);await capture(host,'documents-last','#meili-index-panel-documents');await clickText('Previous',`${drawer} button`);await until(`${state}.documentsOffset===0&&${state}.documents.length===20`);
    await indexTab('embedders');await capture(host,'embedders','#meili-index-panel-embedders');await clickText('Add Embedder',`${drawer} button`);await until(`document.querySelector('${embedderEditor}')`);
    await fill(`${embedderEditor} input[name=name]`,'third');await choose('userProvided');await fill(`${embedderEditor} input[name=dimensions]`,'2');await capture(host,'embedder-user-provided',embedderEditor);await saveEditor(embedderEditor);await waitTask();await clickText('Refresh index',`${drawer} button`);await until(`${state}.embedders.some(embedder=>embedder.name==='third')`);
    await clickText('Add Embedder',`${drawer} button`);await until(`document.querySelector('${embedderEditor}')`);await fill(`${embedderEditor} input[name=name]`,'rest');await choose('rest');
    await fill(`${embedderEditor} input[name=url]`,process.env['ATLAS_MEILI_PROVIDER_REMOTE_'+host]);await fill(`${embedderEditor} input[name=key]`,'fixture-embedder-key');await fill(`${embedderEditor} input[name=dimensions]`,'2');await fill(`${embedderEditor} input[name=documentTemplateMaxBytes]`,'400');await fill(`${embedderEditor} textarea[name=documentTemplate]`,'{{doc.title}}');
    await fillJson('request','{invalid');await click(`${embedderEditor} button[type=submit]`);await until(`${state}.editorFeedback.failed&&document.querySelector('${embedderEditor}')`);await capture(host,'embedder-invalid-json',embedderEditor);assert.equal(await evaluate(`document.querySelector('${embedderEditor} textarea[name=request]').value`),'{invalid');
    await choose('userProvided');assert.equal(await evaluate(`document.querySelector('${embedderEditor} form').checkValidity()`),true,'Inactive REST mappings cannot block another source');await choose('rest');assert.equal(await evaluate(`document.querySelector('${embedderEditor} textarea[name=request]').value`),'{invalid');
    await fillJson('request','{"input":"{{text}}"}');await fillJson('response','{"embedding":"{{embedding}}"}');await fillJson('headers','{"X-Fixture":"meilisearch"}');assert.equal(await evaluate(`document.querySelector('${embedderEditor}').innerText.includes('Do you want to format the JSON?')`),false,'Compact mapping fields do not open formatting prompts');await capture(host,'embedder-rest',embedderEditor);await saveEditor(embedderEditor);await waitTask();await clickText('Refresh index',`${drawer} button`);await until(`${state}.embedders.some(embedder=>embedder.name==='rest')`);
    await clickText('Edit',`[data-meili-embedder="rest"] button`);await until(`document.querySelector('${embedderEditor}')`);assert.equal(await evaluate(`document.querySelector('${embedderEditor} input[name=key]').value`),'');await fill(`${embedderEditor} textarea[name=documentTemplate]`,'Changed: {{doc.title}}');await capture(host,'embedder-edit',embedderEditor);await saveEditor(embedderEditor);await waitTask();
    await escape();await until(`!document.querySelector('dialog[open]')`);await tab('tasks');await capture(host,'tasks','#meili-panel-tasks');
    await control('hold',host);const pendingTask=await control('enqueue',host);for(let i=0;i<100;i++){if((await control('status',host)).held>0)break;await new Promise(resolve=>setTimeout(resolve,100));}
    await clickText('Refresh','button');await until(`${state}.overview.tasks.some(task=>task.uid===${pendingTask.target}&&task.status==='enqueued')`);
    await click(`[data-meili-task="${pendingTask.target}"] summary`);await clickText('Cancel task',`[data-meili-task="${pendingTask.target}"] button`);await capture(host,'task-cancel',confirmation);await confirm();await control('release',host);await waitTask();
    await clickText('Refresh','button');await until(`${state}.overview.tasks.some(task=>task.uid===${failedTask}&&task.error)`);await click(`[data-meili-task="${failedTask}"] summary`);await capture(host,'task-error',`[data-meili-task="${failedTask}"]`);
    await tab('keys');await capture(host,'keys','#meili-panel-keys');await tab('indexes');await click(`[data-meili-index="${uid}"] button`);await until(`document.querySelector('${drawer}')`);await indexTab('embedders');
    for(const name of ['first','third','rest','second']){
      await clickText('Refresh index',`${drawer} button`);await until(`${state}.embedders.some(embedder=>embedder.name===${JSON.stringify(name)})`);await clickText('Remove',`[data-meili-embedder="${name}"] button`);await until(`document.querySelector('${confirmation}')`);
      if(name==='first'){await capture(host,'embedder-remove',confirmation);await clickText('Cancel',`${confirmation} button`);await until(`!document.querySelector('${confirmation}')`);await clickText('Remove',`[data-meili-embedder="${name}"] button`);}
      await confirm();await waitTask();await clickText('Refresh index',`${drawer} button`);await until(`!${state}.embedders.some(embedder=>embedder.name===${JSON.stringify(name)})`);
    }
    await capture(host,'embedders-empty','#meili-index-panel-embedders');await clickText('Delete All Docs',`${drawer} button`);await capture(host,'documents-delete',confirmation);await confirm();await waitTask();await clickText('Refresh index',`${drawer} button`);await indexTab('documents');await until(`${state}.documentsTotal===0&&${state}.documents.length===0`);await capture(host,'documents-empty','#meili-index-panel-documents');await escape();await until(`!document.querySelector('dialog[open]')`);
    await tab('nodes');await clickText('Delete',`${card} button`);await capture(host,'node-delete',confirmation);await confirm();await until(`!${state}.overview.nodes.some(node=>node.id===${JSON.stringify(nodeId)})`);
    await clickText('Refresh','button');await until(`!document.querySelector('dialog[open]')`);assert.ok(!await evaluate('document.querySelector("dialog[open]")'),'Dismissed resources stay closed');
  }
  assert.deepEqual(errors,[]);await writeFile(join(reports,'checks.json'),JSON.stringify(measurements,null,2));console.log(`Meilisearch browser passed in both hosts: ${measurements.length} measurements.`);

} catch(failure) {
  try { await failureCapture?.(); } catch {}
  throw failure;
} finally {
  ws?.close();chrome.kill('SIGTERM');await new Promise(resolve=>chrome.once('exit',resolve));await rm(profile,{recursive:true,force:true});
}
