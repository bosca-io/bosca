import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import { defineComponent } from 'vue'
import TextInput from './TextInput.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountInput(props: Record<string, unknown> = {}) {
  return mount(TextInput, {
    props,
    global: { stubs: { Icon: IconStub } },
  })
}

describe('TextInput', () => {
  it('binds v-model value to input', () => {
    const w = mountInput({ modelValue: 'hello' })
    expect((w.find('input').element as HTMLInputElement).value).toBe('hello')
  })

  it('emits update:modelValue on input', async () => {
    const w = mountInput({ modelValue: '' })
    await w.find('input').setValue('test')
    expect(w.emitted('update:modelValue')).toBeTruthy()
    const emitted = w.emitted('update:modelValue')!
    expect(emitted[emitted.length - 1]).toEqual(['test'])
  })

  it('renders label when provided', () => {
    const w = mountInput({ label: 'Username' })
    expect(w.find('.text-input-label').text()).toBe('Username')
  })

  it('hides label when not provided', () => {
    const w = mountInput()
    expect(w.find('.text-input-label').exists()).toBe(false)
  })

  it('applies disabled state', () => {
    const w = mountInput({ disabled: true })
    expect(w.find('.text-input-wrap').classes()).toContain('disabled')
    expect((w.find('input').element as HTMLInputElement).disabled).toBe(true)
  })

  it('sets placeholder', () => {
    const w = mountInput({ placeholder: 'Enter text...' })
    expect(w.find('input').attributes('placeholder')).toBe('Enter text...')
  })

  it('emits blur event', async () => {
    const w = mountInput()
    await w.find('input').trigger('blur')
    expect(w.emitted('blur')).toHaveLength(1)
  })

  it('applies size-sm class for small size', () => {
    const w = mountInput({ size: 'sm' })
    expect(w.find('.text-input-root').classes()).toContain('size-sm')
  })

  it('applies size-md class by default', () => {
    const w = mountInput()
    expect(w.find('.text-input-root').classes()).toContain('size-md')
  })

  it('adds mono class when mono is true', () => {
    const w = mountInput({ mono: true })
    expect(w.find('input').classes()).toContain('mono')
  })

  it('defaults to type=text', () => {
    const w = mountInput()
    expect(w.find('input').attributes('type')).toBe('text')
  })

  it('applies the type attribute when provided', () => {
    const w = mountInput({ type: 'password' })
    expect(w.find('input').attributes('type')).toBe('password')
  })

  it('applies the minimum attribute for number inputs', () => {
    const w = mountInput({ type: 'number', min: 60 })
    expect(w.find('input').attributes('min')).toBe('60')
  })
})
