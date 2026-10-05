import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import {
  captureYDocRevision,
  markYDocSaved,
  trackYDocChanges,
} from './ydoc'

function isDirty(doc: Y.Doc): boolean {
  return doc.getText('changes').getAttribute('changes') === 'true'
}

describe('trackYDocChanges', () => {
  it('marks local editor content changes dirty', () => {
    const doc = new Y.Doc()
    trackYDocChanges(doc)

    doc.getMap('textAttributes').set('summary', 'Changed')

    expect(isDirty(doc)).toBe(true)
  })

  it('applies a causally matching remote save', () => {
    const savingClient = new Y.Doc()
    const remoteClient = new Y.Doc()
    trackYDocChanges(savingClient)
    trackYDocChanges(remoteClient)

    savingClient.getMap('textAttributes').set('summary', 'Changed')
    Y.applyUpdate(remoteClient, Y.encodeStateAsUpdate(savingClient), 'remote-provider')
    expect(isDirty(remoteClient)).toBe(true)

    const remoteState = Y.encodeStateVector(remoteClient)
    markYDocSaved(savingClient)
    Y.applyUpdate(remoteClient, Y.encodeStateAsUpdate(savingClient, remoteState), 'remote-provider')

    expect(isDirty(savingClient)).toBe(false)
    expect(isDirty(remoteClient)).toBe(false)
  })

  it('keeps a concurrent local edit dirty when another client saves without it', () => {
    const editingClient = new Y.Doc()
    const savingClient = new Y.Doc()
    trackYDocChanges(editingClient)
    trackYDocChanges(savingClient)

    editingClient.getMap('textAttributes').set('summary', 'Unsaved on editing client')
    const editingState = Y.encodeStateVector(editingClient)

    markYDocSaved(savingClient)
    Y.applyUpdate(
      editingClient,
      Y.encodeStateAsUpdate(savingClient, editingState),
      'remote-provider',
    )

    expect(isDirty(editingClient)).toBe(true)
  })

  it('keeps an edit made while a save is in flight dirty', () => {
    const doc = new Y.Doc()
    trackYDocChanges(doc)
    doc.getMap('textAttributes').set('summary', 'Included in save')
    const savedRevision = captureYDocRevision(doc)

    doc.getMap('textAttributes').set('summary', 'Changed during save')
    markYDocSaved(doc, savedRevision)

    expect(isDirty(doc)).toBe(true)
  })

  it('accepts an edit and its matching save when they arrive in one update', () => {
    const savingClient = new Y.Doc()
    trackYDocChanges(savingClient)
    savingClient.getMap('textAttributes').set('summary', 'Saved')
    markYDocSaved(savingClient)

    const remoteClient = new Y.Doc()
    trackYDocChanges(remoteClient)
    Y.applyUpdate(remoteClient, Y.encodeStateAsUpdate(savingClient), 'remote-provider')

    expect(isDirty(remoteClient)).toBe(false)
  })

  it('ignores backend re-seed markers', () => {
    const doc = new Y.Doc()
    trackYDocChanges(doc)

    doc.getMap('attrs').set('|__dirty__|', 'true')
    doc.getMap('collections').set('|__dirty__|', 'true')
    doc.getMap('metadatas').set('|__dirty__|', 'true')

    expect(isDirty(doc)).toBe(false)
  })

  it('keeps real collection and relationship changes dirty', () => {
    const doc = new Y.Doc()
    trackYDocChanges(doc)

    doc.getMap('collections').set('series', '[{"id":"collection-1"}]')
    doc.getMap('metadatas').set('author', '[{"id":"metadata-1"}]')

    expect(isDirty(doc)).toBe(true)
  })

  it('marks the current revision clean after a successful save', () => {
    const doc = new Y.Doc()
    trackYDocChanges(doc)
    doc.getMap('textAttributes').set('summary', 'Changed')

    markYDocSaved(doc)

    expect(isDirty(doc)).toBe(false)
  })
})
