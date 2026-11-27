import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../bml-runtime/package.json', import.meta.url));
const { Window } = require('happy-dom'), { build } = require('esbuild');
const bundle = await build({ stdin: { contents: `export * from './src/main/client/helpers/document-references.ts'; export * from './src/main/client/helpers/scripture.ts';`, resolveDir: process.cwd() },
  bundle: true, write: false, format: 'esm', platform: 'browser' });
const { documentReferencePanel, scriptureContent, scripturePassage } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const delay = () => new Promise(resolve => setTimeout(resolve, 280));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

async function fixture(run) {
  const window = new Window({ url: 'https://references.test/document' });
  for (const key of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLButtonElement', 'CustomEvent', 'Event', 'KeyboardEvent', 'AbortController', 'Option', 'location']) {
    Object.defineProperty(globalThis, key, { configurable: true, value: window[key] });
  }
  Object.defineProperty(globalThis, 'Option', { configurable: true, value: function(text, value = '') { const option = document.createElement('option'); option.textContent = text; option.value = value; return option; } });
  try { await run(document.body); } finally { await window.happyDOM.abort(); }
}

test('scripture retains inline character semantics verse numbers styles and supplied attribution without HTML injection', () => fixture(() => {
  const components = { _: 'cc', type: 'PARAGRAPH', style: { _: 'sr', id: 'p', textIndent: { size: 0, unit: 'POINT' } }, components: [
    { _: 'vs', reference: { usfm: 'GEN.1.1', human: 'Genesis 1:1' } },
    { _: 'cc', type: 'DIV', style: { _: 'sr', id: 'wj' }, components: [{ _: 't', text: '<script>Text</script>' }] },
    { _: 'cc', type: 'DIV', style: { _: 'sr', id: 'it' }, components: [{ _: 't', text: ' italic' }] },
    { _: 'cc', type: 'DIV', style: { _: 'sr', id: 'f' }, components: [{ _: 't', text: 'hidden footnote' }] },
    { _: 'b' }, { _: 've' },
  ] };
  const rendered = scripturePassage({ label: 'Genesis 1', components,
    styles: [{ id: 'p', textIndent: { size: 20, unit: 'POINT' } }, { id: 'wj', fontWeight: 'bold', reference: true }],
    attribution: { copyright: 'Copyright supplied', citation: 'Citation supplied', informationUrl: 'https://example.test/bible' } });
  document.body.append(rendered);
  assert.equal(rendered.querySelector('script'), null);
  assert.equal(rendered.querySelector('sup').textContent, '1');
  assert.equal(rendered.getAttribute('aria-label'), 'Genesis 1');
  assert.equal(rendered.getAttribute('role'), 'group', 'repeated citations remain local passage groups');
  assert.equal(rendered.querySelector('h1,h2,h3,h4,h5,h6'), null, 'the citation label does not add a document heading');
  assert.equal(rendered.querySelector('.document-scripture-label').textContent, 'Genesis 1');
  assert.equal(rendered.querySelector('.document-scripture-content p').style.textIndent, '0pt');
  assert.equal(rendered.querySelector('.scripture-inline').tagName, 'SPAN');
  assert.equal(rendered.querySelector('.scripture-inline').style.fontWeight, 'bold');
  assert.equal(rendered.querySelector('span[style*="italic"]').tagName, 'SPAN');
  assert.equal(rendered.textContent.includes('hidden footnote'), false);
  assert.ok(rendered.querySelector('footer').textContent.includes('Citation supplied'));
  assert.equal(rendered.querySelector('a').href, 'https://example.test/bible');
  assert.throws(() => scriptureContent({ _: 'unknown' }, {}), /Unsupported scripture/);
  const combined = scriptureContent({ _: 'cc', type: 'DIV', style: { _: 'sr', id: 'it bd', fontFamily: null }, components: [{ _: 't', text: 'Combined styles' }] },
    { it: { fontFamily: 'Example Font', verticalAlign: 'TEXT_TOP' }, bd: { fontWeight: 'BOLD' } }).firstChild;
  assert.equal(combined.tagName, 'SPAN'); assert.equal(combined.style.fontFamily.replaceAll('"', ''), 'Example Font');
  assert.equal(combined.style.verticalAlign, 'text-top'); assert.equal(combined.style.fontWeight, 'bold');
}));

test('metadata lookup preserves opaque attributes and ignores stale results after a peer changes the reference', () => fixture(async root => {
  let attrs = { metadataId: 'one', version: 4, renderer: 'card', retained: 'opaque' };
  const first = deferred(), second = deferred(), requests = [];
  root.addEventListener('documentreferencerequest', event => {
    const request = event.detail; requests.push(request);
    if (request.kind === 'metadata') request.respondWith(request.id === 'one' ? first.promise : second.promise);
  });
  const panel = documentReferencePanel(root, { kind: 'METADATA', filters: ['kind = "article"'], current: () => attrs,
    change(patch) { attrs = { ...attrs, ...patch }; panel.update(true); } });
  root.append(panel.dom); panel.update(true);
  assert.equal(requests[0].version, 4);
  attrs = { ...attrs, metadataId: 'two' }; panel.update(true);
  assert.equal(requests[0].signal.aborted, true);
  second.resolve({ id: 'two', label: 'Second article', contentType: 'text/plain' }); await tick();
  first.resolve({ id: 'one', label: 'Obsolete title' }); await tick();
  assert.equal(root.textContent.includes('Obsolete title'), false);
  assert.ok(root.textContent.includes('Second article'));
  assert.ok(root.textContent.includes('card'));
  panel.update(false);
  assert.equal(root.querySelector('.document-reference-controls').hidden, true);
  const clear = [...root.querySelectorAll('button')].find(item => item.textContent === 'Clear metadata reference');
  assert.equal(clear.disabled, true); clear.click(); assert.equal(attrs.metadataId, 'two');
  panel.update(true); clear.click();
  assert.deepEqual(attrs, { metadataId: null, version: 4, renderer: null, retained: 'opaque' });
  panel.destroy();
}));

test('failed and private metadata lookups retain their stored identities with explicit retry and clear actions', () => fixture(async root => {
  let attrs = { metadataId: 'private', renderer: 'stored' }, requests = 0;
  root.addEventListener('documentreferencerequest', event => {
    requests++; event.detail.respondWith(requests === 1 ? Promise.reject(new Error('offline')) : null);
  });
  const panel = documentReferencePanel(root, { kind: 'METADATA', filters: [], current: () => attrs, change(patch) { attrs = { ...attrs, ...patch }; } });
  root.append(panel.dom); panel.update(true); await tick();
  assert.ok(root.textContent.includes('Could not load the reference'));
  [...root.querySelectorAll('button')].find(item => item.textContent === 'Reload reference').click(); await tick();
  assert.ok(root.textContent.includes('unavailable or you do not have access'));
  assert.equal(attrs.metadataId, 'private');
  [...root.querySelectorAll('button')].find(item => item.textContent === 'Clear metadata reference').click();
  assert.equal(attrs.metadataId, null); assert.equal(attrs.renderer, null); panel.destroy();
}));

test('a late application adapter reloads mounted references without changing the stored document', () => fixture(async root => {
  const attrs = { metadataId: 'article', version: 3, renderer: 'card' };
  const panel = documentReferencePanel(root, { kind: 'METADATA', filters: [], current: () => attrs, change() { assert.fail('Presentation must not change document attributes'); } });
  root.append(panel.dom); panel.update(true); await tick();
  assert.ok(root.textContent.includes('Could not load the reference'));
  root.addEventListener('documentreferencerequest', event => event.detail.respondWith({ id: 'article', label: 'Loaded after adapter mount' }));
  panel.reload(); await tick();
  assert.ok(root.textContent.includes('Loaded after adapter mount'));
  assert.deepEqual(attrs, { metadataId: 'article', version: 3, renderer: 'card' }); panel.destroy();
}));

test('readonly scripture loads selected passages without fetching the hidden Bible catalog', () => fixture(async root => {
  const requests = [];
  root.addEventListener('documentreferencerequest', event => {
    requests.push(event.detail.kind);
    if (event.detail.kind === 'scripture') event.detail.respondWith([{ label: 'A passage', components: { _: 't', text: 'Readonly text' }, styles: [] }]);
    else event.detail.respondWith([]);
  });
  const panel = documentReferencePanel(root, { kind: 'BIBLE', filters: [], current: () => ({ metadataId: 'bible', references: ['GEN.1'] }), change() { assert.fail('Readonly'); } });
  root.append(panel.dom); panel.update(false); panel.start(); await tick();
  assert.deepEqual(requests, ['scripture']); assert.ok(root.textContent.includes('Readonly text')); panel.destroy();
}));

test('metadata search forwards template filters suppresses obsolete queries and cancels pending work on disposal', () => fixture(async root => {
  const first = deferred(), second = deferred(), requests = [];
  let attrs = {};
  root.addEventListener('documentreferencerequest', event => {
    const request = event.detail; requests.push(request);
    if (request.kind === 'metadata-search') request.respondWith(request.query === 'first' ? first.promise : second.promise);
  });
  const panel = documentReferencePanel(root, { kind: 'METADATA', filters: ['kind = "article"'], current: () => attrs, change(patch) { attrs = { ...attrs, ...patch }; } });
  root.append(panel.dom); panel.update(true);
  const input = root.querySelector('input');
  const composing = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, cancelable: true });
  input.dispatchEvent(composing); assert.equal(composing.defaultPrevented, false, 'Reference lookup does not consume IME confirmation');
  input.value = 'first'; input.dispatchEvent(new Event('input')); await delay();
  input.value = 'second'; input.dispatchEvent(new Event('input')); await delay();
  assert.equal(requests[0].signal.aborted, true);
  assert.deepEqual(requests[1].filters, ['kind = "article"']);
  second.resolve([{ id: 'two', label: 'Current title' }]); await tick();
  first.resolve([{ id: 'one', label: 'Old title' }]); await tick();
  const select = root.querySelector('select'); assert.equal(select.options[1].textContent, 'Current title');
  select.value = 'two'; select.dispatchEvent(new Event('change')); assert.equal(attrs.metadataId, 'two');
  input.value = 'third'; input.dispatchEvent(new Event('input')); panel.destroy(); await delay();
  assert.equal(requests.length, 2);
}));

test('scripture selections append once retain ordering and remove through the original Studio representation', () => fixture(async root => {
  let attrs = { usfm: ['GEN.1.1'], metadataIds: ['bible'], metadataId: 'legacy', references: ['retained'], renderer: 'opaque' };
  const requests = [];
  root.addEventListener('documentreferencerequest', event => {
    const request = event.detail; requests.push(request);
    if (request.kind === 'bibles') request.respondWith([{ id: 'bible', label: 'Example Bible' }]);
    if (request.kind === 'scripture-search') request.respondWith([{ id: 'GEN.1.2', label: 'Genesis 1:2' }]);
    if (request.kind === 'scripture') request.respondWith([{ label: request.reference, components: { _: 't', text: 'Passage text' }, styles: [] }]);
  });
  const panel = documentReferencePanel(root, { kind: 'BIBLE', standalone: true, filters: [], current: () => attrs,
    change(patch) { attrs = { ...attrs, ...patch }; panel.update(true); } });
  root.append(panel.dom); panel.update(true); panel.start(); await tick();
  const input = root.querySelector('input'), results = root.querySelectorAll('select')[1];
  for (let attempt = 0; attempt < 2; attempt++) {
    input.value = 'Genesis 1:2'; input.dispatchEvent(new Event('input')); await delay();
    results.value = 'GEN.1.2'; results.dispatchEvent(new Event('change')); await tick();
  }
  assert.deepEqual(attrs.usfm, ['GEN.1.1', 'GEN.1.2']);
  assert.deepEqual(attrs.references, ['retained']); assert.equal(attrs.metadataId, 'legacy'); assert.equal(attrs.renderer, 'opaque');
  root.querySelector('[aria-label="Remove GEN.1.1 reference"]').click(); await tick();
  assert.deepEqual(attrs.usfm, ['GEN.1.2']);
  panel.update(false); assert.equal(root.querySelector('[aria-label="Remove GEN.1.2 reference"]').hidden, true);
  assert.ok(root.textContent.includes('Passage text')); panel.destroy();
  assert.ok(requests.every(request => request.signal.aborted));
}));
