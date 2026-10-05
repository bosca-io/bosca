import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ScriptureRenderer from './ScriptureRenderer.vue'

describe('ScriptureRenderer', () => {
  it('renders serialized character-style containers inline', () => {
    const wrapper = mount(ScriptureRenderer, {
      props: {
        nodes: [{
          _: 'cc',
          type: 'PARAGRAPH',
          style: { _: 'sr', id: 'p' },
          components: [
            { _: 't', text: 'sin ', style: { _: 'sr', id: 'verse' } },
            {
              _: 'cc',
              type: 'DIV',
              style: { _: 'sr', id: 'bd' },
              components: [{ _: 't', text: '[[', style: { _: 'sr', id: 'verse' } }],
            },
            {
              _: 'cc',
              type: 'DIV',
              style: { _: 'sr', id: 'it' },
              components: [{ _: 't', text: 'or', style: { _: 'sr', id: 'verse' } }],
            },
            { _: 't', text: ' a sin-offering', style: { _: 'sr', id: 'verse' } },
            {
              _: 'cc',
              type: 'DIV',
              style: { _: 'sr', id: 'bd' },
              components: [{ _: 't', text: ']]', style: { _: 'sr', id: 'verse' } }],
            },
            { _: 't', text: ' is lying', style: { _: 'sr', id: 'verse' } },
          ],
        }],
        styles: new Map(),
        accent: '#ff7ac6',
      },
    })

    expect(wrapper.text()).toBe('sin [[or a sin-offering]] is lying')
    expect(wrapper.findAll('.sc-style-bd')).toHaveLength(2)
    expect(wrapper.get('.sc-style-it').element.tagName).toBe('SPAN')
    expect(wrapper.findAll('.sc-style-bd').every(node => node.element.tagName === 'SPAN')).toBe(true)
  })

  it('omits extended footnotes from the scripture text', () => {
    const wrapper = mount(ScriptureRenderer, {
      props: {
        nodes: [{
          _: 'cc',
          type: 'PARAGRAPH',
          style: { _: 'sr', id: 'p' },
          components: [
            { _: 't', text: 'sin ', style: { _: 'sr', id: 'verse' } },
            {
              _: 'cc',
              type: 'DIV',
              style: { _: 'sr', id: 'ef' },
              components: [
                { _: 't', text: '[[', style: { _: 'sr', id: 'verse' } },
                { _: 't', text: 'or a sin-offering', style: { _: 'sr', id: 'ft' } },
                { _: 't', text: ']]', style: { _: 'sr', id: 'verse' } },
              ],
            },
            { _: 't', text: 'is lying', style: { _: 'sr', id: 'verse' } },
          ],
        }],
        styles: new Map(),
        accent: '#ff7ac6',
      },
    })

    expect(wrapper.text()).toBe('sin is lying')
    expect(wrapper.text()).not.toContain('[[')
    expect(wrapper.text()).not.toContain('or a sin-offering')
  })
})
