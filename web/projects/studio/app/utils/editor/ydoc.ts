/* eslint-disable @typescript-eslint/no-explicit-any */
import * as Y from 'yjs'
import { Editor } from '@tiptap/core'
import { prosemirrorJSONToYXmlFragment } from 'y-prosemirror'
import type { Collection, CollectionLanguageVariantMetadataRelationship, CollectionMetadataRelationship, Metadata, MetadataRelationship, Profile } from '~/types/graphql'
import { newExtensions } from '~/utils/editor/extensions'
import { executeYDocRequest } from '~/utils/editor/api'
import { AttributeState } from '~/utils/editor/attribute'
import { sanitizeDocument } from '~/utils/editor/document'

/**
 * YDocs that haven't finished their initial Hocuspocus sync yet.
 * Updates from the provider during sync should not mark the document dirty.
 */
const initializingDocs = new Set<Y.Doc>()
const readyCallbacks = new Map<Y.Doc, Array<() => void>>()
const DIRTY_KEY = '|__dirty__|'
const SAVE_ORIGIN = 'save-document'
const DIRTY_ORIGIN = 'update-document-changes'
const EDITOR_REVISIONS = 'editorRevisions'

/**
 * Returns true when a Yjs transaction changed editor-owned content.
 *
 * The `changes` and `saved` texts are bookkeeping shared between clients. A
 * remote client receiving a save transaction must not interpret that clean
 * marker as a new edit and immediately change it back to dirty. Likewise, the
 * backend uses `|__dirty__|` map entries to request a re-seed from persisted
 * attributes, collections, or relationships; those markers are not edits.
 */
function hasEditorChanges(ydoc: Y.Doc, transaction: Y.Transaction): boolean {
  const changes = ydoc.getText('changes')
  const saved = ydoc.getText('saved')
  const editorRevisions = ydoc.getMap(EDITOR_REVISIONS)
  const reseedMaps = new Set<unknown>([
    ydoc.getMap('attrs'),
    ydoc.getMap('collections'),
    ydoc.getMap('metadatas'),
  ])

  for (const [type, keys] of transaction.changed) {
    const changedType: unknown = type
    if (changedType === changes || changedType === saved || changedType === editorRevisions) continue
    if (reseedMaps.has(changedType) && keys.size > 0 && [...keys].every(key => key === DIRTY_KEY)) {
      continue
    }
    return true
  }
  return false
}

function changesAttribute(ydoc: Y.Doc, transaction: Y.Transaction, name: 'changes' | 'saved'): boolean {
  const text = ydoc.getText(name)
  for (const [type, keys] of transaction.changed) {
    if ((type as unknown) === text && keys.has(name)) return true
  }
  return false
}

type EditorRevision = Record<string, number>

function editorRevision(ydoc: Y.Doc): EditorRevision {
  return Object.fromEntries(
    [...ydoc.getMap<number>(EDITOR_REVISIONS).entries()]
      .filter((entry): entry is [string, number] => typeof entry[1] === 'number')
      .sort(([left], [right]) => left.localeCompare(right))
  )
}

function parseSavedRevision(ydoc: Y.Doc): EditorRevision | null {
  const encoded = ydoc.getText('saved').getAttribute('saved')
  if (typeof encoded !== 'string') return null
  try {
    const parsed: unknown = JSON.parse(encoded)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
    const revision: EditorRevision = {}
    for (const [client, value] of Object.entries(parsed)) {
      if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return null
      revision[client] = value
    }
    return revision
  } catch {
    return null
  }
}

function hasRevisionAfter(current: EditorRevision, saved: EditorRevision): boolean {
  return Object.entries(current).some(([client, revision]) => revision > (saved[client] ?? 0))
}

function setDirtyState(ydoc: Y.Doc, dirty: boolean) {
  const changes = ydoc.getText('changes')
  const value = dirty ? 'true' : 'false'
  if (changes.getAttribute('changes') === value) return
  ydoc.transact((transaction) => {
    transaction.doc.getText('changes').setAttribute('changes', value)
  }, DIRTY_ORIGIN)
}

/** Captures the editor revision represented by the current collaborative document. */
export function captureYDocRevision(ydoc: Y.Doc): string {
  return JSON.stringify(editorRevision(ydoc))
}

/**
 * Marks the exact captured editor revision as persisted. Edits that occur after [revision] was
 * captured remain dirty, even if the save completes later or on another collaborator.
 */
export function markYDocSaved(ydoc: Y.Doc, revision: string = captureYDocRevision(ydoc)) {
  ydoc.transact((transaction) => {
    transaction.doc.getText('saved').setAttribute('saved', revision)
    transaction.doc.getText('changes').setAttribute('changes', 'false')
  }, SAVE_ORIGIN)
}

/**
 * Installs dirty-state tracking on a collaborative editor document.
 */
export function trackYDocChanges(ydoc: Y.Doc) {
  // Register bookkeeping root types before provider updates arrive so Yjs includes their attribute
  // changes in Transaction.changed even when a full document update creates them.
  ydoc.getText('changes')
  ydoc.getText('saved')
  ydoc.getMap(EDITOR_REVISIONS)

  ydoc.on('afterTransaction', (transaction) => {
    if (transaction.origin === DIRTY_ORIGIN || initializingDocs.has(ydoc)) {
      return
    }

    const editorChanged = hasEditorChanges(ydoc, transaction)
    const savedChanged = changesAttribute(ydoc, transaction, 'saved')
    const cleanMarkerChanged = changesAttribute(ydoc, transaction, 'changes')
      && ydoc.getText('changes').getAttribute('changes') !== 'true'

    if (editorChanged && transaction.local && transaction.origin !== SAVE_ORIGIN) {
      ydoc.transact((innerTransaction) => {
        const revisions = innerTransaction.doc.getMap<number>(EDITOR_REVISIONS)
        const client = innerTransaction.doc.clientID.toString()
        revisions.set(client, (revisions.get(client) ?? 0) + 1)
        innerTransaction.doc.getText('changes').setAttribute('changes', 'true')
      }, DIRTY_ORIGIN)
      return
    }

    const savedRevision = parseSavedRevision(ydoc)
    if (savedChanged && savedRevision) {
      // The saved revision is causal evidence. It works whether the provider delivers the edit
      // and save separately or coalesces both into a single Yjs update.
      setDirtyState(ydoc, hasRevisionAfter(editorRevision(ydoc), savedRevision))
      return
    }

    if (editorChanged) {
      setDirtyState(ydoc, true)
      return
    }

    // A legacy or malformed clean marker cannot prove that it contains locally observed edits.
    if (cleanMarkerChanged && savedRevision) {
      setDirtyState(ydoc, hasRevisionAfter(editorRevision(ydoc), savedRevision))
    }
  })
}

/**
 * Checks whether the given YDoc is still in its initialization phase.
 */
export function isYDocInitializing(ydoc: Y.Doc): boolean {
  return initializingDocs.has(ydoc)
}

/**
 * Registers a callback that fires once when the YDoc becomes ready.
 * If the YDoc is already ready, the callback fires immediately.
 */
export function onYDocReady(ydoc: Y.Doc, callback: () => void) {
  if (!initializingDocs.has(ydoc)) {
    callback()
    return
  }
  let callbacks = readyCallbacks.get(ydoc)
  if (!callbacks) {
    callbacks = []
    readyCallbacks.set(ydoc, callbacks)
  }
  callbacks.push(callback)
}

/**
 * Marks the YDoc as fully initialized. Preserves the dirty flag from the
 * server so unsaved changes survive a page refresh, but prevents sync-only
 * updates from incorrectly marking the document as dirty.
 */
export function markYDocReady(ydoc: Y.Doc) {
  if (!initializingDocs.delete(ydoc)) return
  const callbacks = readyCallbacks.get(ydoc)
  if (callbacks) {
    readyCallbacks.delete(ydoc)
    callbacks.forEach(cb => cb())
  }
}

export async function loadYDoc(item: Metadata | Collection, token: string) {
  const response = await executeYDocRequest(
    item.__typename === 'Metadata'
      ? item.data
        ? `/api/v1/content/metadata/${item.id}/data/collaboration?version=${item.version}`
        : `/api/v1/content/metadata/${item.id}/document/collaboration?version=${item.version}`
      // @ts-expect-error: this is ok
      : `/api/v1/content/collection/${item.id}/collaboration?languageTag=${item.languageVariant?.languageTag || item.languageTag}`,
    'GET',
    token
  )
  if (response && response.length > 0) {
    const doc = new Y.Doc()
    Y.applyUpdate(doc, response)
    return doc
  }
  return null
}

export async function newYDoc(
  item: Metadata | Collection,
  attributes: Ref<{ [key: string]: any }>,
  parentCollections: Ref<Collection[]>,
  relationships: Ref<(CollectionMetadataRelationship | CollectionLanguageVariantMetadataRelationship | MetadataRelationship)[]>,
  profile: Profile,
  token: string,
  onError: (_error: unknown) => void
) {
  let ydoc: Y.Doc | null = null
  try {
    try {
      ydoc = await loadYDoc(item, token)
    } catch (e) {
      ydoc = new Y.Doc()
      throw e
    }
    if (!ydoc && item.__typename === 'Metadata' && item.document?.content) {
      const extensions = newExtensions(
        item,
        profile
      )
      const editor = new Editor({ extensions: extensions })
      const content = sanitizeDocument(item.document?.content?.document || { type: 'doc', content: [] })
      ydoc = new Y.Doc()
      prosemirrorJSONToYXmlFragment(editor.schema, content, ydoc.getXmlFragment('default'))

      for (const attr of item.document?.template?.documentTemplate?.attributes || []) {
        const a = new AttributeState(ydoc, item.__typename, item.id, attributes, parentCollections, relationships, attr)
        a.reset()
      }

      await executeYDocRequest(
        `/api/v1/content/metadata/${item.id}/document/collaboration?version=${item.version}`,
        'PUT',
        token,
        Y.encodeStateAsUpdate(ydoc)
      )
    } else if (!ydoc && item.__typename === 'Metadata' && item.data) {
      ydoc = new Y.Doc()

      for (const attr of item.data.template?.dataTemplate?.attributes || []) {
        const a = new AttributeState(ydoc, item.__typename, item.id, attributes, parentCollections, relationships, attr)
        a.reset()
      }

      await executeYDocRequest(
        `/api/v1/content/metadata/${item.id}/data/collaboration?version=${item.version}`,
        'PUT',
        token,
        Y.encodeStateAsUpdate(ydoc)
      )
    } else if (!ydoc && item.__typename === 'Collection') {
      ydoc = new Y.Doc()

      for (const attr of item.templateMetadata?.collectionTemplate?.attributes || []) {
        const a = new AttributeState(ydoc, item.__typename, item.id, attributes, parentCollections, relationships, attr)
        a.reset()
      }

      await executeYDocRequest(
        `/api/v1/content/collection/${item.id}/collaboration?languageTag=${item.languageVariant?.languageTag || item.languageTag}`,
        'PUT',
        token,
        Y.encodeStateAsUpdate(ydoc)
      )
    } else if (!ydoc) {
      ydoc = new Y.Doc()

      await executeYDocRequest(
        item.__typename === 'Metadata'
          ? item.data
            ? `/api/v1/content/metadata/${item.id}/data/collaboration?version=${item.version}`
            : `/api/v1/content/metadata/${item.id}/document/collaboration?version=${item.version}`
          : `/api/v1/content/collection/${item.id}/collaboration?languageTag=${(item as any).languageVariant?.languageTag || item.languageTag}`,
        'PUT',
        token,
        Y.encodeStateAsUpdate(ydoc)
      )
    }
  } catch (e: any) {
    console.error('error processing y.doc', e)
    onError({
      title: 'Error getting document, please refresh the page: ' + e.message,
      icon: 'i-lucide-triangle-alert',
      duration: 60000,
      color: 'error'
    })
    ydoc = new Y.Doc()
  }
  initializingDocs.add(ydoc)
  trackYDocChanges(ydoc)
  return ydoc
}
