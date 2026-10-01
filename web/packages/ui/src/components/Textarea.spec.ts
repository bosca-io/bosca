import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import Textarea from './Textarea.vue'

function mountTextarea(props: Record<string, unknown> = {}) {
  return mount(Textarea, { props })
}

describe('Textarea', () => {
  it('binds v-model value', () => {
    const w = mountTextarea({ modelValue: 'hello world' })
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('hello world')
  })

  it('emits update:modelValue on input', async () => {
    const w = mountTextarea({ modelValue: '' })
    await w.find('textarea').setValue('new text')
    const emitted = w.emitted('update:modelValue')!
    expect(emitted[emitted.length - 1]).toEqual(['new text'])
  })

  it('renders label when provided', () => {
    const w = mountTextarea({ label: 'Description' })
    expect(w.find('.textarea-label').text()).toBe('Description')
  })

  it('hides label when not provided', () => {
    const w = mountTextarea()
    expect(w.find('.textarea-label').exists()).toBe(false)
  })

  it('applies disabled attribute', () => {
    const w = mountTextarea({ disabled: true })
    expect((w.find('textarea').element as HTMLTextAreaElement).disabled).toBe(true)
  })

  it('sets rows attribute from prop', () => {
    const w = mountTextarea({ rows: 5 })
    expect(w.find('textarea').attributes('rows')).toBe('5')
  })

  it('defaults rows to 3', () => {
    const w = mountTextarea()
    expect(w.find('textarea').attributes('rows')).toBe('3')
  })

  it('adds mono class when mono is true', () => {
    const w = mountTextarea({ mono: true })
    expect(w.find('textarea').classes()).toContain('mono')
  })

  it('does not have mono class by default', () => {
    const w = mountTextarea()
    expect(w.find('textarea').classes()).not.toContain('mono')
  })

  it('emits blur event', async () => {
    const w = mountTextarea()
    await w.find('textarea').trigger('blur')
    expect(w.emitted('blur')).toHaveLength(1)
  })
})
