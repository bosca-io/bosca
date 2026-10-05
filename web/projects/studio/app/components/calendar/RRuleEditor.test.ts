import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import RRuleEditor from './RRuleEditor.vue'

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
  Switch: {
    template: '<button class="mock-switch" @click="$emit(\'update:modelValue\', !modelValue)" />',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
}

describe('RRuleEditor', () => {
  it('renders disabled state when no value', () => {
    const wrapper = mount(RRuleEditor, { props: { modelValue: null }, global: { stubs } })
    expect(wrapper.find('.rrule-toggle').exists()).toBe(true)
    expect(wrapper.find('.mock-switch').exists()).toBe(true)
  })

  it('renders enabled state when value provided', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=WEEKLY;BYDAY=MO,WE' },
      global: { stubs },
    })
    expect(wrapper.find('.mock-switch').exists()).toBe(true)
  })

  it('shows frequency selector when enabled', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    expect(wrapper.find('.rrule-select').exists()).toBe(true)
  })

  it('shows day buttons for WEEKLY frequency', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=WEEKLY;BYDAY=MO' },
      global: { stubs },
    })
    const dayBtns = wrapper.findAll('.day-btn')
    expect(dayBtns.length).toBe(7)
    expect(dayBtns[0]!.classes()).toContain('day-btn--active')
  })

  it('hides day buttons for non-WEEKLY frequency', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    expect(wrapper.findAll('.day-btn').length).toBe(0)
  })

  it('shows simple and advanced mode toggle', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    const modeBtns = wrapper.findAll('.mode-btn')
    expect(modeBtns.length).toBe(2)
    expect(modeBtns[0]!.text()).toBe('Simple')
    expect(modeBtns[1]!.text()).toBe('Advanced')
  })

  it('shows raw RRULE input in advanced mode', async () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    await wrapper.findAll('.mode-btn')[1]!.trigger('click')
    expect(wrapper.find('.rrule-input--mono').exists()).toBe(true)
  })

  it('shows end condition selector', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    const selects = wrapper.findAll('.rrule-select')
    const endSelect = selects.find((s) => {
      const options = s.findAll('option')
      return options.some((o) => o.text() === 'Never')
    })
    expect(endSelect).toBeDefined()
  })

  it('emits null when toggled off', async () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY' },
      global: { stubs },
    })
    await wrapper.find('.mock-switch').trigger('click')
    // The Switch emits update:modelValue which toggles enabled
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    const last = wrapper.emitted('update:modelValue')!
    expect(last[last.length - 1]![0]).toBeNull()
  })

  it('shows interval input', () => {
    const wrapper = mount(RRuleEditor, {
      props: { modelValue: 'FREQ=DAILY;INTERVAL=3' },
      global: { stubs },
    })
    const numInput = wrapper.find('.rrule-input--sm')
    expect((numInput.element as HTMLInputElement).value).toBe('3')
  })
})
