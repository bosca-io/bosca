#!/usr/bin/env node
/**
 * Generates Yjs binary fixtures from a known set of ProseMirror documents.
 *
 * The fixtures are consumed by the Kotlin test suite at
 * `bosca-content/core-content/src/test/kotlin/bosca/documents/yjs/ProseMirrorYjsBridgeWireFormatTest.kt`
 * to prove that the JVM bridge (`ProseMirrorYjsBridge`) consumes the exact wire
 * format that `y-prosemirror` produces in the browser. Without this cross-runtime
 * check, the bridge could drift in subtle ways that only surface when a real user
 * opens a document edited by a non-editor caller.
 *
 * The schema below is a hand-rolled subset of TipTap's effective schema — it
 * names every node and mark TipTap uses by the same camelCase identifier and
 * exposes the attributes y-prosemirror writes into the YXmlElement attribute map.
 * This avoids pulling TipTap in (TipTap requires a DOM at construction time).
 *
 * Run from the studio project root:
 *   node scripts/gen-yjs-fixtures.mjs
 *
 * Outputs to:
 *   bosca-content/core-content/src/test/resources/yjs-fixtures/<name>.bin
 *   bosca-content/core-content/src/test/resources/yjs-fixtures/<name>.json
 */

import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as Y from 'yjs'
import { prosemirrorJSONToYXmlFragment } from 'y-prosemirror'
// `prosemirror-model` is re-exported by `@tiptap/pm/model`, which is hoisted
// into studio's node_modules; the underlying prosemirror packages are not.
import { Schema } from '@tiptap/pm/model'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = resolve(
  __dirname,
  '../../../../content/core-content/src/test/resources/yjs-fixtures',
)

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*', parseDOM: [{ tag: 'p' }], toDOM: () => ['p', 0] },
    heading: {
      group: 'block',
      content: 'inline*',
      attrs: { level: { default: 1 } },
      parseDOM: [1, 2, 3, 4, 5, 6].map((l) => ({ tag: `h${l}`, attrs: { level: l } })),
      toDOM: (node) => [`h${node.attrs.level}`, 0],
    },
    bulletList: {
      group: 'block',
      content: 'listItem+',
      parseDOM: [{ tag: 'ul' }],
      toDOM: () => ['ul', 0],
    },
    orderedList: {
      group: 'block',
      content: 'listItem+',
      attrs: { start: { default: 1 } },
      parseDOM: [{ tag: 'ol' }],
      toDOM: () => ['ol', 0],
    },
    listItem: {
      content: 'paragraph block*',
      parseDOM: [{ tag: 'li' }],
      toDOM: () => ['li', 0],
    },
    taskList: {
      group: 'block',
      content: 'taskItem+',
      parseDOM: [{ tag: 'ul' }],
      toDOM: () => ['ul', 0],
    },
    taskItem: {
      content: 'paragraph block*',
      attrs: { checked: { default: false } },
      parseDOM: [{ tag: 'li' }],
      toDOM: () => ['li', 0],
    },
    codeBlock: {
      group: 'block',
      content: 'text*',
      attrs: { language: { default: null } },
      marks: '',
      code: true,
      parseDOM: [{ tag: 'pre' }],
      toDOM: () => ['pre', ['code', 0]],
    },
    table: {
      group: 'block',
      content: 'tableRow+',
      parseDOM: [{ tag: 'table' }],
      toDOM: () => ['table', ['tbody', 0]],
    },
    tableRow: {
      content: '(tableCell | tableHeader)*',
      parseDOM: [{ tag: 'tr' }],
      toDOM: () => ['tr', 0],
    },
    tableCell: {
      content: 'block+',
      attrs: { colspan: { default: 1 }, rowspan: { default: 1 }, colwidth: { default: null } },
      parseDOM: [{ tag: 'td' }],
      toDOM: () => ['td', 0],
    },
    tableHeader: {
      content: 'block+',
      attrs: { colspan: { default: 1 }, rowspan: { default: 1 }, colwidth: { default: null } },
      parseDOM: [{ tag: 'th' }],
      toDOM: () => ['th', 0],
    },
    text: { group: 'inline' },
  },
  marks: {
    bold: { parseDOM: [{ tag: 'strong' }], toDOM: () => ['strong', 0] },
    italic: { parseDOM: [{ tag: 'em' }], toDOM: () => ['em', 0] },
    underline: { parseDOM: [{ tag: 'u' }], toDOM: () => ['u', 0] },
    strike: { parseDOM: [{ tag: 's' }], toDOM: () => ['s', 0] },
    code: { parseDOM: [{ tag: 'code' }], toDOM: () => ['code', 0] },
    link: {
      attrs: { href: { default: null }, target: { default: null } },
      parseDOM: [{ tag: 'a[href]' }],
      toDOM: (mark) => ['a', { href: mark.attrs.href, target: mark.attrs.target }, 0],
    },
  },
})

/**
 * Each fixture: a ProseMirror doc JSON. We encode it through y-prosemirror to
 * produce a Yjs binary update; the Kotlin tests load both files and assert that
 * `ProseMirrorYjsBridge.toContent(<binary>)` produces the same logical tree.
 */
const fixtures = [
  {
    name: 'paragraph',
    doc: { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: 'Hello world' }] },
    ] },
  },
  {
    name: 'heading-with-level',
    doc: { type: 'doc', content: [
      { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Section' }] },
      { type: 'paragraph', content: [{ type: 'text', text: 'Body text under the section.' }] },
    ] },
  },
  {
    name: 'marks-bold-italic-link',
    doc: { type: 'doc', content: [
      { type: 'paragraph', content: [
        { type: 'text', text: 'plain ' },
        { type: 'text', text: 'bold', marks: [{ type: 'bold' }] },
        { type: 'text', text: ' and ' },
        { type: 'text', text: 'italic', marks: [{ type: 'italic' }] },
        { type: 'text', text: ' and ' },
        { type: 'text', text: 'a link', marks: [{ type: 'link', attrs: { href: 'https://example.com', target: '_blank' } }] },
        { type: 'text', text: '.' },
      ] },
    ] },
  },
  {
    name: 'inline-code-and-strike',
    doc: { type: 'doc', content: [
      { type: 'paragraph', content: [
        { type: 'text', text: 'try ' },
        { type: 'text', text: 'foo()', marks: [{ type: 'code' }] },
        { type: 'text', text: '. Old ' },
        { type: 'text', text: 'content', marks: [{ type: 'strike' }] },
        { type: 'text', text: ' is gone.' },
      ] },
    ] },
  },
  {
    name: 'bullet-list',
    doc: { type: 'doc', content: [
      { type: 'bulletList', content: [
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'one' }] }] },
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'two' }] }] },
      ] },
    ] },
  },
  {
    name: 'nested-bullet-list',
    doc: { type: 'doc', content: [
      { type: 'bulletList', content: [
        { type: 'listItem', content: [
          { type: 'paragraph', content: [{ type: 'text', text: 'outer' }] },
          { type: 'bulletList', content: [
            { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'inner' }] }] },
          ] },
        ] },
      ] },
    ] },
  },
  {
    name: 'task-list',
    doc: { type: 'doc', content: [
      { type: 'taskList', content: [
        { type: 'taskItem', attrs: { checked: true }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'done' }] }] },
        { type: 'taskItem', attrs: { checked: false }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'todo' }] }] },
      ] },
    ] },
  },
  {
    name: 'code-block-with-language',
    doc: { type: 'doc', content: [
      { type: 'codeBlock', attrs: { language: 'kotlin' }, content: [{ type: 'text', text: 'fun main() {}' }] },
    ] },
  },
  {
    name: 'table',
    doc: { type: 'doc', content: [
      { type: 'table', content: [
        { type: 'tableRow', content: [
          { type: 'tableHeader', attrs: { colspan: 1, rowspan: 1, colwidth: null }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'h1' }] }] },
          { type: 'tableHeader', attrs: { colspan: 1, rowspan: 1, colwidth: null }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'h2' }] }] },
        ] },
        { type: 'tableRow', content: [
          { type: 'tableCell', attrs: { colspan: 1, rowspan: 1, colwidth: null }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'a' }] }] },
          { type: 'tableCell', attrs: { colspan: 1, rowspan: 1, colwidth: null }, content: [{ type: 'paragraph', content: [{ type: 'text', text: 'b' }] }] },
        ] },
      ] },
    ] },
  },
]

async function main() {
  await mkdir(OUT_DIR, { recursive: true })
  for (const { name, doc } of fixtures) {
    const ydoc = new Y.Doc()
    prosemirrorJSONToYXmlFragment(schema, doc, ydoc.getXmlFragment('default'))
    const update = Y.encodeStateAsUpdate(ydoc)
    await writeFile(resolve(OUT_DIR, `${name}.bin`), Buffer.from(update))
    await writeFile(resolve(OUT_DIR, `${name}.json`), JSON.stringify(doc, null, 2) + '\n')
    console.log(`wrote ${name} (${update.byteLength} bytes)`)
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
