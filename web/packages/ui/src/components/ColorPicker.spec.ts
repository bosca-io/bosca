import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import ColorPicker from './ColorPicker.vue'

function mountPicker(props: Record<string, unknown> = {}) {
  return mount(ColorPicker, { props })
}

describe('ColorPicker', () => {
  it('renders color preview', () => {
    const w = mountPicker({ modelValue: '#ff0000' })
    expect(w.find('.color-preview').exists()).toBe(true)
  })

  it('renders text input for hex', () => {
    const w = mountPicker({ modelValue: '#3b82f6' })
    const input = w.find('.color-input')
    expect(input.exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('#3b82f6')
  })

  it('clicking preset changes model', async () => {
    const w = mountPicker({ modelValue: '#3b82f6' })
    const swatches = w.findAll('.color-swatch')
    expect(swatches.length).toBeGreaterThan(0)
    await swatches[0].trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
  })

  it('renders label when provided', () => {
    const w = mountPicker({ label: 'Brand Color' })
    expect(w.find('.color-picker-label').text()).toBe('Brand Color')
  })

  it('hides label when not provided', () => {
    const w = mountPicker()
    expect(w.find('.color-picker-label').exists()).toBe(false)
  })

  it('applies disabled state', () => {
    const w = mountPicker({ disabled: true })
    expect(w.find('.color-picker-root').classes()).toContain('disabled')
  })

  it('renders default presets', () => {
    const w = mountPicker()
    const swatches = w.findAll('.color-swatch')
    expect(swatches).toHaveLength(10)
  })

  it('renders custom presets', () => {
    const w = mountPicker({ presets: ['#000', '#fff'] })
    const swatches = w.findAll('.color-swatch')
    expect(swatches).toHaveLength(2)
  })
})
