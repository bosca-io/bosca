import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export { Editor } from '@tiptap/core'; export { default as StarterKit } from '@tiptap/starter-kit';
  export { getSchema } from '@tiptap/core'; export { default as Collaboration } from '@tiptap/extension-collaboration';
  export { prosemirrorJSONToYDoc } from 'y-prosemirror';
  export { Doc, encodeStateAsUpdate, applyUpdate } from 'yjs';
  export { documentSelectionBookmark } from './src/main/client/helpers/document-selection.ts';
  export { documentExtensions } from './src/main/client/helpers/document-schema.ts';
  export * from './src/main/client/helpers/document-containers.ts'; export * from './src/main/client/helpers/document-blocks.ts';`, resolveDir: process.cwd() },
  bundle: true, write: false, format: 'esm', platform: 'browser' });
const { Editor, getSchema, StarterKit, Collaboration, prosemirrorJSONToYDoc, documentExtensions, documentContainerViews, wrapDocumentContainer, moveDocumentBlock, documentSelectionBookmark, Doc, encodeStateAsUpdate, applyUpdate } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const definition = { id: 'main', name: 'Main content', description: 'Main article body', type: 'STANDARD' };
const paragraph = text => ({ type: 'paragraph', content: [{ type: 'text', text }] });
const container = { type: 'container', attrs: { name: 'main', references: ['GEN.1'], metadataId: 'related', version: 4, renderer: 'card' }, content: [paragraph('Body')] };
async function fixture(content, check, shared = false) {
  const window = new Window({ url: 'http://containers.test/' });
  for (const key of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'MutationObserver', 'Event', 'MouseEvent', 'navigator', 'getComputedStyle']) Object.defineProperty(globalThis, key, { configurable: true, value: key === 'getComputedStyle' ? window[key].bind(window) : window[key] });
  globalThis.requestAnimationFrame = window.requestAnimationFrame.bind(window);
  globalThis.cancelAnimationFrame = window.cancelAnimationFrame.bind(window);
  const views = documentContainerViews('example', [definition]);
  const extensions = [StarterKit.configure({ document: false, history: shared ? false : undefined }), ...documentExtensions('block+', false)];
  const documentState = shared ? prosemirrorJSONToYDoc(getSchema(extensions), { type: 'doc', content }, 'default') : null;
  const editor = new Editor({ element: document.body, extensions: [...extensions, ...(documentState ? [Collaboration.configure({ document: documentState })] : [])],
    content: { type: 'doc', content }, editorProps: { nodeViews: { container: views.container } } });
  editor.setEditable(true, false);
  try { await check({ editor, views, documentState, tick: () => new Promise(resolve => setTimeout(resolve, 0)) }); }
  finally { editor.destroy(); documentState?.destroy(); await window.happyDOM.abort(); }
}

test('a delayed shared insertion stays before the original text when a peer replaces the paragraph content', () => fixture([paragraph('Peer notes.')], async ({ editor, documentState }) => {
  editor.commands.setTextSelection(1);
  let bookmark = documentSelectionBookmark(editor.view);
  editor.on('transaction', ({ transaction }) => { bookmark = bookmark.map(transaction.mapping); });
  const peer = new Doc(); applyUpdate(peer, encodeStateAsUpdate(documentState));
  try {
    peer.getXmlFragment('default').get(0).get(0).insert(0, 'Peer update ');
    applyUpdate(documentState, encodeStateAsUpdate(peer));
    assert.equal(editor.getText(), 'Peer update Peer notes.');
    const selection = bookmark.resolve(editor.state.doc);
    assert.equal(selection.$from.parentOffset, 'Peer update '.length);
    assert.equal(selection.$from.parent.textBetween(selection.$from.parentOffset, selection.$from.parent.content.size), 'Peer notes.');
  } finally { peer.destroy(); }
}, true));

test('a delayed ordinary insertion maps through local transactions', () => fixture([paragraph('Original')], async ({ editor }) => {
  editor.commands.setTextSelection(1);
  let bookmark = documentSelectionBookmark(editor.view);
  editor.on('transaction', ({ transaction }) => { bookmark = bookmark.map(transaction.mapping); });
  editor.commands.insertContentAt(1, 'Before ');
  assert.equal(bookmark.resolve(editor.state.doc).from, 8);
}));

for (const [name, prefix, offset] of [['distinct inserted text', 'Collaborator update ', 20], ['a repeated prefix retains the original CRDT character', 'Peer update ', 0]]) test(`a delayed insertion after a title follows another editor: ${name}`, () => fixture([
  { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Title' }] }, paragraph('Peer notes.'),
], async ({ editor, documentState }) => {
  const peer = new Doc(); applyUpdate(peer, encodeStateAsUpdate(documentState));
  const element = document.createElement('div'); document.body.append(element);
  const peerEditor = new Editor({ element, extensions: [StarterKit.configure({ document: false, history: false }), ...documentExtensions('block+', false), Collaboration.configure({ document: peer })] });
  editor.commands.setTextSelection(editor.state.doc.child(0).nodeSize + 1);
  let bookmark = documentSelectionBookmark(editor.view);
  editor.on('transaction', ({ transaction }) => { bookmark = bookmark.map(transaction.mapping); });
  try {
    peerEditor.commands.insertContentAt(peerEditor.state.doc.child(0).nodeSize + 1, prefix);
    applyUpdate(documentState, encodeStateAsUpdate(peer));
    const selection = bookmark.resolve(editor.state.doc);
    assert.equal(selection.$from.parentOffset, offset);
    assert.equal(selection.$from.parent.textBetween(selection.$from.parentOffset, selection.$from.parent.content.size), (prefix + 'Peer notes.').slice(offset));
  } finally { peerEditor.destroy(); peer.destroy(); element.remove(); }
}, true));

test('a delayed shared image replacement uses current attributes and stops when its peer deletes the target', () => fixture([
  paragraph('Before'),
  { type: 'image', attrs: { src: '/one.png', metadataId: 'one', class: 'original' } },
  { type: 'image', attrs: { src: '/two.png', metadataId: 'two' } }, paragraph('Notes'),
], async ({ editor, documentState }) => {
  editor.commands.setNodeSelection(editor.state.doc.child(0).nodeSize);
  let bookmark = documentSelectionBookmark(editor.view);
  editor.on('transaction', ({ transaction }) => { bookmark = bookmark.map(transaction.mapping); });
  const peer = new Doc(); applyUpdate(peer, encodeStateAsUpdate(documentState));
  try {
    peer.getXmlFragment('default').get(1).setAttribute('class', 'peer'); applyUpdate(documentState, encodeStateAsUpdate(peer));
    assert.equal(bookmark.resolve(editor.state.doc).node.attrs.class, 'peer');
    peer.getXmlFragment('default').delete(1, 1); applyUpdate(documentState, encodeStateAsUpdate(peer));
    assert.equal(bookmark.resolve(editor.state.doc), null);
    assert.equal(editor.getJSON().content[1].attrs.metadataId, 'two', 'The neighboring image survives the removed target');
  } finally { peer.destroy(); }
}, true));

test('a delayed ordinary image replacement stops when a local transaction deletes its target', () => fixture([
  paragraph('Before'), { type: 'image', attrs: { src: '/one.png', metadataId: 'one' } }, paragraph('Notes'),
], async ({ editor }) => {
  editor.commands.setNodeSelection(editor.state.doc.child(0).nodeSize);
  let bookmark = documentSelectionBookmark(editor.view);
  editor.on('transaction', ({ transaction }) => { bookmark = bookmark.map(transaction.mapping); });
  editor.commands.deleteSelection(); assert.equal(bookmark.resolve(editor.state.doc), null);
}));

test('immediate wrap and removal remain separate collaborative undo actions', () => fixture([paragraph('Body')], async ({ editor }) => {
  editor.commands.setTextSelection(2);
  assert.equal(wrapDocumentContainer(editor, 'main'), true);
  document.querySelector('[aria-label="Remove Main content container"]').click();
  assert.equal(editor.getJSON().content.some(node => node.type === 'container'), false);
  assert.equal(editor.commands.undo(), true);
  assert.equal(editor.getJSON().content[0].type, 'container');
  assert.equal(editor.getJSON().content[0].content[0].content[0].text, 'Body');
  assert.equal(editor.commands.undo(), true);
  assert.equal(editor.getJSON().content[0].type, 'paragraph');
  assert.equal(editor.getText(), 'Body');
}, true));

test('moving a shared region retains its selection and undo boundary', () => fixture([paragraph('Before'), container,
  { ...container, attrs: { ...container.attrs, name: 'notes' }, content: [paragraph('Notes')] }, paragraph('After')], async ({ editor }) => {
  const at = editor.state.doc.child(0).nodeSize + editor.state.doc.child(1).nodeSize;
  editor.commands.setTextSelection(at + 3);
  assert.equal(moveDocumentBlock(editor, -1), true);
  assert.deepEqual(editor.getJSON().content.filter(node => node.type === 'container').map(node => node.attrs.name), ['notes', 'main']);
  assert.equal(editor.commands.undo(), true);
  assert.deepEqual(editor.getJSON().content.filter(node => node.type === 'container').map(node => node.attrs.name), ['main', 'notes']);
}, true));

test('wrapping and unwrapping use native editor transactions with a trailing paragraph and undo', () => fixture([paragraph('Selected body')], async ({ editor }) => {
  editor.commands.setTextSelection({ from: 1, to: 14 });
  assert.equal(wrapDocumentContainer(editor, 'main'), true);
  assert.equal(editor.getJSON().content[0].type, 'container');
  assert.equal(editor.getJSON().content[0].attrs.name, 'main');
  assert.equal(editor.getJSON().content[0].content[0].content[0].text, 'Selected body');
  assert.equal(editor.getJSON().content[1].type, 'paragraph');
  assert.equal(editor.commands.undo(), true);
  assert.equal(editor.getJSON().content[0].type, 'paragraph');
  assert.equal(editor.commands.redo(), true);
  assert.equal(editor.commands.lift('container'), true);
  assert.equal(editor.getJSON().content[0].type, 'paragraph');
}));

test('collapsing is local presentation and does not rewrite opaque container attributes', () => fixture([container], async ({ editor, tick }) => {
  const initial = JSON.parse(JSON.stringify(editor.getJSON()));
  const caption = document.querySelector('.document-container-header strong');
  assert.equal(caption.textContent, 'Main content'); assert.equal(caption.title, 'Main article body');
  const collapse = document.querySelector('[aria-expanded]');
  collapse.click(); await tick();
  assert.equal(collapse.getAttribute('aria-expanded'), 'false');
  assert.equal(document.getElementById(collapse.getAttribute('aria-controls')).hidden, true);
  assert.deepEqual(JSON.parse(JSON.stringify(editor.getJSON())), initial);
  collapse.click(); await tick(); assert.equal(collapse.getAttribute('aria-expanded'), 'true');
}));

test('container headers forward drops to the document while retaining their own controls', () => fixture([container], async ({ editor }) => {
  let drops = 0;
  editor.view.setProps({ handleDOMEvents: { drop: () => { drops++; return true; } } });
  document.querySelector('.document-container-header').dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }));
  assert.equal(drops, 1);
  document.querySelector('[aria-expanded]').click();
  assert.equal(document.querySelector('[aria-expanded]').getAttribute('aria-expanded'), 'false');
}));

test('readonly preserves collapse while locking removal and later template updates refresh labels', () => fixture([container], async ({ editor, views }) => {
  views.setEditable(false);
  const remove = document.querySelector('button[aria-label="Remove Main content container"]');
  assert.equal(remove.disabled, true); assert.equal(remove.hidden, true);
  remove.click(); assert.equal(editor.getJSON().content[0].type, 'container');
  document.querySelector('[aria-expanded]').click(); assert.equal(document.querySelector('[aria-expanded]').getAttribute('aria-expanded'), 'false');
  views.setDefinitions([{ ...definition, name: 'Revised body' }]);
  assert.equal(document.querySelector('strong').textContent, 'Revised body');
  views.setEditable(true); remove.click();
  assert.equal(editor.getJSON().content.some(node => node.type === 'container'), false);
}));

test('missing definitions keep stored content usable and removed view controls release handlers', () => fixture([container], async ({ editor, views }) => {
  views.setDefinitions([]);
  assert.equal(document.querySelector('strong').textContent, 'Content');
  const remove = document.querySelector('button[aria-label="Remove Content container"]');
  editor.destroy();
  assert.doesNotThrow(() => remove.click());
}));

test('keyboard block movement preserves opaque attributes and cursor, supports undo and respects readonly edges', () => fixture([paragraph('Before'), container, paragraph('After')], async ({ editor }) => {
  const beforeSize=editor.state.doc.child(0).nodeSize;
  editor.commands.setTextSelection(beforeSize+3);
  const saved=JSON.parse(JSON.stringify(editor.getJSON().content[1]));
  assert.equal(moveDocumentBlock(editor,-1,false),true);
  assert.equal(moveDocumentBlock(editor,-1),true);
  assert.deepEqual(JSON.parse(JSON.stringify(editor.getJSON().content[0])),saved);
  assert.equal(editor.state.selection.from,3);
  assert.equal(moveDocumentBlock(editor,-1),false);
  assert.equal(editor.commands.undo(),true);
  assert.deepEqual(JSON.parse(JSON.stringify(editor.getJSON().content[1])),saved);
  assert.equal(moveDocumentBlock(editor,1),true);
  assert.deepEqual(JSON.parse(JSON.stringify(editor.getJSON().content[2])),saved);
  assert.equal(moveDocumentBlock(editor,1),false);
  editor.setEditable(false); assert.equal(moveDocumentBlock(editor,-1),false);
}));
