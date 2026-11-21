import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { build } = require('esbuild');
const bundle = await build({stdin:{contents:`export * as Y from 'yjs';export {documentRevisions} from './src/main/client/helpers/document-revisions.ts';`,resolveDir:process.cwd()},bundle:true,write:false,format:'esm',platform:'browser'});
const {Y,documentRevisions} = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);

test('initialization and schema normalization preserve the dirty flag supplied by Content', () => {
  for (const original of [true,false]) {
    const doc=new Y.Doc(), state=documentRevisions(doc);
    try {
      doc.getText('changes').setAttribute('changes', original?'true':'false');
      doc.getText('body').insert(0,'Initial content');
      doc.getMap('attrs').set('title','Normalized title');
      assert.equal(state.capture(),'{}');
      state.markReady();state.markReady();
      assert.equal(state.dirty,original);
      assert.equal(state.capture(),'{}');
    } finally {state.destroy();doc.destroy();}
  }
});

test('save completion acknowledges its captured revision and retains edits made while saving', () => {
  const doc=new Y.Doc(),state=documentRevisions(doc);state.markReady();
  try {
    doc.getText('body').insert(0,'First edit');
    assert.equal(state.dirty,true);
    const captured=state.capture();
    doc.getText('body').insert(0,'Later edit ');
    state.markSaved(captured);
    assert.equal(state.dirty,true);
    state.markSaved(state.capture());
    assert.equal(state.dirty,false);
    assert.throws(()=>state.markSaved('{"client":-1}'),/Invalid/);
    assert.throws(()=>state.markSaved('[]'),/Invalid/);
  } finally {state.destroy();doc.destroy();}
});

test('coalesced remote edits and saves stay clean only when the saved revision includes the edit', () => {
  const author=new Y.Doc(),receiver=new Y.Doc(),a=documentRevisions(author),b=documentRevisions(receiver);
  a.markReady();b.markReady();
  try {
    author.getText('body').insert(0,'Saved remotely');a.markSaved(a.capture());
    Y.applyUpdate(receiver,Y.encodeStateAsUpdate(author));
    assert.equal(b.dirty,false);
    const captured=a.capture();author.getText('body').insert(0,'Newer edit ');a.markSaved(captured);
    Y.applyUpdate(receiver,Y.encodeStateAsUpdate(author));
    assert.equal(b.dirty,true);
    a.markSaved(a.capture());Y.applyUpdate(receiver,Y.encodeStateAsUpdate(author));
    assert.equal(b.dirty,false);
  } finally {a.destroy();b.destroy();author.destroy();receiver.destroy();}
});

test('remote acknowledgements cannot clear a local edit that arrived during save', () => {
  const author=new Y.Doc(),receiver=new Y.Doc(),a=documentRevisions(author),b=documentRevisions(receiver);
  a.markReady();b.markReady();
  try {
    author.getText('body').insert(0,'Remote');Y.applyUpdate(receiver,Y.encodeStateAsUpdate(author));
    const captured=a.capture();receiver.getText('body').insert(0,'Local ');a.markSaved(captured);
    Y.applyUpdate(receiver,Y.encodeStateAsUpdate(author));
    assert.equal(b.dirty,true);
    assert.equal(receiver.getText('body').toString(),'Local Remote');
    receiver.getText('changes').setAttribute('changes','false');
    assert.equal(b.dirty,true,'A legacy clean marker does not prove that this local edit was saved');
  } finally {a.destroy();b.destroy();author.destroy();receiver.destroy();}
});

test('reseed markers and bookkeeping do not count as edits and unmount removes tracking', () => {
  const doc=new Y.Doc();let updates=0;const state=documentRevisions(doc,()=>updates++);state.markReady();
  try {
    for (const name of ['attrs','collections','metadatas']) doc.getMap(name).set('|__dirty__|',true);
    assert.equal(state.dirty,false);assert.equal(state.capture(),'{}');
    doc.getMap('attrs').set('title','User edit');assert.equal(state.dirty,true);
    state.markSaved(state.capture());assert.equal(state.dirty,false);
    const before=updates,captured=state.capture();state.destroy();
    doc.getText('body').insert(0,'After unmount');
    assert.equal(state.capture(),captured);assert.equal(updates,before);
  } finally {state.destroy();doc.destroy();}
});
