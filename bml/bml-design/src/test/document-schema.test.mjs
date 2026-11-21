import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom');
const { build } = require('esbuild');
const window = new Window({ url: 'http://documents.test/' });
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLButtonElement', 'Event', 'CustomEvent', 'MutationObserver', 'DOMParser', 'navigator', 'getComputedStyle']) Object.defineProperty(globalThis, name, {
  configurable: true, value: name === 'getComputedStyle' ? window[name].bind(window) : window[name],
});
globalThis.requestAnimationFrame = window.requestAnimationFrame.bind(window);
globalThis.cancelAnimationFrame = window.cancelAnimationFrame.bind(window);
const bundle = await build({ stdin: { contents: `export { Editor, getSchema } from '@tiptap/core';
  export { default as StarterKit } from '@tiptap/starter-kit';
  export * from './src/main/client/helpers/document-schema.ts';`, resolveDir: process.cwd() },
  bundle: true, write: false, format: 'esm', platform: 'browser' });
const { Editor, getSchema, StarterKit, documentExtensions, readDocument, sanitizeDocument, documentTitle } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const extensions = (expression = 'block+', singleTitle = false) => [StarterKit.configure({ document: false }), ...documentExtensions(expression, singleTitle)];
const text = value => ({ type: 'text', text: value });
const heading = value => ({ type: 'heading', attrs: { level: 1 }, content: value ? [text(value)] : [] });
const paragraph = value => ({ type: 'paragraph', content: [text(value)] });
const stored = { type: 'doc', content: [heading('Stored title'),
  { type: 'container', attrs: { name: 'MAIN_CONTENT', references: ['GEN.1'], metadataId: 'related-metadata', version: 4, renderer: 'card' },
    content: [{ type: 'paragraph', content: [text('Body '), { type: 'mention', attrs: { id: 'profile-id', entityType: 'profile', label: 'Author' } }] }] },
  { type: 'bible', attrs: { usfm: ['GEN.1.1'], metadataIds: ['bible-metadata'] } },
  { type: 'image', attrs: { src: '/image.png', alt: 'Example', title: 'Image', metadataId: 'image-metadata' } },
  { type: 'codeBlock', attrs: { language: 'mermaid' }, content: [text('graph TD; A-->B;')] },
] };

test('Content root classes accept the stored serializer discriminator without treating it as an editor attribute', () => {
  const value = { ...stored, attrs: { type: 'empty', class: 'article' } };
  const normalized = readDocument(JSON.stringify(value), getSchema(extensions()));
  assert.deepEqual(normalized.attrs, { class: 'article' });
  const editor = new Editor({ extensions: extensions(), content: normalized });
  try { assert.equal(editor.getJSON().attrs.class, 'article'); }
  finally { editor.destroy(); }
});

test('unresolved mentions retain their identity and display a neutral label', () => {
  const editor = new Editor({ extensions: extensions(), content: { type: 'doc', content: [{ type: 'paragraph',
    content: [{ type: 'mention', attrs: { id: 'stored-identity', entityType: 'metadata' } }] }] } });
  try {
    assert.equal(editor.getText(), '@Mention');
    assert.equal(editor.getJSON().content[0].content[0].attrs.id, 'stored-identity');
  } finally { editor.destroy(); }
});

test('stored content preserves custom nodes, identities, attributes and code language through JSON and HTML', () => {
  const editor = new Editor({ extensions: extensions('heading block+'), content: readDocument(JSON.stringify(stored), getSchema(extensions('heading block+'))) });
  try {
    const json = JSON.parse(JSON.stringify(editor.getJSON()));
    const container = json.content.find(node => node.type === 'container');
    const supplied = (actual, expected) => Object.fromEntries(Object.keys(expected).map(name => [name, actual[name]]));
    assert.deepEqual(supplied(container.attrs, stored.content[1].attrs), stored.content[1].attrs);
    assert.deepEqual(supplied(container.content[0].content[1].attrs, stored.content[1].content[0].content[1].attrs), stored.content[1].content[0].content[1].attrs);
    assert.deepEqual(supplied(json.content.find(node => node.type === 'bible').attrs, stored.content[2].attrs), stored.content[2].attrs);
    assert.equal(json.content.find(node => node.type === 'image').attrs.metadataId, 'image-metadata');
    assert.equal(json.content.find(node => node.type === 'codeBlock').attrs.language, 'mermaid');
    const html = editor.getHTML();
    editor.commands.setContent(html);
    assert.deepEqual(JSON.parse(JSON.stringify(editor.getJSON())), json);
    assert.equal(documentTitle(json), 'Stored title');
  } finally { editor.destroy(); }
});

test('Content-native classes and reference attributes remain in the JSON body', () => {
  const value = { type: 'doc', attrs: { class: 'article' }, content: [
    { type: 'bible', attrs: { class: 'references', metadataId: 'content-bible', references: ['GEN.1'] } },
    { type: 'paragraph', attrs: { class: 'lead' }, content: [{ type: 'mention', attrs: { id: 'content-id', label: 'Article', entityType: 'metadata', mentionSuggestionChar: '@', class: 'reference' } }] },
  ] };
  const schema = getSchema(extensions());
  const json = schema.nodeFromJSON(readDocument(JSON.stringify(value), schema)).toJSON();
  assert.equal(json.attrs.class, 'article');
  assert.equal(json.content[0].attrs.metadataId, 'content-bible');
  assert.deepEqual(json.content[0].attrs.references, ['GEN.1']);
  assert.equal(json.content[1].attrs.class, 'lead');
  assert.equal(json.content[1].content[0].attrs.mentionSuggestionChar, '@');
});

test('normalization removes empty text and malformed references without mutating stored values', () => {
  const source = { type: 'doc', content: [{ type: 'container', attrs: { name: 'references', references: ['GEN.1', null, 4, '', 'EXO.2'], version: 5 },
    content: [{ type: 'paragraph', content: [text(''), text('Kept')] }] }] };
  const before = JSON.stringify(source);
  const normalized = sanitizeDocument(source);
  assert.equal(JSON.stringify(source), before);
  assert.deepEqual(normalized.content[0].attrs, { name: 'references', references: ['GEN.1', 'EXO.2'], version: 5 });
  assert.deepEqual(normalized.content[0].content[0].content, [text('Kept')]);
});

test('unknown data and invalid template structure fail before parsing can discard them', () => {
  const schema = getSchema(extensions('heading block+'));
  const invalid = [
    { type: 'doc', content: [{ type: 'unknownBlock', attrs: { keep: true } }] },
    { type: 'doc', content: [heading('Title'), { type: 'container', attrs: { name: 'Main', unknown: 'Keep' } }] },
    { type: 'doc', content: [heading('Title'), { type: 'paragraph', content: [{ type: 'text', text: 'Keep', marks: [{ type: 'unknownMark' }] }] }] },
    { type: 'doc', content: [heading('Title'), { type: 'paragraph', content: [{ type: 'text', text: 'Keep', marks: [{ type: 'bold', attrs: { unknown: true } }] }] }] },
    { type: 'doc', content: [paragraph('No title')] },
  ];
  for (const value of invalid) {
    const original = JSON.stringify(value);
    assert.throws(() => readDocument(original, schema));
    assert.equal(JSON.stringify(value), original);
  }
});

test('untrusted JSON attributes cannot reach TipTap attribute merging through prototype keys', () => {
  const value = '{"type":"doc","content":[{"type":"image","attrs":{"src":"/example.png","__proto__":{"onerror":"window.executed=true"}}}]}';
  assert.throws(() => readDocument(value, getSchema(extensions())), /Unsupported image attribute: __proto__/);
});

test('title normalization completes before observing edits and the first heading owns the title', () => {
  const editor = new Editor({ extensions: extensions('heading block+', true), content: { type: 'doc', content: [heading('First'), heading('Second'), paragraph('Body')] } });
  try {
    editor.view.dispatch(editor.state.tr);
    assert.equal(documentTitle(editor.getJSON()), 'First');
    assert.deepEqual(editor.getJSON().content.filter(node => node.type === 'heading').map(node => node.attrs.level), [1, 2]);
    const updates = [];
    editor.on('update', () => updates.push(editor.getJSON()));
    assert.equal(updates.length, 0);
    editor.commands.setContent({ type: 'doc', content: [heading(''), heading('Later'), paragraph('Body')] }, true);
    assert.equal(documentTitle(editor.getJSON()), '');
    assert.equal(updates.length, 1);
  } finally { editor.destroy(); }
});

test.after(async () => { await window.happyDOM.abort(); });
