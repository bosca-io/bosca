import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Tabs from './Tabs.vue'

const tabs = ['Overview', 'Details', 'Settings']

function mountTabs(props: Record<string, unknown> = {}) {
  return shallowMount(Tabs, {
    props: { tabs, modelValue: 'Overview', ...props },
  })
}

describe('Tabs', () => {
  it('renders all tab buttons', () => {
    const w = mountTabs()
    const buttons = w.findAll('.tab')
    expect(buttons).toHaveLength(3)
    expect(buttons[0].text()).toBe('Overview')
    expect(buttons[1].text()).toBe('Details')
    expect(buttons[2].text()).toBe('Settings')
  })

  it('marks the active tab with the "active" class', () => {
    const w = mountTabs({ modelValue: 'Details' })
    const buttons = w.findAll('.tab')
    expect(buttons[0].classes()).not.toContain('active')
    expect(buttons[1].classes()).toContain('active')
    expect(buttons[2].classes()).not.toContain('active')
  })

  it('applies active styling to the selected tab', () => {
    const w = mountTabs({ modelValue: 'Overview', accent: '#ff0000' })
    const active = w.findAll('.tab').find(t => t.classes().includes('active'))!
    const style = active.attributes('style')
    expect(style).toContain('border-bottom-color: #ff0000')
    expect(style).toContain('font-weight: 500')
  })

  it('emits update:modelValue when a tab is clicked', async () => {
    const w = mountTabs({ modelValue: 'Overview' })
    await w.findAll('.tab')[2].trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual(['Settings'])
  })

  it('does not apply active style to inactive tabs', () => {
    const w = mountTabs({ modelValue: 'Overview' })
    const inactive = w.findAll('.tab').filter(t => !t.classes().includes('active'))
    inactive.forEach(t => {
      const style = t.attributes('style') || ''
      expect(style).not.toContain('font-weight: 500')
    })
  })
})
