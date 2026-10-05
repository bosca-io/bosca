import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GuideStepList from './GuideStepList.vue'

const stubs = {
  Icon: { template: '<i />' },
  ConfirmModal: { template: '<div class="confirm-modal"><slot /></div>', emits: ['close', 'confirm'] },
}

const baseSteps = [
  { id: 1, date: null, metadata: { id: 'm1', name: 'Introduction' }, modules: [] },
  { id: 2, date: '2026-06-15T00:00:00Z', metadata: { id: 'm2', name: 'Chapter 1' }, modules: [] },
  { id: 3, date: null, metadata: { id: 'm3', name: 'Conclusion' }, modules: [] },
]

const templateSteps = [
  { id: 10, metadata: { id: 't1', name: 'Template Step A' } },
  { id: 11, metadata: { id: 't2', name: 'Template Step B' } },
]

describe('GuideStepList', () => {
  it('renders all steps', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    const items = wrapper.findAll('.step-item')
    expect(items).toHaveLength(3)
  })

  it('shows step names', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Introduction')
    expect(wrapper.text()).toContain('Chapter 1')
    expect(wrapper.text()).toContain('Conclusion')
  })

  it('highlights selected step', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 1 },
      global: { stubs },
    })
    const items = wrapper.findAll('.step-item')
    expect(items[1]!.classes()).toContain('active')
  })

  it('emits select on step click', async () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    await wrapper.findAll('.step-item')[2]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[2]])
  })

  it('shows step count', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.step-count').text()).toBe('3')
  })

  it('shows add button when template steps exist', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps, selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.step-add-btn').exists()).toBe(true)
  })

  it('hides add button when no template steps', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.step-add-btn').exists()).toBe(false)
  })

  it('hides add button when disabled', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps, selectedIndex: 0, disabled: true },
      global: { stubs },
    })
    expect(wrapper.find('.step-add-btn').exists()).toBe(false)
  })

  it('shows empty state when no steps', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: [], templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.step-empty').text()).toContain('No steps')
  })

  it('shows step numbers', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    const numbers = wrapper.findAll('.step-number')
    expect(numbers[0]!.text()).toBe('1')
    expect(numbers[1]!.text()).toBe('2')
    expect(numbers[2]!.text()).toBe('3')
  })

  it('shows date for calendar guide steps', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0, guideType: 'CALENDAR' },
      global: { stubs },
    })
    expect(wrapper.find('.step-date').exists()).toBe(true)
  })

  it('hides date for linear guide steps', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0, guideType: 'LINEAR' },
      global: { stubs },
    })
    expect(wrapper.find('.step-date').exists()).toBe(false)
  })

  it('shows delete button on hover', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.findAll('.step-delete-btn').length).toBe(3)
  })

  it('hides delete button when disabled', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0, disabled: true },
      global: { stubs },
    })
    expect(wrapper.findAll('.step-delete-btn').length).toBe(0)
  })

  it('has drag handles', () => {
    const wrapper = mount(GuideStepList, {
      props: { steps: baseSteps, templateSteps: [], selectedIndex: 0 },
      global: { stubs },
    })
    expect(wrapper.findAll('.drag-handle').length).toBe(3)
  })
})
