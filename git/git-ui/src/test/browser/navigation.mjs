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

const root = 'nav[aria-label="Repository section"]';
const more = `${root} [data-bml-ref=more]`;
const menu = `${root} [data-bml-ref=menu]`;
const row = `${root} [data-bml-ref=row]`;
const query = selector => `document.querySelector(${JSON.stringify(selector)})`;
const opened = `${query(menu)}?.matches(':popover-open')`;
async function key(value, modifiers = 0) {
  const code = { Enter:13, Tab:9, Escape:27, ArrowDown:40, ArrowUp:38, Home:36, End:35, k:75 }[value];
  for (const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent', {
    type, key:value, code:value, windowsVirtualKeyCode:code, modifiers,
    ...(value==='Enter' && type==='keyDown' ? {text:'\r'} : {}),
  });
}
async function viewport(width) {
  await call('Emulation.setDeviceMetricsOverride', {width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
}
async function openMenu() {
  await ev(`${query(more)}.focus()`);
  await key('Enter');
  await until(opened);
}
const prefix = `bml-${Buffer.from('io.bosca:bml-design').toString('hex')}-`;
for (const width of [1920,1440,1024,390]) {
  await viewport(width);
  await nav('/repositories/repo?file=README.md');
  assert.ok(await ev(`['app-header','tabs','view-tabs'].every(tag=>document.querySelector('[data-bml-component="${prefix}'+tag+'"]'))`), 'Git mounts the library client components');
  assert.ok(await ev(`['bosca-mark','page-shell','page-heading'].every(tag=>document.querySelector('[data-bml-c="${prefix}'+tag+'"]'))`), 'Git renders the library layout and mark');
  assert.deepEqual(await ev(`(() => { const crumb=document.querySelector('.breadcrumb'); return [crumb.firstElementChild.tagName,crumb.firstElementChild.textContent.trim(),[...crumb.children].filter(e=>e.textContent.trim()==='Git').length]; })()`), ['SPAN','Git',1], 'Git supplies its plain product breadcrumb once');
  assert.ok(await ev(`(() => {const row=${query(row)},link=row.querySelector('[aria-current=page]'),r=row.getBoundingClientRect(),a=link.getBoundingClientRect();return !link.hidden&&a.left>=r.left-1&&a.right<=r.right+1&&document.documentElement.scrollWidth<=innerWidth;})()`), 'The current destination stays visible without page overflow');
  await ev(`document.querySelector('.view-tabs [aria-selected=true]').focus()`);
  await key('End');
  assert.ok(await ev(`(() => {const tab=document.querySelector('.view-tabs [aria-selected=true]'),panel=document.getElementById(tab.getAttribute('aria-controls'));return tab.textContent.trim()==='Source'&&tab===document.activeElement&&!panel.hidden&&panel.getAttribute('aria-labelledby')===tab.id&&document.querySelector('[aria-label=Preview][role=tabpanel]').hidden;})()`), 'View tabs preserve Git panel relationships and visibility');
  await key('Home');
  assert.ok(await ev(`document.querySelector('[aria-label=Source][role=tabpanel]').hidden&&!document.querySelector('[aria-label=Preview][role=tabpanel]').hidden`));
}
await viewport(390);
await nav('/repositories/repo?file=README.md');
await until(`!${query(more)}.hidden`);
await openMenu();
assert.ok(await ev(`${query(menu)}.firstElementChild===document.activeElement`));
assert.ok(await ev(`${query(menu)}.children.length>1`), 'Fixture must have multiple overflow destinations');
await key('Tab');
assert.ok(await ev(`${opened}&&${query(menu)}.children[1]===document.activeElement`), 'Native Tab between overflow destinations keeps the menu open');
await key('Tab',8);
assert.ok(await ev(`${opened}&&${query(menu)}.firstElementChild===document.activeElement`), 'Shift+Tab between overflow destinations keeps the menu open');
await key('End');
assert.ok(await ev(`${query(menu)}.lastElementChild===document.activeElement`));
await key('ArrowDown');
assert.ok(await ev(`${query(menu)}.firstElementChild===document.activeElement`));
await key('ArrowUp');
assert.ok(await ev(`${query(menu)}.lastElementChild===document.activeElement`));
await key('Escape');
await until(`!(${opened})`);
assert.ok(await ev(`${query(more)}===document.activeElement`));
await openMenu();
await key('End');
await key('Tab');
await until(`!(${opened})`);
assert.ok(await ev(`!${query(menu)}.contains(document.activeElement)&&document.activeElement!==${query(more)}`), 'Tab leaves the destination group');
await openMenu();
const colors = await ev(`(() => {
  const nav=${query(root)},popup=${query(menu)},icon=document.querySelector('.title-mark'),paths=[...document.querySelectorAll('.app-header .brand path')];
  const controls=[nav,popup,icon,...paths], geometry=()=>controls.map(e=>{const r=e.getBoundingClientRect();return [r.x,r.y,r.width,r.height]});
  const before=geometry(),style=document.createElement('style');
  style.textContent='.git-document { --mark-top:#112233; --mark-right:#445566; --mark-left:#778899; --on-accent:#224455; } .git-document nav[aria-label="Repository section"], .git-document nav[aria-label="Repository section"] [popover] { --bg-2:#edf4f6; --fg-1:#264958; }';
  document.head.append(style);
  try {return {before,after:geometry(),fills:paths.map(e=>getComputedStyle(e).fill),icon:getComputedStyle(icon).color,
    background:getComputedStyle(popup).backgroundColor,color:getComputedStyle(popup.querySelector('a')).color,owned:nav.contains(popup),
    controlled:nav.querySelector('[data-bml-ref=more]').getAttribute('aria-controls')===popup.id,
    fits:(()=>{const r=popup.getBoundingClientRect();return r.left>=11&&r.right<=innerWidth-11&&r.top>=11&&r.bottom<=innerHeight-11;})()};}
  finally {style.remove();}
})()`);
assert.deepEqual(colors.fills,['rgb(17, 34, 51)','rgb(68, 85, 102)','rgb(119, 136, 153)']);
assert.equal(colors.icon,'rgb(34, 68, 85)');
assert.equal(colors.background,'rgb(237, 244, 246)');
assert.equal(colors.color,'rgb(38, 73, 88)');
assert.ok(colors.owned&&colors.controlled&&colors.fits, 'The fitted popup retains its owning theme subtree and trigger relationship');
assert.deepEqual(colors.after,colors.before,'Consumer CSS recolors the shared navigation without changing geometry');
await ev(`window.navigationDestination=document.activeElement.href`);
await viewport(1440);
await until(`${query(more)}.hidden&&!(${opened})`);
assert.ok(await ev(`${query(row)}.contains(document.activeElement)&&document.activeElement.href===window.navigationDestination`), 'When links fit, focus follows the same destination into the row');
await viewport(390);
await until(`!${query(more)}.hidden`);
await openMenu();
const destination=await ev(`${query(menu)}.firstElementChild.href`);
await key('Enter');
await until(`location.href===${JSON.stringify(destination)}&&document.readyState==='complete'&&${query(root)}?.hasAttribute('data-tabs-enhanced')`);
assert.ok(await ev(`${query(row)}.querySelector('[aria-current=page]').href===location.href&&!${query(row)}.querySelector('[aria-current=page]').hidden`),'Overflow destinations remain native links and current pages stay visible');
await until(`Boolean(document.querySelector('[data-bml-component=git-search-link]'))&&Boolean(document.querySelector('[data-bml-ref=signout]'))&&!document.querySelector('[data-bml-ref=signout]').disabled`);
await key('k',2);
await until(`location.pathname==='/search'&&document.querySelector('input[name=q]')`);
await viewport(1440);
await nav('/repositories/repo');
await key('k',4);
await until(`location.pathname==='/search'&&document.querySelector('input[name=q]')`);
for (const width of [1920,1440,1024,390]) {
  await viewport(width);
  await call('Emulation.setScriptExecutionDisabled',{value:true});
  try {
    await call('Page.navigate',{url:base+'/repositories/repo?tab=secrets'});
    await until(`location.href===${JSON.stringify(base+'/repositories/repo?tab=secrets')}&&document.readyState==='complete'&&Boolean(${query(row)}?.querySelector('a'))`);
    assert.ok(await ev(`!${query(root)}.hasAttribute('data-tabs-enhanced')&&${query(more)}.hidden&&[...${query(row)}.querySelectorAll(':scope>a')].every(a=>!a.hidden)&&document.documentElement.scrollWidth<=innerWidth`), 'Without scripts every native destination remains available inside the page width');
    await ev(`${query(row)}.lastElementChild.previousElementSibling.focus()`);
    assert.ok(await ev(`(() => {const row=${query(row)},a=document.activeElement.getBoundingClientRect(),r=row.getBoundingClientRect();return a.left>=r.left-1&&a.right<=r.right+1;})()`), 'Native focus reveals the last destination in the scrollable row');
    const href=await ev('document.activeElement.href');
    await key('Enter');
    await until(`location.href===${JSON.stringify(href)}&&document.readyState==='complete'&&Boolean(${query(row)}?.querySelector('[aria-current=page]'))`);
    assert.ok(await ev(`${query(row)}.querySelector('[aria-current=page]').href===location.href`));
  } finally { await call('Emulation.setScriptExecutionDisabled',{value:false}); }
}
assert.deepEqual(errors,[]);
console.log('Navigation passed: library ownership, breadcrumbs, Markdown panels, native overflow focus/navigation, resize, CSS palette, shortcut and no-script links at four widths');
ws.close();
