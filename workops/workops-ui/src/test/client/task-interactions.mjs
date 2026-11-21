import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../../../bml/bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export * from './src/main/client/helpers/bql-query.ts'; export * from './src/main/client/helpers/search-rows.ts'; export * from './src/main/client/helpers/task-create.ts';`, resolveDir: process.cwd() }, bundle: true, write: false, format: 'esm', platform: 'browser' });
const { workOpsBqlQuery, workOpsSearchRows, workOpsTaskCreation } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const tick = (ms = 0) => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const diagnostic = { message: 'Expected value', start: '8', end: '8', hint: 'Choose a status', description: 'Expected value (query range 8–8). Choose a status' };

async function fixture(run) {
  const window = new Window({ url: 'https://workops.test/workops/tasks?bql=initial&offset=25' });
  for (const key of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLFormElement', 'HTMLTextAreaElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'SubmitEvent', 'localStorage']) Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  const navigation = [], cleanups = [];
  Object.defineProperty(globalThis, 'location', { configurable: true, value: { href: window.location.href, assign: url => navigation.push(String(url)), replace: url => navigation.push(String(url)) } });
  document.body.innerHTML = `<form data-validation-message=""><textarea name="bql">initial</textarea><div class="cm-content" role="textbox" contenteditable="true" tabindex="0"></div>
    <div data-bml-ref="validation" hidden><span data-bml-ref="validationText"></span></div><button type="button" data-workops-clear-query>Clear</button><button type="submit">Search</button></form>
    <div id="rows"><table><tbody>${[1,2,3].map(id => `<tr data-workops-task-row><td><a data-workops-task-link href="/workops/tasks/${id}">Task ${id}</a></td></tr>`).join('')}</tbody></table></div>
    <button id="outside">New task</button><input id="input" /><textarea id="textarea"></textarea><select id="select"></select>
    <div contenteditable="true" id="editable" tabindex="0"></div><dialog open><button id="modal">Modal action</button></dialog><div role="listbox"><span id="option" role="option">Choice</span></div>`;
  for (const row of document.querySelectorAll('tr')) row.scrollIntoView = () => {};
  const context = root => ({ root, scoped: name => `${root.id || 'query'}-${name}`, ref: name => root.querySelector(`[data-bml-ref="${name}"]`),
    onUpdate() {}, onUnmount: cb => cleanups.push(cb), on(target, type, listener, options) { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); } });
  const form = document.querySelector('form'), field = form.querySelector('textarea');
  const input = source => { field.value = source; field.dispatchEvent(new Event('input', { bubbles: true })); };
  const submit = () => { const event = new SubmitEvent('submit', { bubbles: true, cancelable: true }); form.dispatchEvent(event); return event.defaultPrevented; };
  const key = (target, value, options = {}) => { const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }); target.dispatchEvent(event); return event; };
  try { await run({ form, field, input, submit, key, context, navigation, unmount: () => cleanups.splice(0).forEach(cb => cb()) }); }
  finally { cleanups.forEach(cb => cb()); await window.happyDOM.abort(); }
}

test('configured choices retain reload feedback and translate transport failures without accepting stale results', () => fixture(async ({ context, unmount }) => {
  const root = document.createElement('div'); document.body.append(root);
  root.innerHTML = '<div data-bml-ref="formRetry" hidden></div><div data-workops-form-key="custom" data-workops-form-version="4"><div id="choice"></div></div>';
  const retry = root.querySelector('[data-bml-ref=formRetry]');
  let handler = async () => { throw new Error('BML contract HTTP 500'); };
  workOpsTaskCreation(context(root), { choices: (...args) => handler(...args) });
  const request = (signal = new AbortController().signal) => {
    let promise;
    root.querySelector('#choice').dispatchEvent(new CustomEvent('schemaformchoicesrequest', { bubbles: true,
      detail: { fieldKey:'form-1', property:'owner', query:'owner', offset:25, signal, respondWith: result => promise = result } }));
    assert.ok(promise); return promise;
  };
  const transport = await request().catch(error => error);
  assert.equal(transport.message, 'Could not load choices. Try again.');
  assert.equal(transport.cause.message, 'BML contract HTTP 500');
  assert.equal(retry.hidden, true, 'temporary lookup failures use the field retry');
  handler = async (...args) => { assert.deepEqual(args, ['custom',4,'form-1','owner',25]); return { error:'The task form changed. Reload the form.' }; };
  assert.equal((await request().catch(error => error)).message, 'The task form changed. Reload the form.');
  assert.equal(retry.hidden, false, 'a changed schema exposes draft-preserving form recovery');
  const pending = deferred(); handler = () => pending.promise;
  const controller = new AbortController(), stale = request(controller.signal).catch(error => error);
  controller.abort(); pending.resolve({options:'[]'}); assert.equal((await stale).name, 'AbortError');
  const last = deferred(); handler = () => last.promise;
  const closing = request().catch(error => error); unmount(); last.resolve({options:'[]'});
  assert.equal((await closing).name,'AbortError');
}));
test('project changes call the compiled action callback with the full draft and recover after a rejected change', () => fixture(async ({ context }) => {
  const root = document.createElement('div'); document.body.append(root);
  root.innerHTML = '<div data-workops-create-project="project"><form data-bml-schema-form><input name="summary" value="Keep this" /></form><p data-bml-ref="transportError" hidden></p></div>';
  const pending = deferred(), calls = [];
  workOpsTaskCreation(context(root), { async choices() { return { options: '[]' }; } }, (...args) => { calls.push(args); return calls.length === 1 ? pending.promise : Promise.resolve(); });
  const draft = { projectId: 'other', summary: 'Keep this', count: 0, enabled: false, optional: null };
  const change = () => root.querySelector('input').dispatchEvent(new CustomEvent('schemaformchange', { bubbles: true, detail: { property: 'projectId', value: 'other', data: draft } }));
  change(); assert.deepEqual(calls, [['other', JSON.stringify(draft)]]); assert.equal(root.querySelector('form').inert, true);
  change(); assert.equal(calls.length, 1);
  pending.reject(new Error('Project form unavailable')); await tick();
  assert.equal(root.querySelector('form').inert, false); assert.equal(root.querySelector('p').hidden, false);
  assert.equal(root.querySelector('p').textContent, 'Project form unavailable');
  change(); await tick(); assert.equal(calls.length, 2); assert.equal(root.querySelector('form').inert, false); assert.equal(root.querySelector('p').hidden, true);
}));
test('static providers load once per form and filter and page the same snapshot', () => fixture(async ({ context }) => {
  const root=document.createElement('div');document.body.append(root);
  root.innerHTML='<div data-workops-form-key="custom" data-workops-form-version="4" data-workops-static-choices="form-1" data-bml-preserve="instance-a"><div id="choice"></div></div>';
  const rows=Array.from({length:31},(_,index)=>({value:String(index+1),label:`Choice ${index+1}`}));
  const pending=deferred(),calls=[];
  workOpsTaskCreation(context(root),{choices(...args){calls.push(args);return calls.length===1?pending.promise:Promise.resolve({options:JSON.stringify(rows)});}});
  const request=(query,offset=0,signal=new AbortController().signal)=>{
    let response;
    root.querySelector('#choice').dispatchEvent(new CustomEvent('schemaformchoicesrequest',{bubbles:true,detail:{fieldKey:'form-1',property:'choice',query,offset,signal,respondWith:value=>response=value}}));
    return response;
  };
  const controller=new AbortController(),initial=request(null,0,controller.signal).catch(error=>error);
  const second=request('',25);controller.abort();assert.equal(calls.length,1);
  pending.resolve({options:JSON.stringify(rows)});
  assert.equal((await initial).name,'AbortError');
  assert.deepEqual((await second).options,rows.slice(25));assert.equal((await second).previousOffset,0);
  assert.equal((await request(null)).options.length,31,'initial labels include selections outside the first displayed page');
  assert.deepEqual((await request('Choice 31')).options,[rows[30]]);assert.equal(calls.length,1);
  assert.deepEqual(calls[0],['custom',4,'form-1',null,0]);
  root.firstElementChild.dataset.bmlPreserve='instance-b';await request(null);assert.equal(calls.length,2,'explicit form reload refreshes the provider');
}));
test('a failed static provider can retry instead of caching its failure', () => fixture(async ({context})=>{
  const root=document.createElement('div');document.body.append(root);
  root.innerHTML='<div data-workops-form-key="custom" data-workops-form-version="4" data-workops-static-choices="form-1"><div id="choice"></div></div>';
  let calls=0;
  workOpsTaskCreation(context(root),{async choices(){if(++calls===1)throw new Error('BML contract HTTP 500');return {options:'[]'};}});
  const request=()=>{let response;root.querySelector('#choice').dispatchEvent(new CustomEvent('schemaformchoicesrequest',{bubbles:true,detail:{fieldKey:'form-1',property:'choice',query:null,offset:0,signal:new AbortController().signal,respondWith:value=>response=value}}));return response;};
  assert.match((await request().catch(error=>error)).message,/Could not load choices/);
  assert.deepEqual((await request()).options,[]);assert.equal(calls,2);
}));
test('live BQL keeps current diagnostics and ignores an older response after the draft changes', () => fixture(async ({ form, field, input, submit, context }) => {
  const requests = [];
  workOpsBqlQuery(context(form), { validate(source) { const pending = deferred(); requests.push({ source, ...pending }); return pending.promise; } });
  input('status ='); await tick(430); assert.equal(requests.length, 1);
  input('ORDER BY modified DESC'); await tick(430); assert.equal(requests.length, 2);
  requests[0].resolve([diagnostic]); await tick(); assert.equal(form.querySelector('[data-bml-ref=validation]').hidden, true);
  requests[1].resolve([]); await tick(); assert.equal(submit(), false);
  assert.equal(field.value, 'ORDER BY modified DESC');
  input('status ='); await tick(430); requests[2].resolve([diagnostic]); await tick();
  assert.equal(form.querySelector('[data-bml-ref=validationText]').textContent, diagnostic.description);
  assert.equal(form.querySelector('.cm-content').getAttribute('aria-invalid'), 'true'); assert.equal(submit(), true);
  input('project = WORKOPS'); assert.equal(form.querySelector('[data-bml-ref=validation]').hidden, true); assert.equal(submit(), false);
}));

test('validation failures remain visible without blocking authoritative Search, and unmount suppresses late results', () => fixture(async ({ form, input, submit, context, unmount }) => {
  const requests = [];
  workOpsBqlQuery(context(form), { validate() { const pending = deferred(); requests.push(pending); return pending.promise; } });
  input('project = WORKOPS'); await tick(430); requests[0].reject(new Error('Validation service unavailable')); await tick();
  assert.match(form.querySelector('[data-bml-ref=validationText]').textContent, /Validation service unavailable/); assert.equal(submit(), false);
  input('status ='); await tick(430); unmount(); requests[1].resolve([diagnostic]); await tick();
  assert.equal(form.querySelector('[data-bml-ref=validation]').hidden, true);
}));

test('initial diagnostics block the unchanged query, while slash, submit and clear retain native search behavior', () => fixture(async ({ form, field, key, context, navigation }) => {
  form.dataset.validationMessage = 'Initial query is invalid';
  workOpsBqlQuery(context(form), { async validate() { return []; } });
  const event = new SubmitEvent('submit', { bubbles: true, cancelable: true }); form.dispatchEvent(event); assert.equal(event.defaultPrevented, true);
  assert.equal(key(document.body, '/').defaultPrevented, true); assert.equal(document.activeElement, form.querySelector('.cm-content'));
  assert.equal(key(document.activeElement, '/').defaultPrevented, false);
  let submits = 0; form.requestSubmit = () => submits++; key(field, 'Enter', { ctrlKey: true }); assert.equal(submits, 1);
  form.querySelector('[data-workops-clear-query]').click();
  const next = new URL(navigation[0]); assert.equal(next.searchParams.get('bql'), ''); assert.equal(next.searchParams.has('offset'), false);
  assert.equal(localStorage.getItem('workops-tasks-bql'), null);
}));

test('j and k remain bounded, Enter uses the selected task and typing/modal/control shortcuts are left alone', () => fixture(({ key, context }) => {
  const root = document.getElementById('rows'), links = [...root.querySelectorAll('a')], visited = [];
  links.forEach(link => link.addEventListener('click', event => { event.preventDefault(); visited.push(link.getAttribute('href')); }));
  workOpsSearchRows(context(root));
  key(document.body, 'j'); assert.equal(document.activeElement, links[0]);
  key(links[0], 'k'); assert.equal(document.activeElement, links[0]);
  for (let i=0;i<5;i++) key(document.activeElement, 'j'); assert.equal(document.activeElement, links[2]);
  assert.equal(root.querySelectorAll('[data-selected]').length, 1);
  key(links[2], 'Enter'); assert.deepEqual(visited, ['/workops/tasks/3']);
  for (const id of ['input','textarea','select','editable','modal','option']) assert.equal(key(document.getElementById(id), 'j').defaultPrevented, false, id);
  assert.equal(key(document.body, 'j', { ctrlKey: true }).defaultPrevented, false);
  assert.equal(key(document.body, 'j', { isComposing: true }).defaultPrevented, false);
  key(document.getElementById('outside'), 'Enter'); assert.equal(visited.length, 1);
}));


test('specification queries use their own catalog and browser preference', () => fixture(async ({ form, field, input, submit, context }) => {
  form.dataset.bqlKind = 'spec';
  localStorage.setItem('workops-tasks-bql', 'task preference');
  const requests = [];
  workOpsBqlQuery(context(form), {
    validate() { throw new Error('Task validation must not handle specifications'); },
    async validateSpec(source) { requests.push(source); return []; },
  });
  input('owner = currentUser()'); await tick(430); assert.deepEqual(requests, ['owner = currentUser()']);
  assert.equal(submit(), false); assert.equal(field.value, 'owner = currentUser()');
  assert.equal(localStorage.getItem('workops-specs-bql'), 'owner = currentUser()');
  assert.equal(localStorage.getItem('workops-tasks-bql'), 'task preference');
}));
