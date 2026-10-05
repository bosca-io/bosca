import { describe, expect, it } from 'vitest'
import { mergeAttributes } from '@tiptap/core'
import { DOMSerializer, Schema } from '@tiptap/pm/model'

describe('Tiptap attribute security', () => {
  it('keeps JSON-origin prototype attributes out of serialized DOM', () => {
    const input = JSON.parse('{"__proto__":{"src":"invalid://image","onerror":"alert(1)","data-inherited":"secret"}}')
    const attributes = mergeAttributes(input, { class: 'image' })
    expect(Object.getPrototypeOf(attributes)).toBe(Object.prototype)
    expect(attributes.onerror).toBeUndefined()
    const schema = new Schema({
      nodes: {
        doc: { content: 'image' },
        image: { toDOM: () => ['img', attributes] },
        text: {}
      }
    })
    const doc = schema.node('doc', null, [schema.node('image')])
    const image = DOMSerializer.fromSchema(schema).serializeFragment(doc.content).firstChild as HTMLElement
    expect(image.hasAttribute('onerror')).toBe(false)
    expect(image.hasAttribute('src')).toBe(false)
    expect(image.hasAttribute('data-inherited')).toBe(false)
    expect(image.className).toBe('image')
  })
})
