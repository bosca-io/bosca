/* eslint-disable @typescript-eslint/no-explicit-any */
import type { Collection, CollectionLanguageVariantMetadataRelationship, CollectionMetadataRelationship, Metadata, MetadataRelationship, TemplateAttribute } from '~/types/graphql'
import { type AttributeMetadata, AttributeState } from '~/utils/editor/attribute'
import type * as Y from 'yjs'

export function newAttributes(
  ydoc: Y.Doc,
  item: Metadata | Collection,
  current: Map<string, AttributeState>,
  attributes: Ref<{ [key: string]: any }>,
  parentCollections: Ref<Collection[]>,
  relationships: Ref<(CollectionMetadataRelationship | CollectionLanguageVariantMetadataRelationship | MetadataRelationship)[]>,
  templateAttributes: TemplateAttribute[]
) {
  const dirtyCollections = ydoc?.getMap('collections')?.get('|__dirty__|') === 'true'
  const dirtyRelationships = ydoc?.getMap('metadatas')?.get('|__dirty__|') === 'true'
  const dirtyAttributes = ydoc?.getMap('attrs')?.get('|__dirty__|') === 'true'
  for (const templateAttribute of templateAttributes) {
    let attr = current.get(templateAttribute.key)
    if (!attr || item.id != attr.contentId) {
      attr?.dispose()
      attr = new AttributeState(ydoc, item.__typename || '', item.id, attributes, parentCollections, relationships, templateAttribute)
    }
    // Server-side changes (e.g. setMetadataParentCollections) set these
    // per-kind dirty flags in the stored CRDT so clients re-seed values from
    // the freshly queried item. Newly constructed states must re-seed too —
    // on first open nothing is in `current` yet, and skipping them would
    // delete the flags below without ever applying the new server state.
    const type = attr.attribute.type
    if (dirtyCollections && type === 'COLLECTION') {
      attr.reset()
    } else if (dirtyRelationships && type === 'METADATA') {
      attr.reset()
    } else if (dirtyAttributes && !(type === 'COLLECTION' || type === 'METADATA')) {
      attr.reset()
    }
    current.set(templateAttribute.key, attr)
  }
  if (dirtyCollections) {
    ydoc.getMap('collections')?.delete('|__dirty__|')
  }
  if (dirtyRelationships) {
    ydoc.getMap('metadatas')?.delete('|__dirty__|')
  }
  if (dirtyAttributes) {
    ydoc.getMap('attrs')?.delete('|__dirty__|')
  }
}

export function applyAttributes(
  item: Metadata | Collection,
  attrs: Map<string, AttributeState>,
  rawAttributes: Ref<{ [key: string]: any }>,
  parentCollections: any[],
  relationships: ({ id1: string, id2: string, relationship: string, attributes: any })[]
) {
  const attributes: { [key: string]: any } = {}
  if (rawAttributes.value) {
    const attrs = toRaw(rawAttributes.value)
    for (const key of Object.keys(attrs)) {
      attributes[key] = toRaw(attrs[key])
    }
  }
  const collectionTypes: { [key: string]: boolean } = {}
  const relationshipTypes: { [key: string]: boolean } = {}
  if (attrs && attrs.size > 0) {
    for (const attrKey of attrs.keys()) {
      const attr = attrs.get(attrKey)
      if (!attr) continue
      if (attr.type === 'COLLECTION' && attr.configuration) {
        collectionTypes[(attr.configuration as any).type] = true
      }
      if (attr.type === 'METADATA' && attr.configuration) {
        relationshipTypes[(attr.configuration as any).relationship] = true
      }
    }
  }
  for (const parent of item.parentCollections || []) {
    // Preserve parent collections that are NOT managed by a template
    // COLLECTION attribute. A collection is template-managed when its
    // attributes.type matches a template attribute's configuration.type.
    // Collections without attributes (or without a type) are never
    // template-managed and must always be kept.
    const parentType = (parent.attributes as any)?.type
    if (!parentType || !collectionTypes[parentType]) {
      parentCollections.push({
        id: parent.id,
        attributes: (parent as any).itemAttributes || {}
      })
    }
  }
  function newRelationship(rel: CollectionMetadataRelationship | MetadataRelationship | AttributeMetadata, isAttributeMetadata: boolean = false): { id1: string, id2: string, relationship: string, attributes: any } {
    let id2: string | null = null
    let relationship: string | null | undefined = null
    let attributes: any | null = null
    if (isAttributeMetadata) {
      const r = rel as AttributeMetadata
      id2 = r.id
      relationship = r.relationship
      attributes = r.attributes
    } else {
      // eslint-disable-next-line @typescript-eslint/ban-ts-comment
      // @ts-expect-error
      if (rel.__typename === 'CollectionMetadataRelationship') {
        const r = rel as CollectionMetadataRelationship
        id2 = r.metadata.id
        relationship = r.relationship
        attributes = r.attributes
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-expect-error
      } else if (rel.__typename === 'MetadataRelationship') {
        const r = rel as MetadataRelationship
        id2 = r.metadata.id
        relationship = r.relationship
        attributes = r.attributes
      } else {
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-expect-error
        throw new Error('unexpected relationship type: ' + rel.__typename)
      }
    }
    if (!id2 || !relationship) {
      const details = isAttributeMetadata
        ? `Metadata attribute "${(rel as AttributeMetadata).name || (rel as AttributeMetadata).id}" is missing a "relationship" value. Check that the template's METADATA attribute has a "relationship" configured in its configuration.`
        : `Relationship object is missing required fields (id2=${id2}, relationship=${relationship}).`
      throw new Error(`Invalid relationship: ${details}\n\nRaw data: ${JSON.stringify(rel)}`)
    }
    if (item.__typename === 'Collection') {
      return {
        id1: item.id,
        id2: id2,
        relationship: relationship,
        attributes: attributes
      }
    } else if (item.__typename === 'Metadata') {
      return {
        id1: item.id,
        id2: id2,
        relationship: relationship,
        attributes: attributes
      }
    } else {
      throw new Error('unexpected item type: ' + item.__typename)
    }
  }
  if (item.__typename === 'Collection') {
    for (const rel of item.metadataRelationships) {
      if (!rel.relationship || !relationshipTypes[rel.relationship]) {
        relationships.push(newRelationship(rel))
      }
    }
  } else if (item.__typename === 'Metadata') {
    for (const rel of item.relationships) {
      if (!rel.relationship || !relationshipTypes[rel.relationship]) {
        relationships.push(newRelationship(rel))
      }
    }
  }
  if (attrs && attrs.size > 0) {
    for (const attrKey of attrs.keys()) {
      const attr = attrs.get(attrKey)
      if (!attr) continue
      const key = attr.key
      // A value can point at a parent that was already preserved above (it
      // survives the template-managed check when the collection carries no
      // `attributes.type` marker) — don't send the same parent twice.
      const pushParentCollection = (id: any) => {
        if (!parentCollections.some(p => p.id === id)) {
          parentCollections.push({ id, attributes: {} })
        }
      }
      switch (attr.type) {
        case 'COLLECTION':
          if (attr.list) {
            const ids = attr.collections
            if (ids && ids.length > 0) {
              for (const id of ids) {
                if (!id) continue
                pushParentCollection(id.id || id)
              }
            } else if (attr.collection?.id) {
              pushParentCollection(attr.collection.id)
            }
          } else if (attr.collection?.id) {
            pushParentCollection(attr.collection.id)
          }
          break
        case 'DATE':
        case 'DATETIME':
        case 'DATE_TIME':
          if (attr.nested) {
            const path = key.split('.')
            let cur = attributes
            for (let i = 0; i < path.length - 1; i++) {
              cur = cur[path[i] || '']
            }
            cur[path[path.length - 1] || ''] = attr.dateTimeValueRaw
          } else {
            attributes[key] = attr.dateTimeValueRaw
          }
          break
        case 'FLOAT':
        case 'INT':
          if (attr.nested) {
            const path = key.split('.')
            let cur = attributes
            for (let i = 0; i < path.length - 1; i++) {
              cur = cur[path[i] || '']
            }
            cur[path[path.length - 1] || ''] = attr.numberValue
          } else {
            attributes[key] = attr.numberValue
          }
          break
        case 'METADATA': {
          // Values can carry a stale relationship name from before a template
          // configuration change — the configured name always wins on save.
          const configRel = (attr.configuration as any)?.relationship as string | undefined
          const normalized = (m: AttributeMetadata): AttributeMetadata =>
            configRel ? { ...m, relationship: configRel } : m
          if (attr.list) {
            const ids = attr.metadatas
            if (ids && ids.length > 0) {
              for (const id of ids) {
                if (!id) continue
                relationships.push(newRelationship(normalized(id), true))
              }
            } else if (attr.metadata?.id) {
              relationships.push(newRelationship(normalized(attr.metadata), true))
            }
          } else if (attr.metadata?.id) {
            relationships.push(newRelationship(normalized(attr.metadata), true))
          }
          break
        }
        case 'PROFILE':
          // TODO
          break
        case 'STRING':
          attributes[key] = attr.textValue
          break
      }
    }
  }
  rawAttributes.value = attributes
}
