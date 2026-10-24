import assert from "node:assert/strict";
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const targets = await (
  await fetch(`${process.env.GIT_UI_CDP_URL}/json`)
).json();
const ws = new WebSocket(
  targets.find(
    (t) =>
      t.url.startsWith(base) ||
      t.type === "page",
  ).webSocketDebuggerUrl,
);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let n = 0;
const pending = new Map(),
  errors = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Runtime.exceptionThrown")
    errors.push(m.params.exceptionDetails);
  if (m.method === "Page.javascriptDialogOpening")
    void call("Page.handleJavaScriptDialog", { accept: true });
  if (m.id) {
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    clearTimeout(p.timeout);
    m.error ? p.reject(m.error) : p.resolve(m.result);
  }
});
ws.addEventListener("close", (event) => {
  for (const request of pending.values()) {
    clearTimeout(request.timeout);
    request.reject(
      new Error(
        `Chrome connection closed during ${request.method} (${event.code}: ${event.reason})`,
      ),
    );
  }
  pending.clear();
});
const call = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++n;
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(
        new Error(
          `Chrome timed out during ${method}: ${params.expression ?? ""}`,
        ),
      );
    }, 30000);
    pending.set(id, { resolve, reject, timeout, method });
    ws.send(JSON.stringify({ id, method, params }));
  });
async function ev(expression) {
  const r = await call("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
async function until(s) {
  for (let i = 0; i < 100; i++) {
    if (await ev(s)) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  const diagnostic = await ev(`JSON.stringify({
    url: location.href,
    active: document.activeElement?.outerHTML.slice(0,1200),
    modal: document.querySelector('dialog[open]')?.outerHTML,
    pending: [...document.querySelectorAll('[data-bml-action-pending]')].map(e => e.outerHTML),
  })`);
  console.error("Screen timeout state:", diagnostic);
  console.error("Browser exceptions:", errors);
  throw Error("Timeout " + s);
}
async function nav(path) {
  await call("Page.navigate", { url: base + path });
  await until(
    `location.href === ${JSON.stringify(base + path)} && document.readyState==='complete' && document.querySelector('main') && document.querySelector('[data-bml-ref=signout]') && !document.querySelector('[data-bml-ref=signout]').disabled`,
  );
  await ev("document.fonts.ready");
  await new Promise((r) => setTimeout(r, 180));
}
await call("Runtime.enable");
await call("Page.enable");
await call("Page.bringToFront");
await call("Network.setCookie", {
  name: "_git_test",
  value: "ui-test-token",
  url: base,
});
await call("Network.setCookie", {
  name: "_git_test_meta",
  value: encodeURIComponent(
    encodeURIComponent(JSON.stringify({ expiresAt: 4102444800, issuedAt: 1 })),
  ),
  url: base,
});


async function key(value,modifiers=0) {
 const code={Tab:9,Enter:13,Escape:27,ArrowDown:40,ArrowUp:38,ArrowRight:39,ArrowLeft:37,Home:36,End:35,Backspace:8,a:65,f:70}[value]??value.toUpperCase().charCodeAt(0);
 for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,code:value,windowsVirtualKeyCode:code,modifiers,...(value==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
}
async function viewport(width){await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});}
const prefix=`bml-${Buffer.from('io.bosca:bml-design').toString('hex')}-`;
for(const width of [1920,1440,1024,390]) {
 await viewport(width);
 await nav('/repositories/repo?file=search-result.md&line=2');
 await until(`document.querySelector('[data-bml-source-view]')?.hasAttribute('data-source-ready')`);
 assert.ok(await ev(`document.querySelector('[data-bml-source-view]').getAttribute('data-bml-component')==='${prefix}source-view'`));
 assert.equal(await ev(`document.querySelector('[data-bml-source-view]').dataset.line`),'2');
 assert.equal(await ev(`document.querySelector('[data-tab=Source]').getAttribute('aria-selected')`),'true');
 assert.equal(await ev(`document.querySelector('.cm-activeLine').textContent`),'matching line');
 assert.equal(await ev(`document.querySelector('.cm-content').getAttribute('aria-label')`),'File source: search-result.md');
 assert.equal(await ev(`document.querySelector('.cm-content').getAttribute('aria-readonly')`),'true');
 assert.ok(await ev(`!document.querySelector('.cm-content').isContentEditable`));
 const handle=await call('Runtime.evaluate',{expression:'window'});
 const listeners=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
 await call('Runtime.releaseObject',{objectId:handle.result.objectId});
 assert.equal(listeners.listeners.filter(e=>e.type==='beforeunload').length,0,'Read-only sources mount no Git dirty-navigation guard');
 const original=await ev(`document.querySelector('[data-bml-source-view] textarea').value`);
 await ev(`document.querySelector('.cm-content').focus()`);await call('Input.insertText',{text:'Unwanted edit'});await key('Backspace');
 assert.equal(await ev(`document.querySelector('[data-bml-source-view] textarea').value`),original);
 assert.equal(await ev(`document.querySelector('.cm-activeLine').textContent`),'matching line');
 await key('f',4);
 await until(`Boolean(document.querySelector('.cm-search input[name=search]'))`);
 await call('Input.insertText',{text:'matching'});await key('Enter');
 await until(`Boolean(document.querySelector('.cm-searchMatch'))`);
 await key('Escape');
 await key('Escape');await key('Tab');
 assert.ok(await ev(`!document.querySelector('[data-bml-source-view]').contains(document.activeElement)`),'Escape then Tab leaves a bare viewer');
 await ev(`document.querySelector('[data-bml-source-view] .cm-content').focus()`);
 const palette=await ev(`(() => {const root=document.querySelector('[data-bml-source-view]'),editor=root.querySelector('.cm-editor'),gutter=root.querySelector('.cm-gutters'),line=root.querySelector('.cm-activeLine'),heading=[...root.querySelectorAll('.source-heading')].find(e=>e.textContent.includes('Title'));
 const elements=[root,editor,gutter,line],dims=()=>elements.map(e=>{const r=e.getBoundingClientRect();return [r.x,r.y,r.width,r.height]}),before=dims(),style=document.createElement('style');
 style.textContent='.git-document .code-widget[data-bml-source-view] { --source-bg:#edf4f6; --source-fg:#163541; --source-gutter:#52717d; --source-active-line:#d6e6ec; --source-heading:#2a5d73; --control-border:#59707c; }';document.head.append(style);
 try{return {before,after:dims(),background:getComputedStyle(editor).backgroundColor,foreground:getComputedStyle(editor).color,gutter:getComputedStyle(gutter).color,line:getComputedStyle(line).backgroundColor,activeGutter:getComputedStyle(root.querySelector('.cm-activeLineGutter')).backgroundColor,heading:getComputedStyle(heading).color,outline:getComputedStyle(editor).outlineColor,outlineWidth:getComputedStyle(editor).outlineWidth};}finally{style.remove();}})()`);
 assert.deepEqual(palette.before,palette.after,'Consumer CSS recolors the viewer without moving its content');
 assert.equal(palette.background,'rgb(237, 244, 246)');assert.equal(palette.foreground,'rgb(22, 53, 65)');assert.equal(palette.gutter,'rgb(82, 113, 125)');assert.equal(palette.heading,'rgb(42, 93, 115)');
 assert.equal(palette.line,'rgba(0, 0, 0, 0)','Source focus keeps the active row unfilled');
 assert.equal(palette.activeGutter,'rgba(0, 0, 0, 0)','The active gutter stays unfilled');
 assert.equal(palette.outline,'rgb(89, 112, 124)');assert.equal(palette.outlineWidth,'1px','Keyboard focus uses the consumer control border');
 await nav('/repositories/repo?file=long.txt&line=180');
 await until(`document.querySelector('[data-bml-source-view][data-source-ready]')&&document.querySelector('.cm-activeLine')?.textContent.startsWith('Line 180 ')`);
 assert.ok(await ev(`(() => {const editor=document.querySelector('.cm-editor').getBoundingClientRect(),line=document.querySelector('.cm-activeLine').getBoundingClientRect(),scroll=document.querySelector('.cm-scroller');return editor.height>=224&&editor.height<=801&&line.top>=scroll.getBoundingClientRect().top-1&&line.bottom<=scroll.getBoundingClientRect().bottom+1&&scroll.scrollHeight>scroll.clientHeight&&document.documentElement.scrollWidth<=innerWidth;})()`),'Requested source lines remain inside the bounded scrolling viewer');
 await nav('/pulls/pull-context?repo=repo&number=12&view=files');
 await until(`Boolean(document.querySelector('[data-bml-component="${prefix}tree"] .ui-tree-row[tabindex="0"]'))`);
 assert.ok(await ev(`Boolean(document.querySelector('[data-bml-c="${prefix}tree-item"]'))`));
 await ev(`document.querySelector('.diff-sidebar [role=treeitem] button').focus()`);
 await key('ArrowLeft');
 assert.equal(await ev(`document.activeElement.closest('[role=treeitem]').getAttribute('aria-expanded')`),'false');
 await key('ArrowRight');
 assert.equal(await ev(`document.activeElement.closest('[role=treeitem]').getAttribute('aria-expanded')`),'true','Right opens the directory while keeping focus');
 await key('ArrowRight');
 assert.ok(await ev(`document.activeElement.closest('[role=treeitem]').dataset.path.includes('/')`),'A second Right enters the open directory');
 await key('End');
 const destination=await ev(`document.activeElement.getAttribute('href')`);
 assert.ok(destination?.startsWith('#'));
 await key('Enter');
 await until(`location.hash===${JSON.stringify(destination)}`);
 assert.ok(await ev(`document.documentElement.scrollWidth<=innerWidth`),'Diff tree navigation keeps the page within the viewport');
}
await call('Emulation.setScriptExecutionDisabled',{value:true});
try {
 await call('Page.navigate',{url:base+'/repositories/repo?file=a.txt'});
 await until(`document.readyState==='complete'&&Boolean(document.querySelector('[data-bml-source-view] pre'))`);
 assert.equal(await ev(`document.querySelector('[data-bml-source-view] pre').textContent`),'<script>source</script>');
 assert.equal(await ev(`document.querySelector('[data-bml-source-view] pre').tabIndex`),0);
 assert.ok(await ev(`!document.querySelector('[data-bml-source-view] .cm-editor')`));
 await call('Page.navigate',{url:base+'/pulls/pull-context?repo=repo&number=12&view=files'});
 await until(`document.readyState==='complete'&&Boolean(document.querySelector('[role=treeitem] a'))`);
 assert.ok(await ev(`[...document.querySelectorAll('[role=treeitem] a')].every(a=>a.checkVisibility()&&a.getAttribute('href').startsWith('#'))`),'Native diff destinations remain visible without tree enhancement');
}finally{await call('Emulation.setScriptExecutionDisabled',{value:false});}
assert.deepEqual(errors,[]);
console.log('Source/tree checks passed: library ownership, explicit line links, read-only keyboard/search, bare Tab exit, consumer CSS, bounded scrolling, native diff navigation and no-script fallbacks at four widths');
ws.close();
