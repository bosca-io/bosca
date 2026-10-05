/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import * as Y from 'yjs'
import { newAttributes, applyAttributes } from './attributes'
import type { AttributeState } from './attribute'
import type { Collection, Metadata, TemplateAttribute } from '~/types/graphql'

function templateAttribute(overrides: Record<string, unknown>): TemplateAttribute {
  return {
    key: 'attr',
    name: 'Attr',
    description: '',
    type: 'STRING',
    list: false,
    location: 'ITEM',
    supplementaryKey: null,
    ui: 'INPUT',
    configuration: null,
    workflows: [],
    tools: [],
    ...overrides,
  } as unknown as TemplateAttribute
}

function metadataItem(overrides: Record<string, unknown> = {}): Metadata {
  return {
    __typename: 'Metadata',
    id: 'meta-1',
    relationships: [],
    parentCollections: [],
    ...overrides,
  } as unknown as Metadata
}

function collection(id: string, name: string, type?: string): Collection {
  return {
    __typename: 'Collection',
    id,
    name,
    attributes: type ? { type } : {},
  } as unknown as Collection
}

interface Refs {
  attributes: ReturnType<typeof ref<{ [key: string]: any }>>
  parentCollections: ReturnType<typeof ref<Collection[]>>
  relationships: ReturnType<typeof ref<any[]>>
}

function newRefs(overrides: Partial<{ attributes: any, parentCollections: Collection[], relationships: any[] }> = {}): Refs {
  return {
    attributes: ref(overrides.attributes ?? {}),
    parentCollections: ref(overrides.parentCollections ?? []),
    relationships: ref(overrides.relationships ?? []),
  }
}

function runNewAttributes(
  ydoc: Y.Doc,
  refs: Refs,
  templates: TemplateAttribute[],
  current: Map<string, AttributeState> = new Map(),
  item: Metadata = metadataItem(),
) {
  newAttributes(
    ydoc,
    item,
    current,
    refs.attributes as any,
    refs.parentCollections as any,
    refs.relationships as any,
    templates,
  )
  return current
}

describe('newAttributes', () => {
  it('creates attribute states without re-seeding when no dirty flags are set', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('textAttributes').set('title', 'from-ydoc')
    const refs = newRefs({ attributes: { title: 'from-server' } })

    const current = runNewAttributes(ydoc, refs, [templateAttribute({ key: 'title' })])

    // The CRDT value is authoritative when the server did not mark anything dirty.
    expect(current.get('title')?.textValue).toBe('from-ydoc')
  })

  it('re-seeds newly created states from server attributes when the attrs dirty flag is set', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('textAttributes').set('title', 'stale')
    ydoc.getMap('attrs').set('|__dirty__|', 'true')
    const refs = newRefs({ attributes: { title: 'fresh' } })

    const current = runNewAttributes(ydoc, refs, [templateAttribute({ key: 'title' })])

    expect(current.get('title')?.textValue).toBe('fresh')
    expect(ydoc.getMap('attrs').has('|__dirty__|')).toBe(false)
  })

  it('re-seeds newly created collection states from typed parents when the collections dirty flag is set', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collections').set('|__dirty__|', 'true')
    const refs = newRefs({ parentCollections: [collection('c1', 'Series A', 'series')] })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', configuration: { type: 'series' } })

    const current = runNewAttributes(ydoc, refs, [template])

    expect(current.get('series')?.collection).toEqual({ id: 'c1', name: 'Series A' })
    expect(ydoc.getMap('collections').has('|__dirty__|')).toBe(false)
  })

  it('keeps a connected collection that lacks the type marker when re-seeding', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collection').set('series', JSON.stringify({ id: 'c1', name: 'Series A' }))
    ydoc.getMap('collections').set('|__dirty__|', 'true')
    // The parent connection is real, but the collection carries no attributes.type.
    const refs = newRefs({ parentCollections: [collection('c1', 'Series A')] })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', configuration: { type: 'series' } })

    const current = runNewAttributes(ydoc, refs, [template])

    expect(current.get('series')?.collection).toEqual({ id: 'c1', name: 'Series A' })
  })

  it('clears a collection value whose parent connection no longer exists', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collection').set('series', JSON.stringify({ id: 'gone', name: 'Removed' }))
    ydoc.getMap('collections').set('|__dirty__|', 'true')
    const refs = newRefs({ parentCollections: [collection('other', 'Other')] })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', configuration: { type: 'series' } })

    const current = runNewAttributes(ydoc, refs, [template])

    expect(current.get('series')?.collection).toBeNull()
  })

  it('merges typed matches with still-connected unmarked values for list collections', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collections').set('series', JSON.stringify([
      { id: 'c1', name: 'Unmarked' },
      { id: 'gone', name: 'Removed' },
    ]))
    ydoc.getMap('collections').set('|__dirty__|', 'true')
    const refs = newRefs({
      parentCollections: [
        collection('c1', 'Unmarked'),
        collection('c2', 'Typed', 'series'),
      ],
    })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', list: true, configuration: { type: 'series' } })

    const current = runNewAttributes(ydoc, refs, [template])

    expect(current.get('series')?.collections).toEqual([
      { id: 'c2', name: 'Typed' },
      { id: 'c1', name: 'Unmarked' },
    ])
  })

  it('re-seeds existing states when their content id changes', () => {
    const ydoc = new Y.Doc()
    const refs = newRefs()
    const template = templateAttribute({ key: 'title' })
    const current = runNewAttributes(ydoc, refs, [template])
    const first = current.get('title')

    runNewAttributes(ydoc, refs, [template], current, metadataItem({ id: 'meta-2' }))

    expect(current.get('title')).not.toBe(first)
    expect(current.get('title')?.contentId).toBe('meta-2')
  })
})

describe('applyAttributes', () => {
  it('does not duplicate a parent collection that was both preserved and held as an attribute value', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collection').set('series', JSON.stringify({ id: 'c1', name: 'Series A' }))
    // The parent has no attributes.type marker, so it survives the
    // template-managed check AND is pushed again from the attribute value.
    const item = metadataItem({ parentCollections: [collection('c1', 'Series A')] })
    const refs = newRefs({ parentCollections: item.parentCollections as Collection[] })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', configuration: { type: 'series' } })
    const attrs = runNewAttributes(ydoc, refs, [template], new Map(), item)

    const parentCollections: any[] = []
    const relationships: any[] = []
    applyAttributes(item, attrs, refs.attributes as any, parentCollections, relationships)

    expect(parentCollections.filter(p => p.id === 'c1')).toHaveLength(1)
  })

  it('keeps distinct parent collections from preservation and attribute values', () => {
    const ydoc = new Y.Doc()
    ydoc.getMap('collection').set('series', JSON.stringify({ id: 'c2', name: 'Series B' }))
    const item = metadataItem({ parentCollections: [collection('c1', 'Unmanaged')] })
    const refs = newRefs({ parentCollections: item.parentCollections as Collection[] })
    const template = templateAttribute({ key: 'series', type: 'COLLECTION', configuration: { type: 'series' } })
    const attrs = runNewAttributes(ydoc, refs, [template], new Map(), item)

    const parentCollections: any[] = []
    const relationships: any[] = []
    applyAttributes(item, attrs, refs.attributes as any, parentCollections, relationships)

    expect(parentCollections.map(p => p.id).sort()).toEqual(['c1', 'c2'])
  })
})
