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
await call("Network.enable");
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


async function viewport(width) {
  await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
}
async function key(key, modifiers=0) {
  const code={Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,Home:36,End:35,F10:121}[key];
  for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent',{type,key,windowsVirtualKeyCode:code,modifiers,
    ...(key==='Enter'&&type==='keyDown'?{text:'\r'}:{})});
}
async function click(selector, button='left') {
  const p=await ev(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
  for (const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,...p,button,clickCount:1});
}
const reports=[];
await viewport(1440);
await nav('/settings/github');
const mappings='[data-bml-island="component:git-github-users:git-github-users"]';
const notice=`${mappings} [data-mapping-notice]`;
const initialSequence=await ev(`Number(document.querySelector('${notice}').dataset.sequence)`);
const tableTop=await ev(`document.querySelector('${mappings} .mapping-table').getBoundingClientRect().top`);
await ev(`document.querySelector('${mappings} input[name=username]').value='alice';
  document.querySelector('${mappings} select[name=principal]').value='principal';
  document.querySelector('${mappings} select[name=principal]').dispatchEvent(new Event('change',{bubbles:true}));
  document.querySelector('${mappings} .mapping-form button[type=submit]').click()`);
await until(`document.querySelector('${mappings} .mapping-table')?.innerText.includes('@alice')`);
await until(`document.querySelector('${notice}').matches(':popover-open')&&document.querySelector('${notice} .toast-message').textContent==='User mapping saved.'`);
const savedSequence=await ev(`Number(document.querySelector('${notice}').dataset.sequence)`);
assert.ok(savedSequence>initialSequence);
assert.ok(await ev(`!document.querySelector('${mappings} .status-message')`),'Save feedback has no inline status row');
assert.ok(Math.abs(await ev(`document.querySelector('${mappings} .mapping-table').getBoundingClientRect().top`)-tableTop)<=1,
  'Saving does not insert a feedback row above the mapping table');
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/mapping-saved-toast.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
await click(`${notice} [data-bml-ref=dismiss]`);
await until(`!document.querySelector('${notice}').matches(':popover-open')`);
await ev(`window.mappingRefresh=[...document.querySelectorAll('${mappings} button')].find(button=>button.textContent.trim()==='Refresh mappings');window.mappingRefresh.click()`);
await until(`!window.mappingRefresh.isConnected`);
assert.equal(await ev(`Number(document.querySelector('${notice}').dataset.sequence)`),savedSequence);
assert.ok(await ev(`!document.querySelector('${notice}').matches(':popover-open')`),'Refreshing does not replay the saved notice');
await ev(`document.querySelector('${mappings} input[name=username]').value='alice';
  document.querySelector('${mappings} select[name=principal]').value='principal';
  document.querySelector('${mappings} select[name=principal]').dispatchEvent(new Event('change',{bubbles:true}));
  document.querySelector('${mappings} .mapping-form button[type=submit]').click()`);
await until(`Number(document.querySelector('${notice}').dataset.sequence)>${savedSequence}&&document.querySelector('${notice}').matches(':popover-open')`);
assert.equal(await ev(`document.querySelector('${notice} .toast-message').textContent`),'User mapping saved.',
  'A repeated save can announce the same message as a new notice');
await nav('/repositories');
await ev('history.back()');
await until(`location.pathname==='/settings/github'&&document.readyState==='complete'&&document.querySelector('${notice}')&&document.querySelector('${mappings} .mapping-form')?.dataset.bmlActionBound==='true'&&document.querySelector('${mappings} .mapping-table')?.innerText.includes('@alice')`);
await ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
assert.ok(await ev(`!document.querySelector('${notice}').matches(':popover-open')`),'Returning through browser history does not resurrect the old toast');
const dialog=`${mappings} dialog[aria-label="Remove user mapping?"]`;
const trigger=`${mappings} .mapping-table [data-bml-design-dialog] > [data-bml-ref=trigger]`;
const description=await ev(`document.querySelector('${dialog} .confirmation-copy').textContent`);
for(const [width,height] of [[1440,900],[390,844],[390,360]]) {
  await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width===390});
  for(const appearance of ['dark','light']) {
    await ev(`document.body.dataset.theme=${JSON.stringify(appearance)}`);
    await ev(`document.querySelector('${trigger}').click()`);
    await until(`document.querySelector('${dialog}').matches(':modal')`);
    let normalHeight=0;
    for(const long of [false,true]) {
      if(long) await ev(`document.querySelector('${dialog} .confirmation-copy').textContent=${JSON.stringify(('Future synchronized changes will no longer use this identity. Previous deliveries retain their attribution. ').repeat(80)+'x'.repeat(280))}`);
      const size=await ev(`(() => {
        const panel=document.querySelector('${dialog}'),copy=panel.querySelector('.confirmation-copy'),footer=panel.querySelector('.modal-footer'),
          rect=node=>node.getBoundingClientRect().toJSON(),style=getComputedStyle(copy),range=document.createRange();range.selectNodeContents(copy);
        return {panel:rect(panel),copy:rect(copy),footer:rect(footer),text:range.getBoundingClientRect().toJSON(),
          padding:[style.paddingTop,style.paddingRight,style.paddingBottom,style.paddingLeft],lineHeight:parseFloat(style.lineHeight),
          parentWrapping:getComputedStyle(panel.closest('.gt-cell')).whiteSpace,wrapping:getComputedStyle(panel).whiteSpace,alignment:getComputedStyle(panel).textAlign,
          horizontal:panel.scrollWidth-panel.clientWidth,copyHorizontal:copy.scrollWidth-copy.clientWidth,vertical:copy.scrollHeight-copy.clientHeight,
          actions:[...footer.querySelectorAll('button')].map(rect)};
      })()`);
      assert.equal(size.parentWrapping,'nowrap');assert.equal(size.wrapping,'normal');assert.equal(size.alignment,'left');
      assert.deepEqual(size.padding,['18px','18px','18px','18px']);
      assert.ok(size.panel.left>=16&&size.panel.right<=width-16&&size.panel.top>=0&&size.panel.bottom<=height,JSON.stringify(size));
      assert.ok(size.horizontal<=1&&size.copyHorizontal<=1,'The real mapping dialog has no horizontal overflow');
      assert.ok(size.footer.bottom<=size.panel.bottom&&size.footer.top>=size.copy.bottom-1,'Mapping actions remain below the description and inside the viewport');
      for(const action of size.actions) assert.ok(action.left>=size.panel.left&&action.right<=size.panel.right);
      if(long) {
        assert.ok(size.panel.height>normalHeight&&size.vertical>0,'Long descriptions grow the dialog and scroll vertically');
        assert.ok(await ev(`(() => {const copy=document.querySelector('${dialog} .confirmation-copy');copy.scrollTop=copy.scrollHeight;return copy.scrollTop>0})()`));
      } else {
        normalHeight=size.panel.height;assert.ok(size.text.height>size.lineHeight,'The mapping explanation wraps over multiple lines');
      }
      reports.push({width,height,appearance,long,...size});
      await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/mapping-dialog-${appearance}-${width}-${height}-${long?'long':'normal'}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
    }
    await ev(`document.querySelector('${dialog} .confirmation-copy').textContent=${JSON.stringify(description)}`);
    await key('Escape');await until(`!document.querySelector('${dialog}').open`);
    assert.ok(await ev(`document.activeElement===document.querySelector('${trigger}')`),'Escape returns focus to the mapping action');
  }
}
await ev(`document.querySelector('${trigger}').click()`);
await until(`document.querySelector('${dialog}').matches(':modal')`);
await ev(`document.querySelector('${dialog} .button.danger').click()`);
await until(`document.querySelector('${mappings} .empty-state')&&document.querySelector('${notice}').matches(':popover-open')&&document.querySelector('${notice} .toast-message')?.textContent.includes('Mapping removed')`);
assert.ok(await ev(`!document.querySelector('${mappings} .status-message')`),'Removal feedback also uses a toast');
assert.deepEqual(errors,[]);
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/mapping-dialog.json`,JSON.stringify(reports,null,2));
console.log('Mapping checks passed: shared save/remove toasts, stable table spacing, repeated notices without refresh/history replay; wrapping dialogs, balanced padding, vertical scrolling, fixed actions and Escape/focus in both themes at desktop/narrow/short sizes.');
ws.close();
