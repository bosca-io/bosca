/**
 * Comprehensive fixture generator for bidirectional yjs ↔ yks wire compatibility testing.
 *
 * Each fixture records binary data (hex) produced by yjs along with expected state.
 * yjs self-verifies every fixture by round-tripping (encode → decode → re-encode).
 *
 * Usage: node generate.mjs > fixtures.json
 */
import * as Y from './node_modules/yjs/dist/yjs.mjs'
import * as encoding from './node_modules/lib0/encoding.js'
import * as decoding from './node_modules/lib0/decoding.js'

const fixtures = {
  metadata: {
    yjsVersion: '13.6.30',
    generatedAt: new Date().toISOString(),
    formatVersion: 2
  },
  single_doc_v1: {},
  single_doc_v2: {},
  delete_ops_v1: {},
  delete_ops_v2: {},
  sync_v1: {},
  sync_v2: {},
  gc_origin: {},
  snapshots: {},
  relative_positions: {},
  state_vectors: {}
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function toHex(uint8arr) {
  return Array.from(uint8arr).map(b => b.toString(16).padStart(2, '0')).join('')
}

/** Sort object keys recursively for deterministic JSON output. */
function sortKeys(obj) {
  if (obj === null || typeof obj !== 'object') return obj
  if (Array.isArray(obj)) return obj.map(sortKeys)
  const sorted = {}
  for (const k of Object.keys(obj).sort()) sorted[k] = sortKeys(obj[k])
  return sorted
}

function assert(condition, msg) {
  if (!condition) throw new Error(`Assertion failed: ${msg}`)
}

function deepEqual(a, b) {
  if (a === b) return true
  if (a == null || b == null) return a === b
  if (typeof a !== typeof b) return false
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false
    return a.every((v, i) => deepEqual(v, b[i]))
  }
  if (typeof a === 'object') {
    const keysA = Object.keys(a).sort()
    const keysB = Object.keys(b).sort()
    if (keysA.length !== keysB.length) return false
    return keysA.every((k, i) => k === keysB[i] && deepEqual(a[k], b[k]))
  }
  return false
}

/**
 * Single-doc fixture: create doc, run ops, encode V1+V2, self-verify round-trip.
 */
function singleDocFixture(name, clientID, setup, expected) {
  const doc = new Y.Doc(); doc.clientID = clientID
  setup(doc)
  const update = Y.encodeStateAsUpdate(doc)
  const sv = Y.encodeStateVector(doc)

  // Round-trip self-verification (V1)
  const doc2 = new Y.Doc()
  Y.applyUpdate(doc2, update)
  const reencoded = Y.encodeStateAsUpdate(doc2)
  const roundTrip = toHex(update) === toHex(reencoded)
  assert(roundTrip, `V1 round-trip failed for ${name}`)

  fixtures.single_doc_v1[name] = {
    clientID,
    update: toHex(update),
    stateVector: toHex(sv),
    expected,
    roundTrip
  }

  // V2 variant
  const updateV2 = Y.encodeStateAsUpdateV2(doc)
  const doc3 = new Y.Doc()
  Y.applyUpdateV2(doc3, updateV2)
  const reencodedV2 = Y.encodeStateAsUpdateV2(doc3)
  const roundTripV2 = toHex(updateV2) === toHex(reencodedV2)
  assert(roundTripV2, `V2 round-trip failed for ${name}`)

  fixtures.single_doc_v2[name] = {
    clientID,
    updateV2: toHex(updateV2),
    stateVector: toHex(sv),
    expected,
    roundTrip: roundTripV2
  }
}

/**
 * Delete-operation fixture (same structure, separate category for clarity).
 */
function deleteFixture(name, clientID, setup, expected) {
  const doc = new Y.Doc(); doc.clientID = clientID
  setup(doc)
  const update = Y.encodeStateAsUpdate(doc)
  const sv = Y.encodeStateVector(doc)

  const doc2 = new Y.Doc()
  Y.applyUpdate(doc2, update)
  const reencoded = Y.encodeStateAsUpdate(doc2)
  assert(toHex(update) === toHex(reencoded), `V1 round-trip failed for ${name}`)

  fixtures.delete_ops_v1[name] = {
    clientID,
    update: toHex(update),
    stateVector: toHex(sv),
    expected
  }

  const updateV2 = Y.encodeStateAsUpdateV2(doc)
  const doc3 = new Y.Doc()
  Y.applyUpdateV2(doc3, updateV2)
  const reencodedV2 = Y.encodeStateAsUpdateV2(doc3)
  assert(toHex(updateV2) === toHex(reencodedV2), `V2 round-trip failed for ${name}`)

  fixtures.delete_ops_v2[name] = {
    clientID,
    updateV2: toHex(updateV2),
    stateVector: toHex(sv),
    expected
  }
}

/**
 * Two-client sync fixture: records ALL intermediate bytes for full protocol verification.
 */
function syncFixture(name, clientIDA, clientIDB, setupA, setupB) {
  const doc1 = new Y.Doc(); doc1.clientID = clientIDA
  const doc2 = new Y.Doc(); doc2.clientID = clientIDB
  setupA(doc1)
  setupB(doc2)

  const sv1Before = Y.encodeStateVector(doc1)
  const sv2Before = Y.encodeStateVector(doc2)
  const u1to2 = Y.encodeStateAsUpdate(doc1, sv2Before)
  const u2to1 = Y.encodeStateAsUpdate(doc2, sv1Before)
  Y.applyUpdate(doc2, u1to2)
  Y.applyUpdate(doc1, u2to1)

  // Self-verify convergence
  const state1 = sortKeys(doc1.toJSON())
  const state2 = sortKeys(doc2.toJSON())
  assert(deepEqual(state1, state2), `Sync convergence failed for ${name}`)

  const mergedUpdate = Y.encodeStateAsUpdate(doc1)
  const mergedSv = Y.encodeStateVector(doc1)

  fixtures.sync_v1[name] = {
    clientA: clientIDA,
    clientB: clientIDB,
    sv1Before: toHex(sv1Before),
    sv2Before: toHex(sv2Before),
    update1to2: toHex(u1to2),
    update2to1: toHex(u2to1),
    mergedUpdate: toHex(mergedUpdate),
    mergedStateVector: toHex(mergedSv),
    expected: state1
  }

  // V2 sync variant
  const doc3 = new Y.Doc(); doc3.clientID = clientIDA
  const doc4 = new Y.Doc(); doc4.clientID = clientIDB
  setupA(doc3)
  setupB(doc4)

  const sv3 = Y.encodeStateVector(doc3)
  const sv4 = Y.encodeStateVector(doc4)
  const u3to4 = Y.encodeStateAsUpdateV2(doc3, sv4)
  const u4to3 = Y.encodeStateAsUpdateV2(doc4, sv3)
  Y.applyUpdateV2(doc4, u3to4)
  Y.applyUpdateV2(doc3, u4to3)

  const mergedV2 = Y.encodeStateAsUpdateV2(doc3)
  const mergedSvV2 = Y.encodeStateVector(doc3)

  fixtures.sync_v2[name] = {
    clientA: clientIDA,
    clientB: clientIDB,
    sv1Before: toHex(sv3),
    sv2Before: toHex(sv4),
    update1to2V2: toHex(u3to4),
    update2to1V2: toHex(u4to3),
    mergedUpdateV2: toHex(mergedV2),
    mergedStateVector: toHex(mergedSvV2),
    expected: sortKeys(doc3.toJSON())
  }
}

/**
 * Snapshot fixture: capture snapshot before mutations, verify restoration.
 */
function snapshotFixture(name, clientID, setupBefore, mutateAfter) {
  const doc = new Y.Doc({ gc: false }); doc.clientID = clientID
  setupBefore(doc)

  const snap = Y.snapshot(doc)
  const snapBytes = Y.encodeSnapshot(snap)
  const snapV2Bytes = Y.encodeSnapshotV2(snap)
  const beforeState = sortKeys(doc.toJSON())
  const fullUpdateBefore = Y.encodeStateAsUpdate(doc)

  mutateAfter(doc)
  const fullUpdateAfter = Y.encodeStateAsUpdate(doc)

  // Restore from snapshot and verify.
  // createDocFromSnapshot returns a doc where types must be accessed by name
  // (toJSON() only shows types that have been accessed via getArray/getMap/getText).
  // Access the same types as the original doc to populate the share map.
  const restored = Y.createDocFromSnapshot(doc, snap)
  for (const [key, type] of doc.share.entries()) {
    if (type instanceof Y.Array) restored.getArray(key)
    else if (type instanceof Y.Map) restored.getMap(key)
    else if (type instanceof Y.Text) restored.getText(key)
    else if (type instanceof Y.XmlFragment) restored.getXmlFragment(key)
  }
  const restoredState = sortKeys(restored.toJSON())
  assert(deepEqual(beforeState, restoredState), `Snapshot restore failed for ${name}: ${JSON.stringify(beforeState)} !== ${JSON.stringify(restoredState)}`)

  fixtures.snapshots[name] = {
    clientID,
    fullUpdateBefore: toHex(fullUpdateBefore),
    fullUpdateAfter: toHex(fullUpdateAfter),
    snapshotV1: toHex(snapBytes),
    snapshotV2: toHex(snapV2Bytes),
    restoredExpected: restoredState,
    stateVectorAtSnapshot: Object.fromEntries(
      Array.from(Y.decodeStateVector(Y.encodeStateVector(doc)).entries())
        .map(([k, v]) => [String(k), v])
    )
  }
}

/**
 * Relative position fixture: create rpos, mutate doc, verify resolution.
 */
function relPosFixture(name, clientID, setup, getTypeAndIndex, mutate) {
  const doc = new Y.Doc(); doc.clientID = clientID
  setup(doc)

  const fullUpdateBefore = Y.encodeStateAsUpdate(doc)
  const { type, index, assoc } = getTypeAndIndex(doc)
  const rpos = Y.createRelativePositionFromTypeIndex(type, index, assoc || 0)
  const encoded = Y.encodeRelativePosition(rpos)

  // Verify resolution before mutation
  const absBefore = Y.createAbsolutePositionFromRelativePosition(rpos, doc)
  assert(absBefore !== null, `RelPos resolution failed before mutation for ${name}`)
  assert(absBefore.index === index, `RelPos index mismatch before mutation for ${name}`)

  mutate(doc)
  const fullUpdateAfter = Y.encodeStateAsUpdate(doc)

  const absAfter = Y.createAbsolutePositionFromRelativePosition(rpos, doc)

  fixtures.relative_positions[name] = {
    clientID,
    fullUpdateBefore: toHex(fullUpdateBefore),
    fullUpdateAfter: toHex(fullUpdateAfter),
    encodedRpos: toHex(encoded),
    indexBefore: index,
    assoc: assoc || 0,
    resolvedIndexAfter: absAfter ? absAfter.index : null
  }
}

// ─── Category 1: Single-doc encoding ──────────────────────────────────────────

// Existing scenarios (keep clientIDs stable)
singleDocFixture('array_ints', 1, doc => {
  doc.getArray('arr').push([1, 2, 3])
}, { arr: [1, 2, 3] })

singleDocFixture('map_strings', 2, doc => {
  const m = doc.getMap('map')
  m.set('key1', 'value1')
  m.set('key2', 'value2')
}, { map: { key1: 'value1', key2: 'value2' } })

singleDocFixture('text_simple', 3, doc => {
  doc.getText('text').insert(0, 'Hello World')
}, { text: 'Hello World' })

singleDocFixture('array_mixed_types', 4, doc => {
  doc.getArray('arr').push([42, 'hello', true, false, null])
}, { arr: [42, 'hello', true, false, null] })

singleDocFixture('nested_map_array', 9, doc => {
  const root = doc.getMap('root')
  const inner = new Y.Array()
  root.set('list', inner)
  inner.push([1, 2, 3])
}, { root: { list: [1, 2, 3] } })

singleDocFixture('empty_doc', 10, doc => {
  // no ops
}, {})

// New scenarios
singleDocFixture('array_large', 20, doc => {
  doc.getArray('arr').push(Array.from({ length: 100 }, (_, i) => i))
}, { arr: Array.from({ length: 100 }, (_, i) => i) })

singleDocFixture('map_large', 21, doc => {
  const m = doc.getMap('map')
  for (let i = 0; i < 50; i++) m.set(`k${String(i).padStart(2, '0')}`, `v${i}`)
}, { map: Object.fromEntries(Array.from({ length: 50 }, (_, i) => [`k${String(i).padStart(2, '0')}`, `v${i}`])) })

singleDocFixture('text_long', 22, doc => {
  doc.getText('text').insert(0, 'A'.repeat(1000))
}, { text: 'A'.repeat(1000) })

singleDocFixture('text_unicode_emoji', 23, doc => {
  doc.getText('text').insert(0, 'Hello 🌍🎉 World')
}, { text: 'Hello 🌍🎉 World' })

singleDocFixture('text_unicode_cjk', 24, doc => {
  doc.getText('text').insert(0, '你好世界こんにちは')
}, { text: '你好世界こんにちは' })

singleDocFixture('text_unicode_arabic', 25, doc => {
  doc.getText('text').insert(0, 'مرحبا بالعالم')
}, { text: 'مرحبا بالعالم' })

singleDocFixture('nested_3_levels', 26, doc => {
  const root = doc.getMap('root')
  const mid = new Y.Map()
  root.set('mid', mid)
  const inner = new Y.Array()
  mid.set('inner', inner)
  inner.push([1, 2, 3])
}, { root: { mid: { inner: [1, 2, 3] } } })

singleDocFixture('array_floats', 27, doc => {
  doc.getArray('arr').push([1.5, -2.7, 0.0, 3.14159, -0.001])
}, { arr: [1.5, -2.7, 0.0, 3.14159, -0.001] })

singleDocFixture('array_large_ints', 28, doc => {
  doc.getArray('arr').push([0, -1, 2147483647, -2147483648, 1000000])
}, { arr: [0, -1, 2147483647, -2147483648, 1000000] })

singleDocFixture('multi_types_same_doc', 30, doc => {
  doc.getArray('arr').push([1, 2])
  doc.getMap('map').set('key', 'val')
  doc.getText('text').insert(0, 'hello')
}, { arr: [1, 2], map: { key: 'val' }, text: 'hello' })

singleDocFixture('map_nested_types', 33, doc => {
  const m = doc.getMap('map')
  const innerArr = new Y.Array()
  m.set('list', innerArr)
  innerArr.push(['a', 'b'])
  const innerMap = new Y.Map()
  m.set('nested', innerMap)
  innerMap.set('x', 42)
}, { map: { list: ['a', 'b'], nested: { x: 42 } } })

singleDocFixture('array_single_item', 34, doc => {
  doc.getArray('arr').push([42])
}, { arr: [42] })

singleDocFixture('map_single_entry', 35, doc => {
  doc.getMap('map').set('only', 'entry')
}, { map: { only: 'entry' } })

singleDocFixture('text_single_char', 36, doc => {
  doc.getText('text').insert(0, 'X')
}, { text: 'X' })

singleDocFixture('array_nested_arrays', 37, doc => {
  const outer = doc.getArray('arr')
  const inner1 = new Y.Array()
  const inner2 = new Y.Array()
  outer.push([inner1, inner2])
  inner1.push([1, 2])
  inner2.push([3, 4])
}, { arr: [[1, 2], [3, 4]] })

singleDocFixture('map_boolean_null_values', 38, doc => {
  const m = doc.getMap('map')
  m.set('t', true)
  m.set('f', false)
  m.set('n', null)
  m.set('zero', 0)
  m.set('empty', '')
}, { map: { t: true, f: false, n: null, zero: 0, empty: '' } })

singleDocFixture('text_multiline', 39, doc => {
  doc.getText('text').insert(0, 'line1\nline2\nline3')
}, { text: 'line1\nline2\nline3' })

singleDocFixture('xml_simple_element', 50, doc => {
  const frag = doc.getXmlFragment('content')
  const p = new Y.XmlElement('paragraph')
  p.insert(0, [new Y.XmlText('Hello World')])
  frag.insert(0, [p])
}, { content: '<paragraph>Hello World</paragraph>' })

singleDocFixture('xml_element_with_attrs', 51, doc => {
  const frag = doc.getXmlFragment('content')
  const p = new Y.XmlElement('paragraph')
  p.setAttribute('class', 'intro')
  p.insert(0, [new Y.XmlText('Hello')])
  frag.insert(0, [p])
}, { content: '<paragraph class="intro">Hello</paragraph>' })

singleDocFixture('xml_nested_elements', 52, doc => {
  const frag = doc.getXmlFragment('content')
  const div = new Y.XmlElement('div')
  const h1 = new Y.XmlElement('heading')
  h1.insert(0, [new Y.XmlText('Title')])
  const p = new Y.XmlElement('paragraph')
  p.insert(0, [new Y.XmlText('Body text')])
  div.insert(0, [h1, p])
  frag.insert(0, [div])
}, { content: '<div><heading>Title</heading><paragraph>Body text</paragraph></div>' })

singleDocFixture('xml_formatted_text', 53, doc => {
  const frag = doc.getXmlFragment('content')
  const p = new Y.XmlElement('paragraph')
  const txt = new Y.XmlText()
  txt.insert(0, 'Hello ')
  txt.insert(6, 'bold', { bold: true })
  txt.insert(10, ' world')
  p.insert(0, [txt])
  frag.insert(0, [p])
}, { content: '<paragraph>Hello <bold>bold world</bold></paragraph>' })

singleDocFixture('xml_hook', 54, doc => {
  const frag = doc.getXmlFragment('content')
  const hook = new Y.XmlHook('my-widget')
  hook.set('config', 'value')
  frag.insert(0, [hook])
}, { content: '[object Object]' })

singleDocFixture('xml_multiple_paragraphs', 55, doc => {
  const frag = doc.getXmlFragment('content')
  const p1 = new Y.XmlElement('paragraph')
  p1.insert(0, [new Y.XmlText('First paragraph')])
  const p2 = new Y.XmlElement('paragraph')
  p2.insert(0, [new Y.XmlText('Second paragraph')])
  const p3 = new Y.XmlElement('paragraph')
  p3.insert(0, [new Y.XmlText('Third paragraph')])
  frag.insert(0, [p1, p2, p3])
}, { content: '<paragraph>First paragraph</paragraph><paragraph>Second paragraph</paragraph><paragraph>Third paragraph</paragraph>' })

singleDocFixture('xml_with_maps_and_arrays', 56, doc => {
  const frag = doc.getXmlFragment('content')
  const p = new Y.XmlElement('paragraph')
  p.insert(0, [new Y.XmlText('Document text')])
  frag.insert(0, [p])
  doc.getMap('meta').set('title', 'My Doc')
  doc.getArray('tags').push(['tag1', 'tag2'])
}, { content: '<paragraph>Document text</paragraph>', meta: { title: 'My Doc' }, tags: ['tag1', 'tag2'] })

// ─── Category 2: Delete operations ────────────────────────────────────────────

deleteFixture('array_with_delete', 5, doc => {
  const arr = doc.getArray('arr')
  arr.push(['a', 'b', 'c', 'd', 'e'])
  arr.delete(1, 2)
}, { arr: ['a', 'd', 'e'] })

deleteFixture('map_with_delete', 6, doc => {
  const m = doc.getMap('map')
  m.set('a', 1)
  m.set('b', 2)
  m.set('c', 3)
  m.delete('b')
}, { map: { a: 1, c: 3 } })

deleteFixture('text_editing', 8, doc => {
  const t = doc.getText('text')
  t.insert(0, 'Hello')
  t.insert(5, ' World')
  t.delete(5, 1)
  t.insert(5, ', ')
}, { text: 'Hello, World' })

deleteFixture('map_overwrite', 12, doc => {
  const m = doc.getMap('map')
  m.set('key', 'value1')
  m.set('key', 'value2')
}, { map: { key: 'value2' } })

deleteFixture('text_delete_middle', 40, doc => {
  const t = doc.getText('text')
  t.insert(0, 'Hello World')
  t.delete(5, 1)
}, { text: 'HelloWorld' })

deleteFixture('text_delete_start', 41, doc => {
  const t = doc.getText('text')
  t.insert(0, 'Hello World')
  t.delete(0, 6)
}, { text: 'World' })

deleteFixture('text_delete_end', 42, doc => {
  const t = doc.getText('text')
  t.insert(0, 'Hello World')
  t.delete(5, 6)
}, { text: 'Hello' })

deleteFixture('array_delete_all', 43, doc => {
  const arr = doc.getArray('arr')
  arr.push([1, 2, 3, 4, 5])
  arr.delete(0, 5)
}, { arr: [] })

deleteFixture('array_delete_then_readd', 44, doc => {
  const arr = doc.getArray('arr')
  arr.push([1, 2, 3])
  arr.delete(0, 3)
  arr.push([4, 5, 6])
}, { arr: [4, 5, 6] })

deleteFixture('map_overwrite_chain', 45, doc => {
  const m = doc.getMap('map')
  m.set('key', 'v1')
  m.set('key', 'v2')
  m.set('key', 'v3')
  m.set('key', 'v4')
}, { map: { key: 'v4' } })

deleteFixture('array_delete_first', 46, doc => {
  const arr = doc.getArray('arr')
  arr.push(['a', 'b', 'c'])
  arr.delete(0, 1)
}, { arr: ['b', 'c'] })

deleteFixture('array_delete_last', 47, doc => {
  const arr = doc.getArray('arr')
  arr.push(['a', 'b', 'c'])
  arr.delete(2, 1)
}, { arr: ['a', 'b'] })

// ─── Category 3: Bidirectional sync ───────────────────────────────────────────

syncFixture('two_client_array_concurrent', 100, 200,
  doc => doc.getArray('arr').push(['a', 'b']),
  doc => doc.getArray('arr').push(['c', 'd'])
)

syncFixture('two_client_map_different_keys', 101, 201,
  doc => doc.getMap('map').set('x', 1),
  doc => doc.getMap('map').set('y', 2)
)

syncFixture('two_client_map_same_key', 102, 202,
  doc => doc.getMap('map').set('key', 'from-A'),
  doc => doc.getMap('map').set('key', 'from-B')
)

syncFixture('two_client_text_same_position', 103, 203,
  doc => doc.getText('text').insert(0, 'AAA'),
  doc => doc.getText('text').insert(0, 'BBB')
)

syncFixture('two_client_mixed_types', 104, 204,
  doc => {
    doc.getArray('arr').push([1, 2])
    doc.getMap('map').set('a', 'from-A')
  },
  doc => {
    doc.getArray('arr').push([3, 4])
    doc.getMap('map').set('b', 'from-B')
  }
)

syncFixture('two_client_array_interleave', 105, 205,
  doc => {
    const arr = doc.getArray('arr')
    arr.push(['a1', 'a2', 'a3'])
  },
  doc => {
    const arr = doc.getArray('arr')
    arr.push(['b1', 'b2', 'b3'])
  }
)

syncFixture('two_client_text_different_positions', 106, 206,
  doc => {
    const t = doc.getText('text')
    t.insert(0, 'Hello')
  },
  doc => {
    const t = doc.getText('text')
    t.insert(0, 'World')
  }
)

syncFixture('two_client_map_overwrite_conflict', 107, 207,
  doc => {
    const m = doc.getMap('map')
    m.set('shared', 'A-first')
    m.set('shared', 'A-second')
  },
  doc => {
    const m = doc.getMap('map')
    m.set('shared', 'B-first')
    m.set('shared', 'B-second')
  }
)

// Three-client chain sync
{
  const doc1 = new Y.Doc(); doc1.clientID = 110
  const doc2 = new Y.Doc(); doc2.clientID = 120
  const doc3 = new Y.Doc(); doc3.clientID = 130

  doc1.getMap('m').set('a', 1)
  doc2.getMap('m').set('b', 2)
  doc3.getMap('m').set('c', 3)

  // Sync 1↔2
  const sv1 = Y.encodeStateVector(doc1)
  const sv2 = Y.encodeStateVector(doc2)
  Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1, sv2))
  Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc2, sv1))

  // Sync 2↔3
  const sv2b = Y.encodeStateVector(doc2)
  const sv3 = Y.encodeStateVector(doc3)
  Y.applyUpdate(doc3, Y.encodeStateAsUpdate(doc2, sv3))
  Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc3, sv2b))

  // Sync 1↔3 (to fully converge)
  const sv1c = Y.encodeStateVector(doc1)
  const sv3c = Y.encodeStateVector(doc3)
  Y.applyUpdate(doc3, Y.encodeStateAsUpdate(doc1, sv3c))
  Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc3, sv1c))

  assert(deepEqual(sortKeys(doc1.toJSON()), sortKeys(doc2.toJSON())), 'Three-client: doc1 != doc2')
  assert(deepEqual(sortKeys(doc2.toJSON()), sortKeys(doc3.toJSON())), 'Three-client: doc2 != doc3')

  fixtures.sync_v1['three_client_chain'] = {
    clientA: 110, clientB: 120, clientC: 130,
    mergedUpdate: toHex(Y.encodeStateAsUpdate(doc1)),
    mergedStateVector: toHex(Y.encodeStateVector(doc1)),
    expected: sortKeys(doc1.toJSON())
  }
}

// Incremental sync (two rounds)
{
  const doc1 = new Y.Doc(); doc1.clientID = 140
  const doc2 = new Y.Doc(); doc2.clientID = 150

  // Round 1: doc1 pushes [1,2]
  doc1.getArray('arr').push([1, 2])
  const sv2r1 = Y.encodeStateVector(doc2)
  const u1to2r1 = Y.encodeStateAsUpdate(doc1, sv2r1)
  Y.applyUpdate(doc2, u1to2r1)

  // Round 2: doc2 pushes [3,4], sync back
  doc2.getArray('arr').push([3, 4])
  const sv1r2 = Y.encodeStateVector(doc1)
  const u2to1r2 = Y.encodeStateAsUpdate(doc2, sv1r2)
  Y.applyUpdate(doc1, u2to1r2)

  assert(deepEqual(sortKeys(doc1.toJSON()), sortKeys(doc2.toJSON())), 'Incremental sync convergence failed')

  fixtures.sync_v1['incremental_two_rounds'] = {
    clientA: 140, clientB: 150,
    sv2Round1: toHex(sv2r1),
    update1to2Round1: toHex(u1to2r1),
    sv1Round2: toHex(sv1r2),
    update2to1Round2: toHex(u2to1r2),
    mergedUpdate: toHex(Y.encodeStateAsUpdate(doc1)),
    mergedStateVector: toHex(Y.encodeStateVector(doc1)),
    expected: sortKeys(doc1.toJSON())
  }
}

// ─── Category 4: Snapshots ────────────────────────────────────────────────────

snapshotFixture('snapshot_array', 301, doc => {
  doc.getArray('arr').push([1, 2, 3])
}, doc => {
  doc.getArray('arr').delete(1, 1) // delete '2'
})

snapshotFixture('snapshot_text', 302, doc => {
  doc.getText('text').insert(0, 'Hello World')
}, doc => {
  doc.getText('text').delete(5, 6) // delete ' World'
})

snapshotFixture('snapshot_map', 303, doc => {
  const m = doc.getMap('map')
  m.set('a', 1)
  m.set('b', 2)
  m.set('c', 3)
}, doc => {
  doc.getMap('map').delete('b')
})

snapshotFixture('snapshot_multi_type', 304, doc => {
  doc.getArray('arr').push([1, 2])
  doc.getMap('map').set('key', 'val')
  doc.getText('text').insert(0, 'hi')
}, doc => {
  doc.getArray('arr').delete(0, 1)
  doc.getMap('map').delete('key')
  doc.getText('text').delete(0, 1)
})

// ─── Category 5: Relative positions ──────────────────────────────────────────

relPosFixture('rpos_text_middle', 401, doc => {
  doc.getText('text').insert(0, 'Hello World')
}, doc => ({ type: doc.getText('text'), index: 5, assoc: 0 }),
doc => {
  doc.getText('text').insert(0, 'XXX')
})

relPosFixture('rpos_text_start', 402, doc => {
  doc.getText('text').insert(0, 'Hello')
}, doc => ({ type: doc.getText('text'), index: 0, assoc: -1 }),
doc => {
  doc.getText('text').insert(0, 'YYY')
})

relPosFixture('rpos_array_middle', 403, doc => {
  doc.getArray('arr').push(['a', 'b', 'c', 'd', 'e'])
}, doc => ({ type: doc.getArray('arr'), index: 3, assoc: 0 }),
doc => {
  doc.getArray('arr').insert(0, ['x'])
})

relPosFixture('rpos_text_end', 404, doc => {
  doc.getText('text').insert(0, 'Hello')
}, doc => ({ type: doc.getText('text'), index: 5, assoc: -1 }),
doc => {
  doc.getText('text').insert(5, ' World')
})

relPosFixture('rpos_after_delete', 405, doc => {
  doc.getText('text').insert(0, 'ABCDE')
}, doc => ({ type: doc.getText('text'), index: 3, assoc: 0 }),
doc => {
  doc.getText('text').delete(0, 2) // delete AB
})

// ─── Category 6: State vectors ────────────────────────────────────────────────

{
  // Multi-client state vector
  const doc = new Y.Doc(); doc.clientID = 500
  doc.getArray('arr').push([1])
  const update1 = Y.encodeStateAsUpdate(doc)
  const doc2 = new Y.Doc(); doc2.clientID = 600
  Y.applyUpdate(doc2, update1)
  doc2.getArray('arr').push([2])

  fixtures.state_vectors['multi_client'] = {
    stateVector: toHex(Y.encodeStateVector(doc2)),
    expectedMap: { '500': 1, '600': 2 }
  }
}

{
  // Empty state vector
  const doc = new Y.Doc(); doc.clientID = 700
  fixtures.state_vectors['empty'] = {
    stateVector: toHex(Y.encodeStateVector(doc)),
    expectedMap: {}
  }
}

{
  // Single-client state vector
  const doc = new Y.Doc(); doc.clientID = 800
  doc.getArray('arr').push([1, 2, 3, 4, 5])
  fixtures.state_vectors['single_client'] = {
    stateVector: toHex(Y.encodeStateVector(doc)),
    expectedMap: { '800': 5 }
  }
}

{
  // Many-client state vector
  const doc = new Y.Doc(); doc.clientID = 900
  doc.getArray('arr').push(['a'])
  const update = Y.encodeStateAsUpdate(doc)
  for (let i = 1; i <= 5; i++) {
    const d = new Y.Doc(); d.clientID = 900 + i * 100
    Y.applyUpdate(d, update)
    d.getArray('arr').push([`from-${d.clientID}`])
    Y.applyUpdate(doc, Y.encodeStateAsUpdate(d, Y.encodeStateVector(doc)))
  }
  fixtures.state_vectors['many_clients'] = {
    stateVector: toHex(Y.encodeStateVector(doc)),
    expectedMap: Object.fromEntries(
      [...Y.decodeStateVector(Y.encodeStateVector(doc)).entries()]
        .map(([k, v]) => [String(k), v])
    )
  }
}

// ─── Category 7: Protocol messages (y-protocols wire format) ─────────────────

fixtures.protocol_messages = {}

{
  // SyncStep1 message: [messageSync=0, syncStep1=0, varByteArray(stateVector)]
  const doc = new Y.Doc(); doc.clientID = 1000
  doc.getArray('arr').push([1, 2, 3])

  const sv = Y.encodeStateVector(doc)
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, 0) // MESSAGE_SYNC
  encoding.writeVarUint(encoder, 0) // MESSAGE_SYNC_STEP1
  encoding.writeVarUint8Array(encoder, sv)

  fixtures.protocol_messages['sync_step1'] = {
    clientID: 1000,
    message: toHex(encoding.toUint8Array(encoder)),
    stateVector: toHex(sv),
    description: 'SyncStep1: messageSync(0) + syncStep1(0) + varByteArray(stateVector)'
  }
}

{
  // SyncStep2 message: [messageSync=0, syncStep2=1, varByteArray(update)]
  const doc = new Y.Doc(); doc.clientID = 1001
  doc.getMap('map').set('key', 'value')

  const update = Y.encodeStateAsUpdate(doc)
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, 0) // MESSAGE_SYNC
  encoding.writeVarUint(encoder, 1) // MESSAGE_SYNC_STEP2
  encoding.writeVarUint8Array(encoder, update)

  fixtures.protocol_messages['sync_step2'] = {
    clientID: 1001,
    message: toHex(encoding.toUint8Array(encoder)),
    update: toHex(update),
    description: 'SyncStep2: messageSync(0) + syncStep2(1) + varByteArray(update)'
  }
}

{
  // Update message: [messageSync=0, update=2, varByteArray(update)]
  const doc = new Y.Doc(); doc.clientID = 1002
  doc.getText('text').insert(0, 'Hello')

  const update = Y.encodeStateAsUpdate(doc)
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, 0) // MESSAGE_SYNC
  encoding.writeVarUint(encoder, 2) // MESSAGE_YJS_UPDATE
  encoding.writeVarUint8Array(encoder, update)

  fixtures.protocol_messages['update'] = {
    clientID: 1002,
    message: toHex(encoding.toUint8Array(encoder)),
    update: toHex(update),
    description: 'Update: messageSync(0) + update(2) + varByteArray(update)'
  }
}

{
  // Awareness message: [messageAwareness=1, varByteArray(awarenessUpdate)]
  // Awareness update: varUint(numClients) + for each: varUint(clientId) + varUint(clock) + varString(JSON(state))
  const clientID = 1003
  const clock = 1
  const state = { user: { name: 'Alice', color: '#ff0000' }, cursor: { index: 5 } }

  // Encode awareness update
  const awarenessEncoder = encoding.createEncoder()
  encoding.writeVarUint(awarenessEncoder, 1) // 1 client
  encoding.writeVarUint(awarenessEncoder, clientID)
  encoding.writeVarUint(awarenessEncoder, clock)
  encoding.writeVarString(awarenessEncoder, JSON.stringify(state))
  const awarenessBytes = encoding.toUint8Array(awarenessEncoder)

  // Wrap in top-level message
  const msgEncoder = encoding.createEncoder()
  encoding.writeVarUint(msgEncoder, 1) // MESSAGE_AWARENESS
  encoding.writeVarUint8Array(msgEncoder, awarenessBytes)

  fixtures.protocol_messages['awareness'] = {
    clientID: clientID,
    clock: clock,
    state: state,
    awarenessUpdate: toHex(awarenessBytes),
    message: toHex(encoding.toUint8Array(msgEncoder)),
    description: 'Awareness: messageAwareness(1) + varByteArray(awarenessUpdate)'
  }
}

{
  // Awareness with null state (disconnect): client going offline
  const clientID = 1004
  const clock = 5

  const awarenessEncoder = encoding.createEncoder()
  encoding.writeVarUint(awarenessEncoder, 1)
  encoding.writeVarUint(awarenessEncoder, clientID)
  encoding.writeVarUint(awarenessEncoder, clock)
  encoding.writeVarString(awarenessEncoder, JSON.stringify(null))
  const awarenessBytes = encoding.toUint8Array(awarenessEncoder)

  const msgEncoder = encoding.createEncoder()
  encoding.writeVarUint(msgEncoder, 1) // MESSAGE_AWARENESS
  encoding.writeVarUint8Array(msgEncoder, awarenessBytes)

  fixtures.protocol_messages['awareness_null'] = {
    clientID: clientID,
    clock: clock,
    state: null,
    awarenessUpdate: toHex(awarenessBytes),
    message: toHex(encoding.toUint8Array(msgEncoder)),
    description: 'Awareness null state (disconnect)'
  }
}

{
  // Query awareness message: [messageQueryAwareness=3]
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, 3) // MESSAGE_QUERY_AWARENESS

  fixtures.protocol_messages['query_awareness'] = {
    message: toHex(encoding.toUint8Array(encoder)),
    description: 'QueryAwareness: messageQueryAwareness(3)'
  }
}

{
  // Full handshake: SyncStep1 → SyncStep2 exchange between two docs
  const docA = new Y.Doc(); docA.clientID = 1010
  const docB = new Y.Doc(); docB.clientID = 1020
  docA.getArray('arr').push([1, 2])
  docB.getMap('map').set('key', 'val')

  // A sends SyncStep1
  const svA = Y.encodeStateVector(docA)
  const step1Encoder = encoding.createEncoder()
  encoding.writeVarUint(step1Encoder, 0)
  encoding.writeVarUint(step1Encoder, 0)
  encoding.writeVarUint8Array(step1Encoder, svA)

  // B receives, sends SyncStep2 (with A's missing updates from B)
  const updateBtoA = Y.encodeStateAsUpdate(docB, svA)
  const step2Encoder = encoding.createEncoder()
  encoding.writeVarUint(step2Encoder, 0)
  encoding.writeVarUint(step2Encoder, 1)
  encoding.writeVarUint8Array(step2Encoder, updateBtoA)

  // B also sends its own SyncStep1
  const svB = Y.encodeStateVector(docB)
  const step1BEncoder = encoding.createEncoder()
  encoding.writeVarUint(step1BEncoder, 0)
  encoding.writeVarUint(step1BEncoder, 0)
  encoding.writeVarUint8Array(step1BEncoder, svB)

  // A receives B's SyncStep1, sends SyncStep2
  const updateAtoB = Y.encodeStateAsUpdate(docA, svB)
  const step2AEncoder = encoding.createEncoder()
  encoding.writeVarUint(step2AEncoder, 0)
  encoding.writeVarUint(step2AEncoder, 1)
  encoding.writeVarUint8Array(step2AEncoder, updateAtoB)

  // Apply updates
  Y.applyUpdate(docA, updateBtoA)
  Y.applyUpdate(docB, updateAtoB)

  // Access types so they're properly typed in the share map
  docA.getArray('arr'); docA.getMap('map')
  docB.getArray('arr'); docB.getMap('map')

  assert(deepEqual(sortKeys(docA.toJSON()), sortKeys(docB.toJSON())), 'Handshake convergence failed')

  fixtures.protocol_messages['full_handshake'] = {
    clientA: 1010,
    clientB: 1020,
    step1_A_to_B: toHex(encoding.toUint8Array(step1Encoder)),
    step2_B_to_A: toHex(encoding.toUint8Array(step2Encoder)),
    step1_B_to_A: toHex(encoding.toUint8Array(step1BEncoder)),
    step2_A_to_B: toHex(encoding.toUint8Array(step2AEncoder)),
    expected: sortKeys(docA.toJSON()),
    description: 'Full SyncStep1/SyncStep2 handshake between two docs'
  }
}

// ─── Category: GC-origin resolution ──────────────────────────────────────────
// These fixtures test the case where a remote update references (via origin or
// rightOrigin) an item that has been garbage-collected in the receiving doc.
// GC only occurs for items whose parent item is itself deleted (nested structures).
// The correct Yjs behavior is to GC the incoming item as well.

{
  // Scenario 1: B inserts into a nested array whose parent A later deletes (GC's children).
  const docA = new Y.Doc(); docA.clientID = 500
  const docB = new Y.Doc(); docB.clientID = 600

  docA.transact(() => {
    const outer = docA.getArray('arr')
    const inner = new Y.Array()
    outer.push([inner])
    inner.push(['a', 'b', 'c'])
  })

  const syncAtoB = Y.encodeStateAsUpdate(docA)
  Y.applyUpdate(docB, syncAtoB)

  // B inserts into the inner array (origin = inner item 'a')
  docB.transact(() => {
    const inner = docB.getArray('arr').get(0)
    inner.insert(1, ['X', 'Y'])
  })

  const svA = Y.encodeStateVector(docA)
  const updateBpending = Y.encodeStateAsUpdate(docB, svA)

  // A deletes the parent item → inner items get GC'd
  docA.transact(() => { docA.getArray('arr').delete(0, 1) })

  const fullUpdateA = Y.encodeStateAsUpdate(docA)
  const fresh = new Y.Doc()
  Y.applyUpdate(fresh, fullUpdateA)
  Y.applyUpdate(fresh, updateBpending)

  fixtures.gc_origin['nested_array_origin_gc'] = {
    clientA: 500,
    clientB: 600,
    fullUpdateA: toHex(fullUpdateA),
    updateBpending: toHex(updateBpending),
    expected: sortKeys(fresh.toJSON()),
    description: 'B inserts into nested array whose parent A deletes. Origin resolves to GC struct.'
  }
}

{
  // Scenario 2: B inserts into a nested text inside a map value that A deletes.
  const docA = new Y.Doc(); docA.clientID = 501
  const docB = new Y.Doc(); docB.clientID = 601

  docA.transact(() => {
    const m = docA.getMap('map')
    const inner = new Y.Text()
    m.set('doc', inner)
    inner.insert(0, 'hello')
  })

  const syncAtoB = Y.encodeStateAsUpdate(docA)
  Y.applyUpdate(docB, syncAtoB)

  // B inserts into the nested text (origin = 'o' at end)
  docB.transact(() => {
    const inner = docB.getMap('map').get('doc')
    inner.insert(5, ' world')
  })

  const svA = Y.encodeStateVector(docA)
  const updateBpending = Y.encodeStateAsUpdate(docB, svA)

  // A overwrites the map key (deletes old value item → GC's nested text items)
  docA.transact(() => { docA.getMap('map').set('doc', 'replaced') })

  const fullUpdateA = Y.encodeStateAsUpdate(docA)
  const fresh = new Y.Doc()
  Y.applyUpdate(fresh, fullUpdateA)
  Y.applyUpdate(fresh, updateBpending)

  fixtures.gc_origin['nested_text_origin_gc'] = {
    clientA: 501,
    clientB: 601,
    fullUpdateA: toHex(fullUpdateA),
    updateBpending: toHex(updateBpending),
    expected: sortKeys(fresh.toJSON()),
    description: 'B appends to nested YText that A replaces via map.set. Origin resolves to GC.'
  }
}

{
  // Scenario 3: rightOrigin points to GC'd struct in nested array.
  const docA = new Y.Doc(); docA.clientID = 502
  const docB = new Y.Doc(); docB.clientID = 602

  docA.transact(() => {
    const outer = docA.getArray('arr')
    const inner = new Y.Array()
    outer.push([inner])
    inner.push(['x', 'y', 'z'])
  })

  const syncAtoB = Y.encodeStateAsUpdate(docA)
  Y.applyUpdate(docB, syncAtoB)

  // B inserts at position 0 in inner array (rightOrigin = first item 'x')
  docB.transact(() => {
    const inner = docB.getArray('arr').get(0)
    inner.insert(0, ['Z'])
  })

  const svA = Y.encodeStateVector(docA)
  const updateBpending = Y.encodeStateAsUpdate(docB, svA)

  // A deletes the parent
  docA.transact(() => { docA.getArray('arr').delete(0, 1) })

  const fullUpdateA = Y.encodeStateAsUpdate(docA)
  const fresh = new Y.Doc()
  Y.applyUpdate(fresh, fullUpdateA)
  Y.applyUpdate(fresh, updateBpending)

  fixtures.gc_origin['nested_array_right_origin_gc'] = {
    clientA: 502,
    clientB: 602,
    fullUpdateA: toHex(fullUpdateA),
    updateBpending: toHex(updateBpending),
    expected: sortKeys(fresh.toJSON()),
    description: 'B inserts at pos 0 of nested array. rightOrigin resolves to GC struct.'
  }
}

{
  // Scenario 4: Both origin AND rightOrigin are GC'd (insert in middle of nested array).
  const docA = new Y.Doc(); docA.clientID = 503
  const docB = new Y.Doc(); docB.clientID = 603

  docA.transact(() => {
    const outer = docA.getArray('arr')
    const inner = new Y.Array()
    outer.push([inner])
    inner.push([1, 2, 3, 4, 5])
  })

  const syncAtoB = Y.encodeStateAsUpdate(docA)
  Y.applyUpdate(docB, syncAtoB)

  // B inserts in the middle (origin = item '2', rightOrigin = item '3')
  docB.transact(() => {
    const inner = docB.getArray('arr').get(0)
    inner.insert(2, [99])
  })

  const svA = Y.encodeStateVector(docA)
  const updateBpending = Y.encodeStateAsUpdate(docB, svA)

  // A deletes the parent
  docA.transact(() => { docA.getArray('arr').delete(0, 1) })

  const fullUpdateA = Y.encodeStateAsUpdate(docA)
  const fresh = new Y.Doc()
  Y.applyUpdate(fresh, fullUpdateA)
  Y.applyUpdate(fresh, updateBpending)

  fixtures.gc_origin['nested_array_both_origins_gc'] = {
    clientA: 503,
    clientB: 603,
    fullUpdateA: toHex(fullUpdateA),
    updateBpending: toHex(updateBpending),
    expected: sortKeys(fresh.toJSON()),
    description: 'B inserts in middle of nested array. Both origin and rightOrigin are GC.'
  }
}

// ─── Output ───────────────────────────────────────────────────────────────────

// Count fixtures for summary
let total = 0
for (const [section, entries] of Object.entries(fixtures)) {
  if (section === 'metadata') continue
  const count = Object.keys(entries).length
  total += count
}
fixtures.metadata.totalFixtures = total

console.log(JSON.stringify(fixtures, null, 2))
