import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export * from './src/main/client/helpers/profile-mentions.ts';
  export * from './src/main/client/helpers/mention-menu.ts'; export * from './src/main/client/helpers/comment-editor.ts';
  export * from './src/main/client/helpers/document-schema.ts'; export * from './src/main/client/helpers/document-mentions.ts';
  export { Editor } from '@tiptap/core'; export { default as StarterKit } from '@tiptap/starter-kit';`, resolveDir: process.cwd() },
  bundle: true, write: false, format: 'esm', platform: 'browser', loader: { '.css': 'empty' } });
const { profileMentionRange, replaceProfileMention, mentionMenu, commentEditor, documentExtensions, documentMentions, documentTitle, Editor, StarterKit } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const wait = () => new Promise(resolve => setTimeout(resolve, 180));
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const choice = (id, label = id, handle = id) => ({ id, label, handle });

async function fixture(check) {
  const window = new Window({ url: 'http://mentions.test/' });
  for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLTextAreaElement', 'HTMLButtonElement', 'Event', 'CustomEvent', 'KeyboardEvent', 'MouseEvent', 'MutationObserver', 'navigator', 'getComputedStyle']) Object.defineProperty(globalThis, name, {
    configurable: true, value: name === 'getComputedStyle' ? window[name].bind(window) : window[name],
  });
  globalThis.requestAnimationFrame = window.requestAnimationFrame.bind(window);
  globalThis.cancelAnimationFrame = window.cancelAnimationFrame.bind(window);
  document.body.innerHTML = '<form><div id="root" data-mentions data-value=""><textarea id="comment" data-bml-ref="commentBody"></textarea><small id="comment-hint" data-bml-ref="shortcut"></small><button type="submit">Post</button></div></form>';
  const root = document.getElementById('root'), field = root.querySelector('textarea'), form = field.form, cleanups = [], updates = [];
  const ctx = { root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`),
    onUpdate: cb => updates.push(cb), onUnmount: cb => cleanups.push(cb),
    on: (target, type, listener, options) => { target.addEventListener(type, listener, options); cleanups.push(() => target.removeEventListener(type, listener, options)); } };
  const type = value => { field.focus(); field.value = value; field.setSelectionRange(value.length, value.length); field.dispatchEvent(new Event('input', { bubbles: true })); };
  const key = (value, options = {}) => { const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }); field.dispatchEvent(event); return event; };
  const menu = () => root.querySelector('.bml-mention-menu');
  try { await check({ root, field, form, ctx, type, key, menu, update: () => updates.forEach(cb => cb()), cleanup: () => cleanups.splice(0).forEach(cb => cb()) }); }
  finally { cleanups.splice(0).forEach(cb => cb()); await window.happyDOM.abort(); }
}

test('profile ranges preserve Studio handle boundaries and replace the entire active suffix', () => {
  for (const value of ['a@jamie', 'user_@jamie', '@@jamie', '@jamie!', '@two words']) assert.equal(profileMentionRange(value, value.length), null, value);
  assert.deepEqual(profileMentionRange('@', 1), { from: 0, to: 1, query: '' });
  const value = '👋 (@ja.old-name rest';
  const range = profileMentionRange(value, 7);
  assert.deepEqual(range, { from: 4, to: 16, query: 'ja' });
  assert.deepEqual(replaceProfileMention(value, range, 'j.new'), { value: '👋 (@j.new rest', cursor: 10 });
  assert.deepEqual(replaceProfileMention('Hi @ja!', { from: 3, to: 6, query: 'ja' }, 'jamie'), { value: 'Hi @jamie !', cursor: 10 });
  assert.deepEqual(profileMentionRange('@abc', 100), { from: 0, to: 4, query: 'abc' });
  assert.equal(profileMentionRange('@abc', -1), null);
});

test('plain comments query an empty @ and insert handles without submitting or losing caret position', () => fixture(async ({ root, field, form, ctx, type, key, menu }) => {
  let posts = 0;
  form.requestSubmit = () => posts++;
  root.addEventListener('mentionsearch', event => {
    assert.equal(event.detail.kind, 'profile'); assert.equal(event.detail.query, '');
    event.detail.respondWith([choice('first', 'Jamie', 'jamie'), choice('second', 'Avery', 'avery')]);
  });
  commentEditor(ctx); type('@');
  assert.equal(menu().textContent, 'Searching…');
  await wait();
  assert.equal(field.getAttribute('aria-controls'), 'comment-mentions');
  assert.equal(field.hasAttribute('aria-expanded'), false);
  assert.equal(key('ArrowDown').defaultPrevented, true);
  assert.equal(field.getAttribute('aria-activedescendant'), 'comment-mentions-1');
  assert.equal(key('Enter').defaultPrevented, true);
  assert.equal(field.value, '@avery '); assert.equal(field.selectionStart, 7);
  assert.equal(posts, 0); assert.equal(menu().hidden, true);
  assert.equal(field.hasAttribute('aria-activedescendant'), false);
  key('Enter', { ctrlKey: true }); assert.equal(posts, 1);
}));

test('closing an inactive mention menu preserves another editor popup accessibility state', () => fixture(async ({ root, field }) => {
  const menu = mentionMenu({ root, field, id: 'test', kind: 'profile', enabled: () => true, select() {} });
  root.addEventListener('mentionsearch', event => event.detail.respondWith([choice('jamie')]));
  menu.update({ from: 0, to: 2, query: 'j' }); await wait();
  field.setAttribute('aria-controls', 'document-commands');
  field.setAttribute('aria-activedescendant', 'command-1');
  menu.close();
  assert.equal(field.getAttribute('aria-controls'), 'document-commands');
  assert.equal(field.getAttribute('aria-activedescendant'), 'command-1');
  menu.destroy();
  assert.equal(field.getAttribute('aria-controls'), 'document-commands');
}));

test('comment suggestions retain eight bounded choices while rich mentions retain twenty and wrap', () => fixture(async ({ root, field }) => {
  for (const [kind, count] of [['profile', 8], ['entity', 20]]) {
    let selected;
    const menu = mentionMenu({ root, field, id: kind, kind, enabled: () => true, select: value => selected = value.id });
    const respond = event => event.detail.respondWith(Array.from({ length: 25 }, (_, index) => choice(`person-${index}`)));
    root.addEventListener('mentionsearch', respond);
    menu.update({ from: 0, to: 2, query: 'p' }); await wait();
    assert.equal(root.querySelectorAll('[role=option]').length, count);
    assert.equal(menu.keydown(new KeyboardEvent('keydown', { key: 'ArrowUp' })), true);
    const index = kind === 'entity' ? 19 : 0;
    assert.equal(field.getAttribute('aria-activedescendant'), `${kind}-mentions-${index}`);
    assert.equal(menu.keydown(new KeyboardEvent('keydown', { key: 'Enter' })), true);
    assert.equal(selected, `person-${index}`);
    root.removeEventListener('mentionsearch', respond); menu.destroy();
  }
}));

test('stale mention results are aborted and a lookup error remains distinct from no results', () => fixture(async ({ root, ctx, type, menu }) => {
  const requests = [];
  root.addEventListener('mentionsearch', event => {
    let resolve, reject;
    event.detail.respondWith(new Promise((yes, no) => { resolve = yes; reject = no; }));
    requests.push({ ...event.detail, resolve, reject });
  });
  commentEditor(ctx); type('@a'); await wait(); type('@ab'); await wait();
  assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve([choice('fresh')]); await tick();
  requests[0].resolve([choice('stale')]); await tick();
  assert.match(menu().textContent, /fresh/); assert.doesNotMatch(menu().textContent, /stale/);
  type('@abc'); await wait(); requests[2].resolve([]); await tick();
  assert.equal(menu().textContent, 'No mentions found.');
  let failure;
  root.addEventListener('mentionsearcherror', event => failure = event.detail.error);
  type('@abcd'); await wait(); const error = new Error('denied'); requests[3].reject(error); await tick();
  assert.match(menu().textContent, /Could not load mentions/); assert.equal(failure, error);
}));

test('Escape dismisses the current query, IME does not pick a result, and pointer choice retains focus', () => fixture(async ({ root, field, ctx, type, key, menu }) => {
  root.addEventListener('mentionsearch', event => event.detail.respondWith([choice('jamie')]));
  commentEditor(ctx); type('@j'); await wait();
  assert.equal(key('Enter', { isComposing: true }).defaultPrevented, false);
  assert.equal(field.value, '@j');
  assert.equal(key('Escape').defaultPrevented, true);
  field.dispatchEvent(new KeyboardEvent('keyup', { key: 'Escape' }));
  assert.equal(menu().hidden, true);
  type('@ja'); await wait();
  const option = menu().querySelector('[role="option"]');
  assert.equal(option.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true })), false);
  option.click(); assert.equal(field.value, '@jamie '); assert.equal(document.activeElement, field);
}));

test('pending actions, readonly, selection ranges and composition close suggestions without changing a draft', () => fixture(async ({ root, field, form, ctx, type, menu }) => {
  let requests = 0;
  root.addEventListener('mentionsearch', event => { requests++; event.detail.respondWith([choice('jamie')]); });
  commentEditor(ctx); type('@ja'); await wait();
  form.setAttribute('data-bml-action-pending', ''); await tick();
  assert.equal(menu().hidden, true); assert.equal(field.readOnly, true); assert.equal(field.value, '@ja');
  form.removeAttribute('data-bml-action-pending'); await tick();
  field.dispatchEvent(new Event('compositionstart')); type('@jam'); await wait();
  assert.equal(menu().hidden, true); assert.equal(requests, 1);
  field.dispatchEvent(new Event('compositionend')); await wait(); assert.equal(requests, 2);
  field.setSelectionRange(0, 2); field.dispatchEvent(new Event('select')); assert.equal(menu().hidden, true);
  field.readOnly = true; type('@jami'); await wait(); assert.equal(menu().hidden, true);
}));

test('unmount aborts an outstanding lookup and removes menu and listeners', () => fixture(async ({ root, field, ctx, type, key, menu, cleanup }) => {
  let resolve, signal;
  root.addEventListener('mentionsearch', event => { signal = event.detail.signal; event.detail.respondWith(new Promise(yes => resolve = yes)); });
  field.setAttribute('aria-controls', 'existing-controls');
  commentEditor(ctx); type('@ja'); await wait(); cleanup();
  assert.equal(signal.aborted, true); assert.equal(menu(), null);
  assert.equal(field.getAttribute('aria-controls'), 'existing-controls');
  resolve([choice('late')]); await tick(); assert.equal(menu(), null);
  assert.equal(key('Enter').defaultPrevented, false);
}));

test('rich suggestions skip empty queries and filter duplicate or unnamed choices without exposing IDs', () => fixture(async ({ root, field }) => {
  let requests = 0, selection;
  root.addEventListener('mentionsearch', event => {
    requests++; assert.equal(event.detail.kind, 'entity');
    event.detail.respondWith([{ id: 'internal-1', label: 'Document', entityType: 'metadata' }, { id: 'internal-1', label: 'Duplicate', entityType: 'metadata' }, { id: 'internal-2', label: '' }]);
  });
  const menu = mentionMenu({ root, field, id: 'rich', kind: 'entity', enabled: () => true, select: (choice, range) => selection = { choice, range } });
  try {
    menu.update({ from: 1, to: 2, query: '' }); await wait(); assert.equal(requests, 0);
    menu.update({ from: 1, to: 3, query: 'd' }); await wait();
    assert.equal(root.querySelectorAll('[role="option"]').length, 1);
    assert.doesNotMatch(root.querySelector('.bml-mention-menu').textContent, /internal-/);
    assert.equal(menu.keydown(new KeyboardEvent('keydown', { key: 'Enter' })), true);
    assert.equal(selection.choice.id, 'internal-1'); assert.deepEqual(selection.range, { from: 1, to: 3, query: 'd' });
  } finally { menu.destroy(); }
}));

test('a preserved field transferred to a new component keeps one menu and its accessible relationship', () => fixture(async ({ root, field }) => {
  const nextRoot = document.createElement('div'); root.after(nextRoot);
  const options = root => ({ root, field, id: field.id, kind: 'profile', enabled: () => true, select() {} });
  for (const target of [root, nextRoot]) target.addEventListener('mentionsearch', event => event.detail.respondWith([choice('jamie')]));
  const first = mentionMenu(options(root)); first.update({ from: 0, to: 3, query: 'ja' });
  nextRoot.append(field);
  const next = mentionMenu(options(nextRoot)); next.update({ from: 0, to: 3, query: 'ja' });
  first.destroy();
  try {
    await wait();
    assert.equal(field.getAttribute('aria-controls'), 'comment-mentions');
    assert.equal(document.querySelectorAll('[role=listbox]').length, 1);
    assert.equal(field.getAttribute('aria-activedescendant'), 'comment-mentions-0');
  } finally { first.destroy(); next.destroy(); }
}));

test('rich editor inserts typed mention nodes with one following space and includes visible labels in its title', () => fixture(async ({ root }) => {
  root.addEventListener('mentionsearch', event => event.detail.respondWith([{ id: 'content-id', label: 'Launch', entityType: 'metadata' }]));
  const target = document.createElement('div'); root.append(target);
  const editor = new Editor({ element: target,
    extensions: [StarterKit.configure({ document: false }), ...documentExtensions('block+', true), documentMentions(root, 'rich')],
    content: { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '@la suffix' }] }] } });
  try {
    editor.view.dom.focus(); editor.commands.setTextSelection(4); await wait();
    assert.equal(editor.isFocused, true);
    assert.equal(root.querySelector('.bml-mention-menu').hidden, false);
    editor.view.dom.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    const content = editor.getJSON().content[0].content;
    assert.equal(content[0].type, 'mention');
    assert.equal(content[0].attrs.id, 'content-id'); assert.equal(content[0].attrs.entityType, 'metadata');
    assert.equal(content[0].attrs.label, 'Launch'); assert.equal(content[0].attrs.mentionSuggestionChar, '@');
    assert.equal(content[1].text, ' suffix');
    assert.equal(documentTitle(editor.getJSON()), '@Launch suffix');
    assert.equal(editor.state.selection.empty, true);
    editor.commands.setContent('<p>@la</p>'); editor.commands.setTextSelection(4); await wait();
    assert.equal(root.querySelector('.bml-mention-menu').hidden, false);
    editor.view.dom.blur(); await tick();
    assert.equal(root.querySelector('.bml-mention-menu').hidden, true);
    editor.view.dom.focus(); await wait();
    assert.equal(root.querySelector('.bml-mention-menu').hidden, false, 'refocusing the unchanged query restores suggestions');
    editor.setEditable(false); await tick();
    assert.equal(root.querySelector('.bml-mention-menu').hidden, true);
  } finally { editor.destroy(); }
  assert.equal(root.querySelector('.bml-mention-menu'), null);
}));
