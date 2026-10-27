import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
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
  errors = [],
  dialogs = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Runtime.exceptionThrown")
    errors.push(m.params.exceptionDetails);
  if (m.method === "Page.javascriptDialogOpening") {
    dialogs.push(m.params.message);
    void call("Page.handleJavaScriptDialog", { accept: true });
  }
  if (m.id) {
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    clearTimeout(p.timeout);
    m.error ? p.reject(new Error(`${p.method}: ${m.error.message}`)) : p.resolve(m.result);
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
    if (await ev(`Boolean(${s})`)) return;
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

async function click(selector) {
  const p=await ev(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
  for(const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,...p,button:'left',clickCount:1});
}
async function key(key) {
  for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key,windowsVirtualKeyCode:{Escape:27,Enter:13,Tab:9}[key],
    ...(key==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
}
async function geometry() {
  return ev(`(() => {
    const root=document.querySelector('.file-workspace')??document.querySelector('.file-surface');
    const main=document.querySelector('main'),rect=root.getBoundingClientRect(),page=main.getBoundingClientRect();
    return {width:innerWidth,height:innerHeight,surface:rect.toJSON(),page:page.toJSON(),bottomPadding:parseFloat(getComputedStyle(main).paddingBottom),
      pageScroll:main.scrollHeight-main.clientHeight,overflow:document.documentElement.scrollWidth>innerWidth,
      native:document.fullscreenElement===root,
      editor:root.querySelector('.cm-editor')?.getBoundingClientRect().toJSON()};
  })()`);
}
async function expanded(value) {
  await until(`document.querySelector('.file-workspace').hasAttribute('data-bml-workspace-expanded')===${value}&&document.querySelector('[data-bml-fullscreen-button]').getAttribute('aria-busy')!=='true'`);
  await ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  assert.ok(await ev(`(() => {
    const button=document.querySelector('[data-bml-fullscreen-button]'),rect=button.getBoundingClientRect();
    return button.getAttribute('aria-label')===${JSON.stringify(value?'Exit full screen':'Full screen')}&&button.dataset.tooltipContent===button.getAttribute('aria-label')&&
      rect.width===30&&rect.height===30&&button.querySelector('[data-fullscreen-enter-icon]').checkVisibility()===${!value}&&
      button.querySelector('[data-fullscreen-exit-icon]').checkVisibility()===${value};
  })()`),'Full screen uses the shared enter/exit icons, accessible labels and a fixed square footprint');
}
const reports=[];
for(const width of [1920,1440,1024,390]) {
  await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
  await nav('/repositories/repo?file=README.md&line=1');
  await until(`document.querySelector('.file-surface .cm-editor')`);
  assert.ok(await ev(`document.querySelector('.file-workspace').dataset.bmlComponent==='bml-696f2e626f7363613a626d6c2d64657369676e-workspace'`),
    'The Design Library owns the fill and full-screen workspace');
  const normal=await geometry();
  reports.push({width,normal});
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-workspace.json`,JSON.stringify(reports,null,2));
  assert.ok(Math.abs(normal.surface.bottom-(normal.page.bottom-normal.bottomPadding))<=1,`File workspace fills the available page: ${JSON.stringify(normal)}`);
  assert.ok(normal.pageScroll<=1&&!normal.overflow,'File pages have bounded content and no outer overflow');
  assert.ok(await ev(`(() => {
    const header=document.querySelector('.file-header').getBoundingClientRect();
    return [...document.querySelectorAll('.file-header a,.file-header button')].filter(e=>e.getBoundingClientRect().width>0).every(e=>{
      const r=e.getBoundingClientRect();return r.left>=header.left&&r.right<=header.right&&r.top>=header.top&&r.bottom<=header.bottom;
    });
  })()`),'Every file action fits inside the header');
  assert.ok(await ev(`!document.querySelector('.repository-summary')&&!document.querySelector('.path-bar')`),'File views reserve the page for the file');
  assert.ok(await ev(`document.querySelector('[data-tab=Source]').getAttribute('aria-selected')==='true'`),'Requested source lines retain the Source view');
  await ev(`void(window.originalFileEditor=document.querySelector('.file-surface .cm-editor'))`);
  await click('[data-bml-fullscreen-button]');
  await expanded(true);
  const full=await geometry();
  assert.ok(Math.abs(full.surface.top)<=1&&Math.abs(full.surface.left)<=1&&Math.abs(full.surface.width-full.width)<=1&&Math.abs(full.surface.height-full.height)<=1,
    `Full screen fills the viewport: ${JSON.stringify(full)}`);
  assert.ok(await ev(`document.querySelector('.app-header').inert&&document.querySelector('.file-surface .cm-editor')===window.originalFileEditor`),
    'Full screen isolates the workspace and preserves the editor instance');
  await click('[data-tab=Preview]');
  const preview=await ev(`document.querySelector('.markdown-file [aria-label=Preview][role=tabpanel]').getBoundingClientRect().toJSON()`);
  assert.ok(Math.abs(preview.bottom-full.surface.bottom)<=2,'Preview fills the full-screen content area');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-preview-full-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await click('[data-tab=Source]');
  await key('Escape');
  await expanded(false);
  assert.ok(await ev(`!document.querySelector('.app-header').inert&&document.querySelector('.file-surface .cm-editor')===window.originalFileEditor`));
  assert.ok(await ev(`document.activeElement===document.querySelector('[data-bml-fullscreen-button]')`),'Exiting restores focus to the full-screen control');
  const restored=await geometry();
  assert.deepEqual(restored.surface,normal.surface,'Exiting full screen restores the normal workspace geometry');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-source-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await ev(`document.querySelector('.file-workspace').requestFullscreen=async()=>{throw new DOMException('Unavailable','NotAllowedError')}`);
  await click('[data-bml-fullscreen-button]');
  await expanded(true);
  assert.ok(await ev(`!document.fullscreenElement&&document.querySelector('.file-workspace').hasAttribute('data-bml-workspace-expanded')`),
    'Viewport expansion remains available when native full screen is unavailable');
  await click('[data-bml-fullscreen-button]');
  await expanded(false);
  reports.at(-1).full=full;
  reports.at(-1).preview=preview;
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-workspace.json`,JSON.stringify(reports,null,2));
}
assert.ok(reports.some(report=>report.full.native),'A trusted click exercises the browser full-screen API');
const fileActions=[];
for(const width of [1920,1440,1024,390]) {
  await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
  for(const appearance of ['dark','light']) {
    await call('Network.setCookie',{name:'git_ui_appearance',value:appearance,url:base,path:'/'});
    await nav('/repositories/repo?file=README.md&line=1');
    const actions=await ev(`(() => {
      const toolbar=document.querySelector('.file-toolbar'),header=document.querySelector('.file-header').getBoundingClientRect();
      const labelText=control=>{const clone=control.cloneNode(true);clone.querySelectorAll('[role=tooltip]').forEach(node=>node.remove());return clone.textContent.trim();};
      return [...toolbar.querySelectorAll('a,button')].filter(e=>e.checkVisibility()).map(control=>({
        label:control.getAttribute('aria-label'),text:labelText(control),tag:control.tagName,
        href:control.getAttribute('href'),tooltip:control.dataset.tooltipContent,icon:control.querySelector('svg')?.innerHTML,
        rect:control.getBoundingClientRect().toJSON(),header:header.toJSON(),
      }));
    })()`);
    assert.deepEqual(actions.map(action=>action.label),['Rename file','Delete file','Edit file','Download file']);
    assert.equal(new Set(actions.map(action=>action.icon)).size,4,'File actions have distinct glyphs');
    for(const action of actions) {
      assert.ok(action.icon&&!action.text&&action.tooltip===action.label,
        `Icon-only actions retain accessible names and tooltips: ${JSON.stringify(action)}`);
      assert.ok(Math.abs(action.rect.width-30)<=1&&Math.abs(action.rect.height-30)<=1,
        `All file actions share the compact square footprint: ${JSON.stringify(action)}`);
      assert.ok(action.rect.left>=action.header.left&&action.rect.right<=action.header.right&&action.rect.bottom<=action.header.bottom,
        'Icon actions fit the file header');
    }
    for(let index=1;index<actions.length;index++) {
      assert.ok(Math.abs(actions[index].rect.left-actions[index-1].rect.right-10)<=1&&Math.abs(actions[index].rect.top-actions[0].rect.top)<=1,
        'File actions keep an even horizontal gap and vertical alignment');
    }
    assert.ok(actions[0].tag==='A'&&actions[0].href.includes('edit=true')&&actions[2].tag==='A'&&actions[2].href.includes('edit=true'),
      'Rename and Edit retain native editing links');
    await click('.file-toolbar [aria-label="Delete file"]');
    await until(`document.querySelector('dialog[open]')?.getAttribute('aria-label')==='Delete file?'`);
    assert.ok(await ev(`document.querySelector('dialog[open]').textContent.includes('A commit removing README.md will be added to main.')`),
      'The trash icon still opens the file-specific confirmation');
    await key('Escape');
    await until(`!document.querySelector('dialog[open]')`);
    assert.ok(await ev(`document.activeElement===document.querySelector('.file-toolbar [aria-label="Delete file"]')`),
      'Dismissing deletion restores focus to its icon');
    await ev(`document.activeElement.blur()`);
    for(const label of ['Rename file','Delete file','Edit file']) {
      const action=actions.find(action=>action.label===label);
      await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:action.rect.x+action.rect.width/2,y:action.rect.y+action.rect.height/2});
      await until(`[...document.querySelectorAll('[role=tooltip]')].some(t=>t.matches(':popover-open')&&t.textContent===${JSON.stringify(label)})`);
    }
    await call('Input.dispatchMouseEvent',{type:'mouseMoved',x:1,y:1});
    await until(`![...document.querySelectorAll('[role=tooltip]')].some(t=>t.matches(':popover-open'))`);
    fileActions.push({width,appearance,actions});
    await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-actions-${appearance}-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  }
}
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-actions.json`,JSON.stringify(fileActions,null,2));
for(const label of ['Rename file','Edit file']) {
  await nav('/repositories/repo?file=README.md');
  await ev(`document.querySelector(${JSON.stringify('.file-toolbar [aria-label="'+label+'"]')}).focus()`);
  await key('Enter');
  await until(`location.search.includes('edit=true')&&document.querySelector('form.code-widget .cm-content')`);
  assert.equal(await ev(`document.querySelector('form.code-widget [name=path]').value`),'README.md',
    'Keyboard activation retains the file path and opens the existing editor');
}
await call('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
await nav('/repositories/repo?file=long.txt&edit=true');
await until(`document.querySelector('form.code-widget .cm-content')`);
const edit=await geometry();
assert.ok(Math.abs(edit.surface.bottom-(edit.page.bottom-edit.bottomPadding))<=1,'Editable files also fill the page');
await ev(`window.originalDraftEditor=document.querySelector('form.code-widget .cm-editor');document.querySelector('form.code-widget .cm-content').focus()`);
await call('Input.insertText',{text:'# Workspace draft\n'});
const draft=await ev(`document.querySelector('form.code-widget textarea[name=content]').value`);
await ev(`document.querySelector('form.code-widget .cm-scroller').scrollTop=500`);
await ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
const scroll=await ev(`document.querySelector('form.code-widget .cm-scroller').scrollTop`);
assert.ok(scroll>0,'Long drafts have an internal scroll area');
await click('[data-bml-fullscreen-button]');
await expanded(true);
assert.equal(await ev(`document.querySelector('form.code-widget .cm-scroller').scrollTop`),scroll,'Full screen retains the editor scroll position');
await click('[data-bml-ref=preview]');
await until(`!document.querySelector('[data-bml-ref=previewSurface]').hidden&&document.querySelector('[data-bml-ref=previewStatus]').hidden`);
assert.ok(await ev(`document.querySelector('[data-bml-ref=previewSurface]').textContent.includes('Workspace draft')`));
await click('[data-bml-ref=preview]');
await click('[data-bml-fullscreen-button]');
await expanded(false);
assert.equal(await ev(`document.querySelector('form.code-widget textarea[name=content]').value`),draft,'Draft survives preview and full-screen transitions');
assert.ok(await ev(`document.querySelector('form.code-widget .cm-editor')===window.originalDraftEditor`));
assert.equal(await ev(`document.querySelector('form.code-widget .cm-scroller').scrollTop`),scroll,'Returning from preview and full screen retains the editor scroll position');
await click('[data-bml-fullscreen-button]');
await expanded(true);
await click('[data-bml-ref=wrap]');
await ev(`document.querySelector('form.code-widget [name=path]').value='protected.txt';document.querySelector('form.code-widget [name=message]').value='Keep the draft';document.querySelector('form.code-widget button[type=submit]').click()`);
await until(`document.querySelector('form.code-widget [role=alert]')?.textContent.includes('Branch is protected')`);
await expanded(true);
assert.ok(await ev(`document.querySelector('form.code-widget .cm-editor')===window.originalDraftEditor&&!document.querySelector('[data-bml-fullscreen-button]').hidden`),
  'A failed commit retains the expanded editor and its usable exit control');
assert.equal(await ev(`document.querySelector('form.code-widget textarea[name=content]').value`),draft);
assert.ok(await ev(`document.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')==='true'&&document.querySelector('form.code-widget .cm-content').classList.contains('cm-lineWrapping')`),
  'A failed save retains the wrap icon state and the current line wrapping');
await click('[data-bml-fullscreen-button]');
await expanded(false);
assert.ok(await ev(`!document.querySelector('.app-header').inert`),'A failed commit does not leave the surrounding page inert');
const editors=[];
for (const width of [1440,1024,390]) {
  await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
  await ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  const size=await geometry();
  assert.ok(Math.abs(size.surface.bottom-(size.page.bottom-size.bottomPadding))<=1&&size.editor.height>120&&!size.overflow,
    `Draft editor remains bounded and usable: ${JSON.stringify(size)}`);
  assert.ok(await ev(`(() => {
    const r=document.querySelector('.file-workspace').getBoundingClientRect();
    return [...document.querySelectorAll('.file-workspace form > .toolbar button,.file-workspace form > .toolbar input')].every(e=>{
      const b=e.getBoundingClientRect();return b.left>=r.left&&b.right<=r.right&&b.top>=r.top&&b.bottom<=r.bottom;
    });
  })()`),'Editor and commit controls fit the workspace');
  editors.push(size);
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-editor-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
}
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-editor.json`,JSON.stringify(editors,null,2));
const layouts=[];
for(const width of [1920,1440,1024,768,390]) {
  await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
  for(const appearance of ['dark','light']) {
    await call('Network.setCookie',{name:'git_ui_appearance',value:appearance,url:base,path:'/'});
    for(const creating of [false,true]) {
      await nav(creating?'/repositories/repo?path=docs&new=true':'/repositories/repo?file=README.md&edit=true');
      await until(`document.querySelector('form.code-widget .cm-content')`);
      const layout=await ev(`(() => {
        const form=document.querySelector('form.code-widget'),rect=node=>node.getBoundingClientRect().toJSON(),
          path=form.querySelector('[name=path]'),message=form.querySelector('[name=message]'),
          submit=form.querySelector('button[type=submit]'),author=form.querySelector('.commit-author'),cancel=form.querySelector('[data-editor-cancel]');
        const footer=form.querySelector('.commit-controls'),style=getComputedStyle(footer);
        return {width:innerWidth,appearance:document.body.dataset.theme,path:rect(path),message:rect(message),submit:rect(submit),
          author:rect(author),footer:rect(footer),paddingRight:parseFloat(style.paddingRight),surface:rect(document.querySelector('.file-workspace')),
          editor:rect(form.querySelector('.code-editor')),overflow:document.documentElement.scrollWidth>innerWidth,
          cancel:{tag:cancel.tagName,label:cancel.getAttribute('aria-label'),icon:!!cancel.querySelector('svg'),button:cancel.classList.contains('button-link'),rect:rect(cancel)},
          wrap:{label:form.querySelector('[data-bml-ref=wrap]').getAttribute('aria-label'),icon:!!form.querySelector('[data-bml-ref=wrap] svg')},
          primary:submit.classList.contains('primary'),actions:[...form.querySelectorAll('.editor-actions button,.editor-actions a')].map(rect),
          bounds:[...form.querySelectorAll('.toolbar input,.toolbar button,.toolbar a')].map(rect)};
      })()`);
      assert.equal(layout.appearance,appearance);assert.ok(layout.primary);
      assert.ok(layout.path.width>=(width>900?400:250),`The file path has useful width: ${JSON.stringify(layout)}`);
      assert.ok(layout.message.width>=(width>900?280:250),`The commit message has useful width: ${JSON.stringify(layout)}`);
      assert.ok(Math.abs(layout.submit.right-(layout.footer.right-layout.paddingRight))<=1,'Commit stays at the footer right edge');
      assert.ok(Math.abs((layout.author.top+layout.author.height/2)-(layout.submit.top+layout.submit.height/2))<=1,
        'Author identity is vertically centered with the commit button');
      assert.ok(layout.cancel.button&&layout.cancel.icon&&layout.cancel.tag==='A'&&layout.cancel.label==='Cancel editing',
        'Cancel is a labelled shared icon link with a button appearance');
      assert.ok(layout.wrap.icon&&layout.wrap.label==='Wrap lines','Line wrapping uses a labelled shared icon button');
      assert.ok(Math.max(...layout.actions.map(box=>box.height))-Math.min(...layout.actions.map(box=>box.height))<=1,
        'Toolbar actions have consistent control heights');
      assert.ok(!layout.overflow&&layout.editor.height>120,'The editor retains usable height without page overflow');
      for(const box of layout.bounds) assert.ok(box.left>=layout.surface.left&&box.right<=layout.surface.right&&box.top>=layout.surface.top&&box.bottom<=layout.surface.bottom,
        `Editor controls fit the workspace: ${JSON.stringify(layout)}`);
      layouts.push({creating,...layout});
      await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/editor-layout-${creating?'new':'edit'}-${appearance}-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
    }
  }
}
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/editor-layout.json`,JSON.stringify(layouts,null,2));
await ev(`document.querySelector('form.code-widget .cm-content').focus()`);
await key('Escape');await key('Tab');
assert.ok(await ev(`document.activeElement===document.querySelector('form.code-widget [name=message]')`),
  'Escape then Tab leaves the editor and reaches the commit message');
await ev(`document.querySelector('[data-bml-ref=preview]').focus()`);
await key('Tab');
assert.ok(await ev(`document.activeElement===document.querySelector('[data-bml-ref=wrap]')`),'Toolbar Tab order reaches the wrap icon');
await key('Enter');
await until(`document.querySelector('[data-bml-ref=wrap]').getAttribute('aria-pressed')==='true'&&document.querySelector('.cm-content').classList.contains('cm-lineWrapping')`);
await ev(`document.querySelector('form.code-widget .cm-content').focus()`);
await call('Input.insertText',{text:'Unsaved cancel draft'});
const beforeCancel=dialogs.length;
await click('[data-editor-cancel]');
await until(`location.search.includes('path=docs')&&!location.search.includes('new=true')&&!document.querySelector('form.code-widget')`);
assert.ok(dialogs.slice(beforeCancel).some(message=>message.includes('Discard your uncommitted changes?')),
  'The cancel icon retains the existing dirty-draft confirmation before returning to the folder');
await call('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
await call('Network.setCookie',{name:'git_ui_appearance',value:'light',url:base,path:'/'});
await nav('/repositories/repo?file=README.md&view=source');
await until(`document.querySelector('.file-surface .cm-editor')`);
await click('[data-bml-fullscreen-button]');
await expanded(true);
assert.equal(await ev(`getComputedStyle(document.querySelector('.file-workspace')).backgroundColor`),'rgb(255, 255, 255)',
  'Full screen uses the consuming application light palette');
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/file-source-full-light.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
await click('[data-bml-fullscreen-button]');
await expanded(false);
await call('Network.setCookie',{name:'git_ui_appearance',value:'dark',url:base,path:'/'});
assert.deepEqual(errors,[]);
console.log('File workspace passed: page fill and full-screen geometry at four widths; icon actions and tooltips in both palettes, delete confirmation/focus and keyboard editing links; source/preview, native and viewport full screen, retained drafts, failed-save recovery and responsive editor controls');
ws.close();
