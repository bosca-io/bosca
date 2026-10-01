/* eslint-disable @typescript-eslint/no-explicit-any */
import { useAuth } from '@bosca/auth-client-browser'
import { newYDoc, markYDocReady, onYDocReady } from '~/utils/editor/ydoc'
import { newAttributes } from '~/utils/editor/attributes'
import type { Collection, CollectionLanguageVariantMetadataRelationship, CollectionMetadataRelationship, Metadata, MetadataRelationship, Profile } from '~/types/graphql'
import type * as Y from 'yjs'
import type { AttributeState } from '~/utils/editor/attribute'

interface CollaborationAndAttributes {
  ydoc: Ref<Y.Doc | null>
  ready: Ref<boolean>
  attributes: Map<string, AttributeState>
  parentCollections: Ref<Collection[]>
  relationships: Ref<(MetadataRelationship | CollectionMetadataRelationship | CollectionLanguageVariantMetadataRelationship)[]>
  rawAttributes: Ref<{ [key: string]: any }>
  reloadParentCollections: () => void
  reloadRelationships: () => void
  refreshState: () => void
  reload: () => void
}

export const useCollaborationAndAttributes = (
  item: Ref<Metadata | Collection | undefined | null>,
  profile: Ref<Profile>
): CollaborationAndAttributes => {
  let cachedYDoc: Y.Doc | null | undefined = null
  const authState = import.meta.client ? useAuth() : null
  const rawAttributes = ref<{ [key: string]: any }>(
    item.value?.__typename === 'Collection'
      ? (item.value as any)?.languageVariant?.attributes || item.value?.attributes || {}
      : item.value?.attributes || {}
  )
  const parentCollections = ref<Collection[]>((item.value as any)?.parentCollections || [])
  const relationships = ref(
    item.value?.__typename === 'Metadata'
      ? (item.value as any)?.relationships
      : item.value?.__typename === 'Collection'
        ? (item.value as any)?.languageVariant?.metadataRelationships || (item.value as any)?.metadataRelationships || []
        : []
  )
  const templateAttributes = ref(
    item.value?.__typename === 'Metadata'
      ? ((item.value as any)?.document?.template?.documentTemplate?.attributes || (item.value as any)?.data?.template?.dataTemplate?.attributes || (item.value as any)?.guide?.template?.documentTemplate?.attributes)
      : item.value?.__typename === 'Collection'
        ? (item.value as any)?.templateMetadata?.collectionTemplate?.attributes
        : []
  )
  const ready = ref(false)
  const ydoc = shallowRef<Y.Doc | null>(null)

  async function initYDoc() {
    if (cachedYDoc) {
      ydoc.value = cachedYDoc
      return
    }
    if (!item.value || !profile.value) {
      ydoc.value = null
      return
    }
    if (import.meta.server) {
      ydoc.value = null
      return
    }
    cachedYDoc = await newYDoc(
      item.value,
      rawAttributes,
      parentCollections,
      relationships,
      profile.value,
      authState?.auth.token ?? '',
      (e) => {
        console.warn('YDoc error', e)
      }
    )
    ydoc.value = cachedYDoc ?? null
  }

  const attributes = new Map<string, AttributeState>()

  function updateAttributes() {
    const doc = ydoc.value
    attributes.clear()
    if (!item.value || !doc || !templateAttributes.value) {
      return
    }
    newAttributes(
      doc,
      item.value,
      attributes,
      rawAttributes,
      parentCollections,
      relationships,
      templateAttributes.value
    )
  }

  watch([ydoc, item, templateAttributes], () => {
    updateAttributes()
    const doc = toRaw(ydoc.value)
    if (!doc) return
    const isDocument = item.value?.__typename === 'Metadata' && !!(item.value as any).document
    if (!isDocument) {
      markYDocReady(doc)
    }
    onYDocReady(doc, () => { ready.value = true })
  })

  watch(item, () => { initYDoc() }, { immediate: true })

  const refreshState = () => {
    rawAttributes.value = item.value?.__typename === 'Collection'
      ? (item.value as any)?.languageVariant?.attributes || item.value?.attributes || {}
      : item.value?.attributes || {}

    parentCollections.value = (item.value as any)?.parentCollections || []

    relationships.value = item.value?.__typename === 'Metadata'
      ? (item.value as any)?.relationships
      : item.value?.__typename === 'Collection'
        ? (item.value as any)?.languageVariant?.metadataRelationships || (item.value as any)?.metadataRelationships || []
        : []

    templateAttributes.value = item.value?.__typename === 'Metadata'
      ? ((item.value as any)?.document?.template?.documentTemplate?.attributes || (item.value as any)?.data?.template?.dataTemplate?.attributes || (item.value as any)?.guide?.template?.documentTemplate?.attributes)
      : item.value?.__typename === 'Collection'
        ? (item.value as any)?.templateMetadata?.collectionTemplate?.attributes
        : []
  }

  const reload = () => {
    cachedYDoc = null
    ready.value = false
    refreshState()
    initYDoc()
  }

  watch(item, (value, oldValue) => {
    if (!value || !oldValue) return
    if (value.id !== oldValue.id) {
      reload()
      updateAttributes()
    }
  })

  return {
    ydoc,
    ready,
    attributes,
    parentCollections,
    relationships,
    rawAttributes,
    reloadParentCollections: () => {
      parentCollections.value = (item.value as any)?.parentCollections || []
    },
    reloadRelationships: () => {
      relationships.value = item.value?.__typename === 'Metadata'
        ? (item.value as any)?.relationships
        : item.value?.__typename === 'Collection'
          ? (item.value as any)?.metadataRelationships
          : []
    },
    refreshState,
    reload
  }
}
