import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GuideStepDetail from './GuideStepDetail.vue'

const stubs = {
  Icon: { template: '<i />' },
  Button: { template: '<button><slot /></button>', props: ['size', 'icon'] },
  ConfirmModal: { template: '<div class="confirm-modal" />', emits: ['close', 'confirm'] },
}

const baseModules = [
  { id: 1, metadata: { id: 'mod1', name: 'Module A' } },
  { id: 2, metadata: { id: 'mod2', name: 'Module B' } },
]

const templateModules = [
  { id: 10, metadata: { id: 'tm1', name: 'Template Module 1' } },
]

describe('GuideStepDetail', () => {
  it('renders step name', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Introduction', stepDate: null, stepMetadataId: 'm1',
        modules: [], templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Introduction')
  })

  it('shows date for calendar guides', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Day 1', stepDate: '2026-06-15T00:00:00Z', stepMetadataId: 'm1',
        modules: [], templateModules: [], isCalendar: true,
      },
      global: { stubs },
    })
    const labels = wrapper.findAll('.field-label')
    const dateLabel = labels.find(l => l.text() === 'Date')
    expect(dateLabel).toBeTruthy()
  })

  it('hides date for non-calendar guides', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: '2026-06-15T00:00:00Z', stepMetadataId: 'm1',
        modules: [], templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    const labels = wrapper.findAll('.field-label')
    const dateLabel = labels.find(l => l.text() === 'Date')
    expect(dateLabel).toBeUndefined()
  })

  it('renders modules list', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: baseModules, templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.findAll('.module-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('Module A')
    expect(wrapper.text()).toContain('Module B')
  })

  it('shows module count', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: baseModules, templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.find('.module-count').text()).toBe('2')
  })

  it('shows empty state when no modules', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: [], templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.find('.module-empty').text()).toContain('No modules')
  })

  it('shows add module button when template modules exist', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: [], templateModules, isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.find('.module-add-btn').exists()).toBe(true)
  })

  it('hides add module button when disabled', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: [], templateModules, isCalendar: false, disabled: true,
      },
      global: { stubs },
    })
    expect(wrapper.find('.module-add-btn').exists()).toBe(false)
  })

  it('shows Open Content button when metadata exists', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: [], templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Open Content')
  })

  it('has drag handles on modules', () => {
    const wrapper = mount(GuideStepDetail, {
      props: {
        stepId: 1, stepName: 'Step 1', stepDate: null, stepMetadataId: 'm1',
        modules: baseModules, templateModules: [], isCalendar: false,
      },
      global: { stubs },
    })
    expect(wrapper.findAll('.drag-handle').length).toBe(2)
  })
})
