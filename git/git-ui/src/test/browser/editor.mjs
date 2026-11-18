import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
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



const modifier = process.platform === 'darwin' ? 4 : 2;
const root = "document.querySelector('[data-bml-code-editor]')";
const content = `${root}.querySelector('.cm-content')`;
const field = `${root}.querySelector('textarea[name=content]')`;
async function key(key, modifiers = 0, text) {
  const code = {Tab:9,Enter:13,Escape:27,Home:36,End:35,Backspace:8,' ':32,'(':57}[key] ?? key.toUpperCase().charCodeAt(0);
  for(const type of ['keyDown','keyUp']) await call('Input.dispatchKeyEvent', {type,key,code:key==='('? 'Digit9' : key.length===1?'Key'+key.toUpperCase():key,windowsVirtualKeyCode:code,modifiers,...(type==='keyDown'&&text?{text}:{})});
}
async function replace(selector, text) {
  await ev(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  await call('Input.dispatchKeyEvent',{type:'keyDown',key:'a',code:'KeyA',windowsVirtualKeyCode:65,modifiers:modifier,commands:['selectAll']});
  await call('Input.dispatchKeyEvent',{type:'keyUp',key:'a',code:'KeyA',windowsVirtualKeyCode:65});
  if(text) await call('Input.insertText',{text}); else await key('Backspace');
}
async function edit(text) {
  await replace('[data-bml-code-editor] .cm-content',text);
  assert.equal(await ev(`${field}.value`),text,'The native commit field receives the editor draft');
}
async function pointer(selector) {
  await ev(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
  await ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  const rect=await ev(`(()=>{const element=document.querySelector(${JSON.stringify(selector)}),r=element.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  for(const type of ['mousePressed','mouseReleased'])await call('Input.dispatchMouseEvent',{type,...rect,button:'left',clickCount:1});
}
await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
await nav('/repositories/repo?file=README.md&edit=true');
await until(`${root}?.hasAttribute('data-code-ready')`);
await ev(`void(window.originalGitEditor=${root}.querySelector('.cm-editor'))`);
const grammars = [
  ['ts','const greeting: string = "Hello";'],['tsx','const greeting = <main>Hello</main>;'],
  ['js','const greeting = "Hello";'],['jsx','const greeting = <main>Hello</main>;'],['mjs','const greeting = "Hello";'],
  ['kt','val greeting = "Hello"'],['kts','val greeting = "Hello"'],['py','def greet():\n    return "Hello"'],
  ['java','class Greeting { String message = "Hello"; }'],['rs','fn main() { let greeting = "Hello"; }'],
  ['go','package main\nfunc main() { println("Hello") }'],['json','{"greeting":"Hello"}'],
  ['md','# Greeting\n\n**Hello**'],['markdown','# Greeting\n\n**Hello**'],
  ['html','<main title="Hello">Greeting</main>'],['vue','<template><main>Hello</main></template>'],
  ['bml','<component tag="greeting"><script server>val message = "Hello"</script><input /></component>'],
  ['css','body { color: red; }'],['scss','body { color: red; }'],['yaml','greeting: "Hello"'],['yml','greeting: "Hello"'],
  ['sql','SELECT name FROM profiles WHERE active = true;'],
];
const results=[];
for(const theme of ['dark','light']) {
  await ev(`document.body.dataset.theme=${JSON.stringify(theme)}`);
  for(const [extension,text] of grammars) {
    const path='example.'+extension;
    await replace('form.code-widget input[name=path]',path);
    await until(`${root}.dataset.path===${JSON.stringify(path)}&&${root}.hasAttribute('data-code-ready')`);
    await edit(text);
    await until(`${content}.querySelector('[class*="source-"]')`);
    const highlight=await ev(`(()=>{const editor=${root}.querySelector('.cm-editor'),tokens=[...${content}.querySelectorAll('[class*="source-"]')];return {sameEditor:editor===window.originalGitEditor,foreground:getComputedStyle(editor).color,tokens:tokens.map(token=>({text:token.textContent,color:getComputedStyle(token).color,kind:token.className}))};})()`);
    assert.ok(highlight.sameEditor,'Grammar and palette changes preserve the editor and its history');
    assert.ok(highlight.tokens.some(token=>token.color!==highlight.foreground),`${path} has visible syntax colors in ${theme}`);
    if(extension==='bml') assert.ok(highlight.tokens.some(token=>token.text==='val'&&token.kind.includes('source-keyword')),'BML server blocks retain Kotlin highlighting');
    results.push({theme,extension,...highlight});
  }
}

console.log('Git highlighting passed for all 22 original file grammars in dark and light palettes.');
await replace('form.code-widget input[name=path]','example.js');
await until(`${root}.hasAttribute('data-code-ready')`);
const original='const greeting = "Hello";\nconsole.log(greeting);';
await edit(original); await key('f',modifier);
await until("document.querySelector('.cm-search input[name=search]')");
await replace('.cm-search input[name=search]','greeting'); await key('Enter');
await until("document.querySelector('.cm-searchMatch')");
await replace('.cm-search input[name=replace]','salutation');
await pointer('.cm-search button[name=replaceAll]');
const replaced=original.replaceAll('greeting','salutation');
await until(`${field}.value===${JSON.stringify(replaced)}`); await key('Escape');
await ev(`${content}.focus()`); await key('z',modifier);
assert.equal(await ev(`${field}.value`),original,'Undo restores a search-and-replace transaction');
await key('z',modifier|8);
assert.equal(await ev(`${field}.value`),replaced,'Redo restores the replacement');

await edit('const greeting = "Hello";\ngre');
await key(' ',2);
await until("[...document.querySelectorAll('.cm-completionLabel')].some(option=>option.textContent==='greeting')");
// CodeMirror ignores acceptance keys for its default 75 ms completion interaction delay.
await new Promise(resolve=>setTimeout(resolve,100));
await key('Enter'); assert.equal(await ev(`${field}.value`),'const greeting = "Hello";\ngreeting','JavaScript completion inserts the local variable');
await edit(''); await key('(',8,'(');
assert.equal(await ev(`${field}.value`),'()','Typing an opening bracket inserts its pair');
await until("document.querySelectorAll('.cm-matchingBracket').length===2");
await edit('const value = 1;'); await key('Home'); await key('Tab');
assert.match(await ev(`${field}.value`),/^\s+const value/,'Tab indents through the original CodeMirror command');

await edit('function greet() {\n  return "Hello";\n}\ngreet();');
await until("document.querySelector('.cm-foldGutter .cm-gutterElement span')");
await pointer('.cm-foldGutter .cm-gutterElement span'); await until("document.querySelector('.cm-foldPlaceholder')");
await pointer('.cm-foldPlaceholder'); await until("!document.querySelector('.cm-foldPlaceholder')");
const fullscreenDraft=await ev(`${field}.value`);
for(const mode of ['native','viewport']) {
  if(mode==='viewport') await ev("document.querySelector('.file-workspace').requestFullscreen=()=>Promise.reject(new Error('Isolated fullscreen denial')); void 0");
  await pointer('[data-bml-fullscreen-button]');
  await until("document.querySelector('.file-workspace[data-bml-workspace-expanded]')");
  await ev(`${content}.focus()`); await key('End'); await key('Escape');
  await until("!document.querySelector('.file-workspace[data-bml-workspace-expanded]')");
  assert.equal(await ev(`${field}.value`),fullscreenDraft,'Escape from '+mode+' fullscreen preserves the focused editor draft');
  if(mode==='viewport') await ev("delete document.querySelector('.file-workspace').requestFullscreen");
}
await ev(`${content}.focus()`);
await key('End'); await key('Escape'); await key('Tab');
assert.ok(await ev("document.activeElement===document.querySelector('[data-bml-ref=wrap]')"),'Escape then Tab reaches Git’s original wrap control: '+await ev('document.activeElement.outerHTML.slice(0,500)'));
await ev(`${field}.form.reset()`);
await until("document.querySelector('form.code-widget').dataset.dirty==='false'");
assert.deepEqual(errors,[],'Editor regressions raised no browser exceptions');
await writeFile(join(resolve(process.env.GIT_UI_ARTIFACT_DIR??'build/reports/browser'),'git-editor-regression.json'),JSON.stringify({grammars:results,checks:['all original file grammars in both palettes','stable editor across path/theme changes','native form synchronization','search and replace','undo and redo','local JavaScript completion','automatic bracket pairs','matching brackets','Tab indentation','fold and unfold','Escape from focused native and viewport fullscreen','Escape then Tab','native reset and clean state']},null,2));
await ev('delete window.originalGitEditor');
console.log('Git editor passed: 22 grammars in both palettes, search/replace, undo/redo, completion, bracket pairing/matching, indentation, folding, focus exit and form reset.');
ws.close();
