/* eslint-disable @typescript-eslint/no-explicit-any */
import * as Y from 'yjs'
import { toRaw } from 'vue'

export type IntrospectionJSON = {
  guid: string
  clientID: number
  gc: boolean
  sharedTypes: { [key: string]: any }
}

export function toIntrospectionJSON(ydoc: Y.Doc | null | undefined): IntrospectionJSON {
  if (!ydoc) return { guid: '', clientID: 0, gc: false, sharedTypes: {} }
  const doc = toRaw(ydoc)
  return {
    guid: doc.guid,
    clientID: doc.clientID,
    gc: doc.gc,
    sharedTypes: doc.toJSON()
  }
}

export function applyIntrospectionJSON(ydoc: Y.Doc, edited: Partial<IntrospectionJSON>): void {
  if (!edited || !edited.sharedTypes) return
  const doc = toRaw(ydoc)
  doc.transact(() => {
    let changed = false
    // eslint-disable-next-line @typescript-eslint/ban-ts-comment
    // @ts-expect-error
    for (const [name, value] of Object.entries(edited.sharedTypes)) {
      let type = (doc.share as any).get(name)
      if (!type) {
        if (Array.isArray(value)) {
          type = doc.getArray(name)
        } else if (typeof value === 'object' && value !== null) {
          type = doc.getMap(name)
        } else if (typeof value === 'string') {
          type = doc.getText(name)
        }
      }
      if (type) {
        applyValueToType(type as any, value)
        changed = true
      }
    }
    if (changed) {
      try {
        const changes = doc.getText('changes') as any
        if (typeof changes.setAttribute === 'function') {
          changes.setAttribute('changes', 'true')
        } else {
          // Fallback if it's a standard Y.Text and for some reason the project expects this
          // though based on project code it should have setAttribute
          changes.insert(0, '') // Trigger an update at least
        }
      } catch (e) {
        console.warn('Failed to set changes flag', e)
      }
    }
  }, 'introspection-edit')
}

export function applyValueToType(target: any, json: any): void {
  const t = toRaw(target)
  if (t instanceof Y.Map) {
    applyToYMap(t as Y.Map<any>, json)
  } else if (t instanceof Y.Array) {
    applyToYArray(t as Y.Array<any>, json)
  } else if (t instanceof Y.Text) {
    applyToYText(t as Y.Text, json)
  }
}

function applyToYMap(map: Y.Map<any>, json: any) {
  if (typeof json !== 'object' || json === null || Array.isArray(json)) return

  // Remove keys not present in json
  for (const key of Array.from(map.keys())) {
    if (!(key in json)) {
      map.delete(key)
    }
  }

  for (const [key, val] of Object.entries(json)) {
    const existing = map.get(key)
    if (existing instanceof Y.AbstractType) {
      // deep-apply to existing shared type
      applyValueToType(existing, val)
    } else {
      const updated = convertJsonToYValue(existing, val)
      if (existing !== updated) {
        map.set(key, updated)
      }
    }
  }
}

function applyToYArray(arr: Y.Array<any>, json: any) {
  if (!Array.isArray(json)) return
  const newItems = json.map(v => convertJsonToYValue(undefined, v))
  // Replace whole content for simplicity and determinism
  if (arr.length > 0) {
    arr.delete(0, arr.length)
  }
  if (newItems.length > 0) {
    arr.insert(0, newItems)
  }
}

function applyToYText(text: Y.Text, json: any) {
  if (typeof json !== 'string') return
  const current = text.toString()
  if (current !== json) {
    if (current.length > 0) text.delete(0, current.length)
    if (json.length > 0) text.insert(0, json)
  }
}

function convertJsonToYValue(existing: any, json: any): any {
  if (json instanceof Y.AbstractType) return json

  if (Array.isArray(json)) {
    if (existing instanceof Y.Array) {
      applyToYArray(existing, json)
      return existing
    }
    const yarr = new Y.Array()
    applyToYArray(yarr, json)
    return yarr
  }

  if (typeof json === 'object' && json !== null) {
    if (existing instanceof Y.Map) {
      applyToYMap(existing, json)
      return existing
    }
    const ymap = new Y.Map()
    applyToYMap(ymap, json)
    return ymap
  }

  // primitives (number, string, boolean, null)
  return json
}
