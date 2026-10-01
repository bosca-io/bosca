import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import Button from './Button.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountButton(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(Button, {
    props,
    slots,
    global: { stubs: { Icon: IconStub } },
  })
}

describe('Button', () => {
  it('emits click with MouseEvent when clicked', async () => {
    const w = mountButton({}, { default: 'Go' })
    await w.trigger('click')
    expect(w.emitted('click')).toHaveLength(1)
    expect(w.emitted('click')![0][0]).toBeInstanceOf(MouseEvent)
  })

  it('does not emit click when disabled', async () => {
    const w = mountButton({ disabled: true }, { default: 'Go' })
    await w.trigger('click')
    expect(w.emitted('click')).toBeUndefined()
  })

  it('sets the disabled attribute on the button element', () => {
    const w = mountButton({ disabled: true })
    expect(w.attributes('disabled')).toBeDefined()
  })

  it('adds btn--disabled class when disabled', () => {
    const w = mountButton({ disabled: true })
    expect(w.classes()).toContain('btn--disabled')
  })

  it('renders Icon when icon prop is provided', () => {
    const w = mountButton({ icon: 'plus' })
    expect(w.findComponent(IconStub).exists()).toBe(true)
    expect(w.findComponent(IconStub).props('name')).toBe('plus')
  })

  it('does not render Icon when icon prop is absent', () => {
    const w = mountButton()
    expect(w.findComponent(IconStub).exists()).toBe(false)
  })

  it('size sm applies smaller padding', () => {
    const w = mountButton({ size: 'sm' })
    expect(w.attributes('style')).toContain('padding: 6px 10px')
  })

  it('size md applies default padding', () => {
    const w = mountButton({ size: 'md' })
    expect(w.attributes('style')).toContain('padding: 8px 14px')
  })

  it('renders slot content', () => {
    const w = mountButton({}, { default: 'Save' })
    expect(w.text()).toContain('Save')
  })
})
