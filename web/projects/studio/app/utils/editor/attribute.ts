/* eslint-disable @typescript-eslint/no-explicit-any */
import type * as Y from 'yjs'
import type { Collection, CollectionLanguageVariantMetadataRelationship, CollectionMetadataRelationship, MetadataRelationship, TemplateAttribute } from '~/types/graphql'

export type AttributeCollection = { id: string, name: string }
export type AttributeMetadata = { id: string, relationship: string, contentType: string, attributes: any, name: string }

export class AttributeState {
  readonly ydoc: Y.Doc
  readonly typename: string
  readonly contentId: string
  readonly attributes: Ref<{ [key: string]: any }>
  readonly parentCollections: Ref<Collection[]>
  readonly relationships: Ref<(CollectionMetadataRelationship | CollectionLanguageVariantMetadataRelationship | MetadataRelationship)[]>
  readonly attribute: TemplateAttribute
  readonly changeRef = ref(0)
  readonly loading = ref(false)
  readonly hasValueRef = ref(false)

  private _nested = false
  private _listeners: (() => void)[] = []
  private _observers: { map: Y.Map<any>, observer: (_event: any) => void }[] = []

  invalidValue: boolean
  valueWarning: string | null

  constructor(
    ydoc: Y.Doc,
    typename: string,
    contentId: string,
    attributes: Ref<{ [key: string]: any }>,
    parentCollections: Ref<Collection[]>,
    relationships: Ref<(CollectionMetadataRelationship | CollectionLanguageVariantMetadataRelationship | MetadataRelationship)[]>,
    attribute: TemplateAttribute
  ) {
    this.ydoc = ydoc
    this.typename = typename
    this.contentId = contentId
    this.parentCollections = parentCollections
    this.relationships = relationships
    this.attribute = attribute
    this.attributes = attributes
    this.invalidValue = false
    this.valueWarning = null

    this.updateHasValue()

    const doc = toRaw(this.ydoc)
    const update = () => {
      this.changeRef.value++
      this.updateHasValue()
      for (const l of this._listeners) l()
    }
    const observer = (event: any) => {
      if (event.keysChanged.has(this.key)) {
        update()
      }
    }
    const maps = this.getRelevantMaps()
    for (const mapName of maps) {
      const map = doc.getMap(mapName)
      map.observe(observer)
      this._observers.push({ map, observer })
    }
  }

  dispose() {
    for (const { map, observer } of this._observers) {
      map.unobserve(observer)
    }
    this._observers = []
    this._listeners = []
  }

  private getRelevantMaps(): string[] {
    const type = this.type
    switch (type) {
      case 'STRING':
        return ['textAttributes']
      case 'FLOAT':
      case 'INT':
        return ['numberAttributes']
      case 'DATE':
      case 'DATETIME':
      case 'DATE_TIME':
        return ['dateAttributes']
      case 'METADATA':
        return [this.list ? 'metadatas' : 'metadata']
      case 'COLLECTION':
        return [this.list ? 'collections' : 'collection']
      default:
        return [
          'textAttributes',
          'numberAttributes',
          'dateAttributes',
          'metadatas',
          'metadata',
          'collections',
          'collection'
        ]
    }
  }

  addListener(listener: () => void) {
    this._listeners.push(listener)
  }

  removeListener(listener: () => void) {
    this._listeners = this._listeners.filter(l => l !== listener)
  }

  get rawAttributes() {
    return this.attributes
  }

  get nested() {
    return this._nested
  }

  get key() {
    return this.attribute.key
  }

  get name() {
    return this.attribute.name
  }

  get description() {
    return this.attribute.description
  }

  get tools() {
    return this.attribute.tools || []
  }

  get hasTools() {
    return this.tools.length > 0
  }

  get configuration() {
    return this.attribute.configuration || {}
  }

  get type() {
    return this.attribute.type
  }

  get ui() {
    return this.attribute.ui
  }

  get location() {
    return this.attribute.location
  }

  get list() {
    return this.attribute.list
  }

  get numberValue() {
    // trigger reactivity
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    const value = ydoc.getMap('numberAttributes').get(this.key)
    if (value === undefined || value === null) return null
    if (typeof value === 'number') return value
    return parseFloat(value.toString())
  }

  set numberValue(value: any) {
    if (this.numberValue === value) return
    const ydoc = toRaw(this.ydoc)
    const map = ydoc.getMap('numberAttributes')
    if (value === null || value === undefined || value === '') {
      if (!map.has(this.key)) return
      map.delete(this.key)
    } else {
      const n = parseFloat(value?.toString() || '')
      if (isNaN(n)) {
        if (!map.has(this.key)) {
          return
        }
        map.delete(this.key)
      } else {
        if (map.get(this.key) === n) return
        map.set(this.key, n)
      }
    }
  }

  get dateTimeValueRaw() {
    // trigger reactivity
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    return ydoc.getMap('dateAttributes').get(this.key) as number | null | undefined
  }

  get dateTimeValue() {
    const value = this.dateTimeValueRaw
    if (!value || value === 0) return ''
    const valueNumber = parseInt(value?.toString() || '')
    if (!isNaN(valueNumber)) {
      return new Date(valueNumber).toLocaleDateString('en-US', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: true
      }).replace(',', '')
    }
    return value?.toString() || ''
  }

  set dateTimeValue(value: any) {
    if (this.dateTimeValue === value) return
    if ((value === null || value === undefined || value === '') && this.dateTimeValue === '') return
    this.forceSetDateTimeValue(value)
  }

  forceSetDateTimeValue(value: any) {
    const ydoc = toRaw(this.ydoc)
    const map = ydoc.getMap('dateAttributes')
    if (value === null || value === undefined || value === '') {
      if (!map.has(this.key)) return
      map.delete(this.key)
      return
    }
    try {
      if (typeof value === 'number') {
        if (map.get(this.key) === value) return
        map.set(this.key, value)
        return
      }
      const date = new Date(Date.parse(value))
      if (isNaN(date.getTime())) throw new Error('invalid date')
      if (map.get(this.key) === date.getTime()) return
      map.set(this.key, date.getTime())
      this.invalidValue = false
      if (date.getTime() < new Date().getTime()) {
        this.valueWarning = 'Date is in the past.'
      } else {
        this.valueWarning = null
      }
      return
    } catch (e) {
      console.error(e)
      this.invalidValue = true
      this.valueWarning = null
    }
    if (value) {
      if (map.get(this.key) === value.toString()) return
      map.set(this.key, value.toString())
    } else {
      if (map.has(this.key)) {
        map.delete(this.key)
      }
    }
  }

  get textValue() {
    // Access changeRef to establish Vue reactivity tracking.
    // The Y.Doc observer increments this ref when the key changes,
    // causing any computed/watcher reading textValue to re-evaluate.
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    return ydoc.getMap('textAttributes').get(this.key)?.toString() || ''
  }

  set textValue(value: string) {
    if (this.type === 'FLOAT' || this.type === 'INT') {
      this.numberValue = value
    } else {
      if (this.textValue === value) return
      const ydoc = toRaw(this.ydoc)
      const map = ydoc.getMap('textAttributes')
      if (value) {
        if (map.get(this.key) === value) return
        map.set(this.key, value.toString())
      } else {
        if (!map.has(this.key)) return
        map.delete(this.key)
      }
    }
  }

  updateHasValue() {
    const value = this.dateTimeValueRaw || this.textValue || this.metadatas.length > 0 || this.collections.length > 0 || this.collection || this.metadata
    this.hasValueRef.value = value !== null && value !== undefined && value !== ''
  }

  get hasValue() {
    return this.hasValueRef.value
  }

  reset(): void {
    const attributes: { [key: string]: any } = {}
    const attr = toRaw(this.attributes.value)
    if (attr) {
      for (const key of Object.keys(attr)) {
        attributes[key] = toRaw(attr[key])
      }
    }
    switch (this.type) {
      case 'STRING':
        this.textValue = this.findValue(this.key, attributes) || ''
        break
      case 'FLOAT':
      case 'INT':
        this.numberValue = this.findValue(this.key, attributes) || null
        break
      case 'DATE':
      case 'DATETIME':
      case 'DATE_TIME':
        this.dateTimeValue = this.findValue(this.key, attributes) || null
        break
      case 'COLLECTION': {
        if (this.parentCollections) {
          // Re-seeding matches parents by their `attributes.type` marker, but
          // a collection picked through the attribute dropdown may not carry
          // it. If a current value is still one of the item's parent
          // collections the connection is real — keep it rather than clearing.
          const stillParent = (id: string) => this.parentCollections.value.some(c => c.id === id)
          if (this.list) {
            const cols: AttributeCollection[] = []
            for (const c of this.parentCollections.value) {
              if ((c.attributes as any)?.type === (this.attribute.configuration as any)?.type) {
                cols.push({
                  id: c.id,
                  name: c.name
                })
              }
            }
            for (const cur of this.collections) {
              if (stillParent(cur.id) && !cols.some(c => c.id === cur.id)) {
                cols.push(cur)
              }
            }
            this.collections = cols
          } else {
            const col = this.parentCollections.value.find(c => (c.attributes as any)?.type === (this.attribute.configuration as any)?.type)
            if (col) {
              this.collection = {
                id: col.id,
                name: col.name
              }
            } else {
              const cur = this.collection
              if (!cur || !stillParent(cur.id)) {
                this.collection = null
              }
            }
          }
        } else {
          if (this.list) {
            this.collections = []
          } else {
            this.collection = null
          }
        }
        break
      }
      case 'METADATA': {
        if (this.relationships.value) {
          const relationship = (this.attribute.configuration as any)?.relationship
          if (this.list) {
            const metas: AttributeMetadata[] = []
            for (const r of this.relationships.value) {
              if (r.relationship === relationship) {
                metas.push({
                  id: r.metadata.id,
                  relationship: (r.relationship as string) || 'unknown',
                  contentType: (r.metadata as any).content?.type || 'text/plain',
                  attributes: r.attributes as any,
                  name: r.metadata.name
                })
              }
            }
            this.metadatas = metas
          } else {
            const meta = this.relationships.value.find(c => c.relationship === relationship)
            if (meta) {
              this.metadata = {
                id: meta.metadata.id,
                relationship: (meta.relationship as string) || 'unknown',
                contentType: (meta.metadata as any).content?.type || 'text/plain',
                attributes: meta.attributes as any,
                name: meta.metadata.name
              }
            } else {
              this.metadata = null
            }
          }
        } else {
          if (this.list) {
            this.metadatas = []
          } else {
            this.metadata = null
          }
        }
        break
      }
      case 'PROFILE': {
        // TODO
        break
      }
    }
  }

  clearInvalidValue() {
    this.invalidValue = false
    this.valueWarning = null
  }

  get metadatas(): AttributeMetadata[] {
    // trigger reactivity
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    const metadatas = ydoc.getMap('metadatas').get(this.key)
    if (!metadatas) return []
    return JSON.parse(metadatas as string) as AttributeMetadata[]
  }

  set metadatas(value: AttributeMetadata[]) {
    const doc = toRaw(this.ydoc)
    const map = doc.getMap('metadatas')
    if (!value || value.length === 0) {
      if (!map.has(this.key)) return
      map.delete(this.key)
      return
    }
    const key = this.key
    const rawValues: any[] = []
    for (const metadata of value) {
      const v = toRaw(metadata)
      if (v) {
        rawValues.push(v)
      }
    }
    if (rawValues.length === 0) {
      if (map.has(key)) {
        map.delete(key)
      }
    } else {
      const value = JSON.stringify(rawValues)
      if (map.get(key) === value) return
      map.set(key, value)
    }
  }

  get metadata(): AttributeMetadata | null {
    // trigger reactivity
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    const attrs = ydoc.getMap('metadata').get(this.key)
    if (!attrs) return null
    return JSON.parse(attrs.toString()) as AttributeMetadata | null
  }

  set metadata(value: AttributeMetadata | null) {
    const doc = toRaw(this.ydoc)
    const map = doc.getMap('metadata')
    if (!value) {
      if (!map.has(this.key)) return
      map.delete(this.key)
      return
    }
    const key = this.key
    const rawValue = toRaw(value)
    if (rawValue) {
      const value = JSON.stringify(rawValue)
      if (map.get(key) === value) return
      map.set(key, value)
    } else {
      if (map.has(key)) {
        map.delete(key)
      } else {
        return
      }
    }
  }

  get collections(): { id: string, name: string }[] {
    // trigger reactivity
    void this.changeRef.value
    const ydoc = toRaw(this.ydoc)
    const collections = ydoc.getMap('collections').get(this.key)
    if (!collections) return []
    return JSON.parse(collections as string) as { id: string, name: string }[]
  }

  set collections(value: { id: string, name: string }[]) {
    const doc = toRaw(this.ydoc)
    const map = doc.getMap('collections')
    if (!value || value.length === 0) {
      if (!map.has(this.key)) return
      map.delete(this.key)
      return
    }
    const key = this.key
    const rawValues: any[] = []
    for (const collection of value) {
      const v = toRaw(collection)
      if (v) {
        rawValues.push(v)
      }
    }
    if (rawValues.length === 0) {
      if (map.has(key)) {
        map.delete(key)
      }
    } else {
      const value = JSON.stringify(rawValues)
      if (map.get(key) === value) return
      map.set(key, value)
    }
  }

  get collection(): AttributeCollection | null {
    const ydoc = toRaw(this.ydoc)
    const attrs = ydoc.getMap('collection').get(this.key)
    if (!attrs) return null
    return JSON.parse(attrs.toString()) as AttributeCollection | null
  }

  set collection(value: AttributeCollection | null) {
    const doc = toRaw(this.ydoc)
    const map = doc.getMap('collection')
    if (!value) {
      if (!map.has(this.key)) return
      map.delete(this.key)
      return
    }
    const key = this.key
    const rawValue = toRaw(value)
    if (rawValue) {
      const value = JSON.stringify(rawValue)
      if (map.get(key) === value) return
      map.set(key, value)
    } else {
      if (map.has(key)) {
        map.delete(key)
      } else {
        return
      }
    }
  }

  private findValue(key: string, attributes: any | null | undefined): any {
    if (attributes) {
      if (attributes[key]) return attributes[key]
      if (key.indexOf('.') > 0) {
        const keys = key.split('.')
        const value = this.findPath(keys, 0, attributes)
        if (value) {
          this._nested = true
        }
        return value
      }
    }
    return null
  }

  private findPath(keys: string[], depth: number, attributes: any): any {
    if (!attributes) return null
    const key = keys[depth]
    if (!key) return null
    const value = attributes[key]
    if (depth === keys.length - 1) {
      return value
    }
    return this.findPath(keys, depth + 1, value)
  }
}
