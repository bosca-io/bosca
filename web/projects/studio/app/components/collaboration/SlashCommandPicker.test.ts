import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SlashCommandPicker from './SlashCommandPicker.vue'

describe('SlashCommandPicker', () => {
  it('renders commands when visible', () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/', visible: true },
    })
    const items = wrapper.findAll('.slash-item')
    expect(items.length).toBe(4)
  })

  it('hides when not visible', () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/', visible: false },
    })
    expect(wrapper.find('.slash-picker').exists()).toBe(false)
  })

  it('filters commands by query', () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/kit search', visible: true },
    })
    const items = wrapper.findAll('.slash-item')
    expect(items.length).toBe(1)
    expect(items[0]!.find('.slash-cmd').text()).toBe('/kit search')
  })

  it('emits select on click', async () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/', visible: true },
    })
    await wrapper.find('.slash-item').trigger('click')
    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]![0]).toBe('/kit summarize')
  })

  it('shows command descriptions', () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/', visible: true },
    })
    const descs = wrapper.findAll('.slash-desc')
    expect(descs[0]!.text()).toBe('Summarize recent conversation')
  })

  it('shows args hint when command has args', () => {
    const wrapper = mount(SlashCommandPicker, {
      props: { query: '/kit translate', visible: true },
    })
    const args = wrapper.find('.slash-args')
    expect(args.text()).toBe('<language>')
  })
})
