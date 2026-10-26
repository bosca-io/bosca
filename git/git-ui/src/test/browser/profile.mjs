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
  heldRequests = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === 'Fetch.requestPaused') heldRequests.push(m.params);
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
const trigger='.profile-popover > summary';
const content='.profile-popover .popover-content';
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
async function checkAmbient(appearance) {
  const background=await ev(`(() => {
    const style=getComputedStyle(document.querySelector('[data-bml-app-shell]'));
    return {image:style.backgroundImage,color:style.backgroundColor,appearance:document.body.dataset.theme};
  })()`);
  assert.equal(background.appearance,appearance);
  assert.equal((background.image.match(/radial-gradient\(/g)??[]).length,3,'Both appearances retain the Studio ambient radial layers');
  assert.equal((background.image.match(/linear-gradient\(/g)??[]).length,1,'Both appearances retain the ambient linear layer');
  assert.equal(background.color,appearance==='light'?'rgb(248, 250, 252)':'rgb(10, 12, 18)');
  return background;
}
async function checkMenuIcons(appearance) {
  const icons=await ev(`(() => {
    const actions=[...document.querySelectorAll('.profile-actions .profile-action')];
    return {arrow:getComputedStyle(document.querySelector('${trigger}'),'::after').content,
      frame:getComputedStyle(document.querySelector('${content}')).borderColor,
      frameWidth:getComputedStyle(document.querySelector('${content}')).borderWidth,
      rows:actions.map(action=>{
        const icon=[...action.querySelectorAll('.menu-icon, .button-icon')].find(node=>node.checkVisibility());
        const svg=icon?.querySelector('svg'),label=action.querySelector('.button-content')?.lastElementChild;
        return {count:[...action.querySelectorAll('.menu-icon, .button-icon')].filter(node=>node.checkVisibility()).length,
          width:svg?.getBoundingClientRect().width,height:svg?.getBoundingClientRect().height,
          circle:!!svg?.querySelector('circle'),paths:svg?.querySelectorAll('path').length,
          hidden:svg?.getAttribute('aria-hidden'),left:icon?.getBoundingClientRect().left,
          gap:label?.getBoundingClientRect().left-icon?.getBoundingClientRect().right};
      })};
  })()`);
  assert.equal(icons.arrow,'none','The profile trigger has no down arrow');
  assert.equal(icons.frame,appearance==='light'?'rgb(215, 222, 234)':'rgb(35, 40, 57)',
    'The profile popover uses the quiet surface border');
  assert.equal(icons.frameWidth,'1px');
  for (const row of icons.rows) {
    assert.equal(row.count,1,'Each menu action has exactly one visible icon');
    assert.equal(row.width,16);assert.equal(row.height,16);assert.equal(row.hidden,'true');
    assert.equal(row.gap,8,'Menu icons have consistent spacing before their labels');
  }
  assert.equal(icons.rows[0].left,icons.rows[1].left,'Menu icon columns align');
  assert.equal(icons.rows[0].circle,appearance==='dark','Sun offers light mode; moon offers dark mode');
  assert.equal(icons.rows[0].paths,appearance==='dark'?8:1);
  assert.equal(icons.rows[1].paths,3,'Sign out uses the exit icon');
  return icons;
}
async function checkModal(appearance,width) {
  const modal='dialog[aria-label="New folder"]';
  await ev(`document.querySelector('${modal}').parentElement.querySelector('[data-bml-ref=trigger]').click()`);
  await until(`document.querySelector('${modal}').matches(':modal')`);
  const frame=await ev(`(() => {const panel=document.querySelector('${modal}'),style=getComputedStyle(panel);
    return {border:style.borderColor,borderWidth:style.borderWidth,outline:style.outlineWidth,
      background:style.backgroundColor,focusInside:panel.contains(document.activeElement)};})()`);
  assert.equal(frame.border,appearance==='light'?'rgb(215, 222, 234)':'rgb(35, 40, 57)');
  assert.equal(frame.borderWidth,'1px');assert.ok(frame.focusInside);
  await key('Tab');
  assert.ok(await ev(`document.activeElement.matches('button:focus-visible')&&parseFloat(getComputedStyle(document.activeElement).outlineWidth)>=2`),
    'Keyboard focus stays visible inside the softer modal frame');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/modal-${appearance}-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await key('Escape');
  await until(`!document.querySelector('${modal}').open`);
  return frame;
}
for (const width of [1920,1024,768,390]) {
  await viewport(width);
  await call('Network.setCookie',{name:'git_ui_appearance',value:'dark',url:base});
  await nav('/repositories/repo');
  const darkBackground=await checkAmbient('dark');
  const darkModal=await checkModal('dark',width);
  const before=await ev(`document.querySelector('.profile-menu').getBoundingClientRect().toJSON()`);
  await click(trigger,'right');
  await until(`document.querySelector('${content}').matches(':popover-open')&&document.activeElement===document.querySelector('[data-bml-ref=appearance]')`);
  assert.equal(await ev(`document.querySelector('${trigger}').getAttribute('aria-expanded')`),'true');
  const bounds=await ev(`document.querySelector('${content}').getBoundingClientRect().toJSON()`);
  assert.ok(bounds.left>=11&&bounds.right<=width-11&&bounds.top>=11&&bounds.bottom<=(width===390?844:900)-11,JSON.stringify(bounds));
  await until(`document.querySelector('[data-profile-heading] [data-profile-name]').textContent==='Alice'&&document.querySelector('[data-profile-heading] [data-profile-subtitle]').textContent==='@alice'&&!document.querySelector('[data-profile-error]').checkVisibility()`);
  const darkIcons=await checkMenuIcons('dark');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/profile-dark-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await key('End');
  assert.ok(await ev(`document.activeElement===document.querySelector('[data-bml-ref=signout]')`));
  await key('Home');
  await key('Enter');
  await until(`document.body.dataset.theme==='light'`);
  const lightBackground=await checkAmbient('light');
  const colors=await ev(`(() => ({body:getComputedStyle(document.body).backgroundColor,
    text:getComputedStyle(document.body).color,menu:getComputedStyle(document.querySelector('${content}')).backgroundColor,
    button:document.querySelector('[data-bml-ref=appearanceLabel]').textContent,
    bounds:document.querySelector('.profile-menu').getBoundingClientRect().toJSON()}))()`);
  assert.equal(colors.body,'rgb(248, 250, 252)');
  assert.equal(colors.text,'rgb(15, 23, 42)');
  assert.equal(colors.menu,'rgb(255, 255, 255)');
  assert.equal(colors.button,'Dark mode');
  assert.deepEqual(colors.bounds,before,'Opening the menu and changing appearance do not move the profile control');
  const lightIcons=await checkMenuIcons('light');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/profile-light-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await key('Escape');
  assert.ok(await ev(`document.activeElement===document.querySelector('${trigger}')`));
  await key('F10',8);
  await until(`document.querySelector('${content}').matches(':popover-open')`);
  await key('Escape');
  const lightModal=await checkModal('light',width);
  await nav('/pulls/pull?repo=repo&number=1&view=overview');
  assert.ok(await ev(`document.querySelector('.pull-workspace')`),'The pull overview renders with the shared application theme');
  await checkAmbient('light');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/pull-overview-light-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await nav('/repositories/repo?file=README.md');
  assert.equal(await ev('document.body.dataset.theme'),'light','Light mode survives document navigation');
  await checkAmbient('light');
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  try {
    await call('Page.navigate',{url:base+'/repositories/repo?tab=branches'});
    await until(`document.readyState==='complete'&&document.querySelector('${trigger}')`);
    assert.equal(await ev('document.body.dataset.theme'),'light','SSR preserves the selected palette without scripts');
    await checkAmbient('light');
    await click(trigger);
    assert.ok(await ev(`document.querySelector('.profile-popover').open`),'The profile menu remains a native disclosure without scripts');
    assert.ok(await ev(`document.querySelector('[data-bml-ref=appearance]').disabled&&document.querySelector('[data-bml-ref=signout]').disabled`));
  } finally {await call('Emulation.setScriptExecutionDisabled',{value:false});}
  await nav('/repositories/repo');
  await click(trigger);
  await until(`document.querySelector('${content}').matches(':popover-open')`);
  await click('[data-bml-ref=appearance]');
  await until(`document.body.dataset.theme==='dark'`);
  await checkAmbient('dark');
  await nav('/pulls/pull?repo=repo&number=1&view=overview');
  await checkAmbient('dark');
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/pull-overview-dark-${width}.png`,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
  await nav('/repositories/repo?file=README.md');
  assert.equal(await ev('document.body.dataset.theme'),'dark','Dark mode survives document navigation');
  reports.push({width,menu:bounds,colors,darkIcons,lightIcons,darkModal,lightModal,darkBackground,lightBackground});
}
await viewport(1440);
await nav('/repositories/repo?file=README.md&edit=true');
await until(`document.querySelector('form.code-widget .cm-editor')`);
assert.equal(await ev('document.body.dataset.theme'),'dark');
await ev(`window.darkEditorClasses=[...document.querySelector('form.code-widget .cm-editor').classList].filter(name=>name!=='cm-focused').sort().join(' ')`);
await click(trigger);
await until(`document.querySelector('${content}').matches(':popover-open')`);
await click('[data-bml-ref=appearance]');
await until(`document.body.dataset.theme==='light'&&[...document.querySelector('form.code-widget .cm-editor').classList].filter(name=>name!=='cm-focused').sort().join(' ')!==window.darkEditorClasses`);
assert.equal(await ev(`getComputedStyle(document.querySelector('form.code-widget .cm-editor')).backgroundColor`),'rgb(255, 255, 255)',
  'The live editable CodeMirror switches to the light palette');
await nav('/ci-agents/orchestrator');
await until(`document.querySelectorAll('[data-bml-json-editor] .jse-main').length===2`);
assert.ok(await ev(`[...document.querySelectorAll('[data-bml-json-editor]')].every(editor=>getComputedStyle(editor).getPropertyValue('--json-scheme').trim()==='light')`),
  'Both structured editors inherit the persisted light appearance');
await nav('/repositories/repo?file=README.md');
assert.equal(await ev(`getComputedStyle(document.querySelector('[data-bml-source-view]')).getPropertyValue('--source-keyword').trim()`),'#7e22ce',
  'Read-only source uses the light syntax palette');
for(const path of ['/repositories','/pipelines','/settings/github']) {
  await nav(path);
  await checkAmbient('light');
}
await click(trigger);
await until(`document.querySelector('[data-profile-heading] [data-profile-name]').textContent==='Alice'`);
await call('Fetch.enable',{patterns:[{urlPattern:base+'/graphql',requestStage:'Request'}]});
try {
  heldRequests.length=0;
  await click('[data-bml-ref=signout]');
  for (let attempt=0;attempt<100&&!heldRequests.length;attempt++) await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(heldRequests.length,1,'Sign out uses one SDK request');
  assert.ok(heldRequests[0].request.postData.includes('SignOut'));
  assert.ok(await ev(`document.querySelector('[data-bml-ref=signout]').disabled`));
  assert.equal(await ev(`getComputedStyle(document.querySelector('[data-bml-ref=signout]')).opacity`),'1',
    'The shared menu preserves the busy button spinner visibility');
  await call('Fetch.fulfillRequest',{requestId:heldRequests.shift().requestId,responseCode:503,body:btoa('Temporarily unavailable')});
  await until(`location.pathname==='/login'&&document.readyState==='complete'&&document.querySelector('.auth-form')`);
  assert.ok(await ev(`!document.cookie.split(';').some(cookie=>cookie.trim().startsWith('_git_test='))`),
    'The auth SDK clears the local session even when the remote sign-out endpoint is unavailable');
} finally { await call('Fetch.disable'); }
assert.deepEqual(errors,[]);
await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/profile-menu.json`,JSON.stringify(reports,null,2));
console.log('Profile menu passed: click/context activation, keyboard focus, light/dark palettes, navigation persistence and native disclosure at four widths; editable/source/JSON palettes and sign-out with an unavailable endpoint');
ws.close();
