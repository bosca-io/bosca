/* eslint-disable @typescript-eslint/no-explicit-any */
import * as Y from 'yjs'
import { AttributeState } from '~/utils/editor/attribute'
import { AttributeLocation, type TemplateAttribute } from '~/types/graphql'
import type { TimeEventTypeAttribute } from '~/composables/useTimeEvents'

function toTemplateAttribute(attr: TimeEventTypeAttribute): TemplateAttribute {
  return {
    __typename: 'TemplateAttribute',
    key: attr.key,
    name: attr.name,
    description: attr.description,
    type: attr.type as any,
    ui: attr.ui as any,
    list: attr.list,
    location: AttributeLocation.Item,
    configuration: attr.configuration,
    supplementaryKey: attr.supplementaryKey,
    tools: null,
    workflows: null,
  }
}

export function useTimeEventAttributes(
  typeAttributes: Ref<TimeEventTypeAttribute[]>,
  currentValues: Ref<Record<string, any>>,
) {
  let ydoc = new Y.Doc()
  const attributes = ref(new Map<string, AttributeState>())
  const rawAttributes = ref<Record<string, any>>({})
  const parentCollections = ref<any[]>([])
  const relationships = ref<any[]>([])

  function rebuild() {
    const prev = attributes.value
    for (const attr of prev.values()) {
      attr.dispose()
    }
    ydoc.destroy()
    ydoc = new Y.Doc()

    const map = new Map<string, AttributeState>()
    rawAttributes.value = { ...currentValues.value }

    for (const typeAttr of typeAttributes.value) {
      const templateAttr = toTemplateAttribute(typeAttr)
      const state = new AttributeState(
        ydoc,
        'TimeEvent',
        '',
        rawAttributes,
        parentCollections,
        relationships,
        templateAttr,
      )
      state.reset()

      const rawValue = currentValues.value[typeAttr.key]
      if (rawValue != null) {
        if (typeAttr.type === 'METADATA') {
          const relationship = typeAttr.configuration?.relationship || ''
          if (typeAttr.list && Array.isArray(rawValue)) {
            state.metadatas = rawValue.map((entry: any) => {
              const id = typeof entry === 'string' ? entry : entry.id
              const attrs = typeof entry === 'object' ? (entry.attributes ?? {}) : {}
              return { id, relationship, contentType: '', attributes: attrs, name: id }
            })
          } else {
            const id = typeof rawValue === 'string' ? rawValue : rawValue.id
            const attrs = typeof rawValue === 'object' ? (rawValue.attributes ?? {}) : {}
            if (id) {
              state.metadata = { id, relationship, contentType: '', attributes: attrs, name: id }
            }
          }
        } else if (typeAttr.type === 'COLLECTION') {
          if (typeAttr.list && Array.isArray(rawValue)) {
            state.collections = rawValue.map((id: string) => ({ id, name: id }))
          } else if (!typeAttr.list && typeof rawValue === 'string') {
            state.collection = { id: rawValue, name: rawValue }
          }
        }
      }

      map.set(typeAttr.key, state)
    }

    attributes.value = map
  }

  let prevTypes = ''
  let prevVals = ''
  watch(
    [typeAttributes, currentValues],
    () => {
      const newTypes = JSON.stringify(typeAttributes.value)
      const newVals = JSON.stringify(currentValues.value)
      if (newTypes !== prevTypes || newVals !== prevVals) {
        prevTypes = newTypes
        prevVals = newVals
        rebuild()
      }
    },
    { immediate: true },
  )

  function extractAttributes(): Record<string, any> {
    const result: Record<string, any> = {}
    for (const [key, attr] of attributes.value) {
      switch (attr.type) {
        case 'DATE':
        case 'DATETIME':
        case 'DATE_TIME':
          result[key] = attr.dateTimeValueRaw ?? null
          break
        case 'FLOAT':
        case 'INT':
          result[key] = attr.numberValue ?? null
          break
        case 'METADATA':
          if (attr.list) {
            result[key] = (attr.metadatas || []).map((m: any) => ({
              id: m.id,
              attributes: m.attributes ?? {},
            }))
          } else {
            const meta = attr.metadata
            result[key] = meta ? { id: meta.id, attributes: meta.attributes ?? {} } : null
          }
          break
        case 'COLLECTION':
          if (attr.list) {
            result[key] = (attr.collections || []).map((c: any) => c.id)
          } else {
            result[key] = attr.collection?.id ?? null
          }
          break
        case 'STRING':
        default:
          result[key] = attr.textValue ?? null
          break
      }
    }
    return result
  }

  onUnmounted(() => {
    for (const attr of attributes.value.values()) {
      attr.dispose()
    }
    ydoc.destroy()
  })

  return {
    attributes,
    rawAttributes,
    parentCollections,
    relationships,
    extractAttributes,
  }
}
