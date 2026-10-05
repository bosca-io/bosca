import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GuideStepModulesModal from './GuideStepModulesModal.vue'

const stubs = {
  Icon: { template: '<i />' },
  Button: {
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled', 'icon', 'primary', 'size'],
    emits: ['click'],
  },
  Select: {
    template: '<select class="select" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
    props: ['modelValue', 'options', 'placeholder', 'size'],
    emits: ['update:modelValue'],
  },
  Teleport: true,
}

const baseModules = [
  { id: 1, metadata: { id: 'm1', name: 'Scripture' } },
  { id: 2, metadata: { id: 'm2', name: 'Reflection' } },
  { id: 3, metadata: { id: 'm3', name: 'Prayer' } },
]

const templateModules = [
  { id: 10, name: 'Scripture Module' },
  { id: 11, name: 'Reflection Module' },
]

function mountModal(props: Record<string, unknown> = {}) {
  return mount(GuideStepModulesModal, {
    props: {
      stepName: 'Day 1',
      modules: baseModules,
      templateModules,
      ...props,
    },
    global: { stubs },
  })
}

describe('GuideStepModulesModal', () => {
  it('renders all modules with names', () => {
    const wrapper = mountModal()
    const items = wrapper.findAll('.modules-item')
    expect(items).toHaveLength(3)
    expect(wrapper.text()).toContain('Scripture')
    expect(wrapper.text()).toContain('Reflection')
    expect(wrapper.text()).toContain('Prayer')
  })

  it('shows the step name in the header', () => {
    const wrapper = mountModal()
    expect(wrapper.find('.modules-subtitle').text()).toBe('Day 1')
  })

  it('shows an empty message when the step has no modules', () => {
    const wrapper = mountModal({ modules: [] })
    expect(wrapper.find('.modules-empty').exists()).toBe(true)
  })

  it('hides the add bar when the template offers no modules', () => {
    const wrapper = mountModal({ templateModules: [] })
    expect(wrapper.find('.modules-add').exists()).toBe(false)
  })

  it('emits add with the numeric template module id', async () => {
    const wrapper = mountModal()
    await wrapper.find('select').setValue('11')
    const addButton = wrapper.find('.modules-add button')
    await addButton.trigger('click')
    expect(wrapper.emitted('add')).toEqual([[11]])
  })

  it('emits delete with the module id', async () => {
    const wrapper = mountModal()
    await wrapper.findAll('.module-delete')[1]!.trigger('click')
    expect(wrapper.emitted('delete')).toEqual([[2]])
  })

  it('emits close from the footer button', async () => {
    const wrapper = mountModal()
    const buttons = wrapper.findAll('.modules-footer button')
    await buttons[0]!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits save with reordered module ids after a drag', async () => {
    const wrapper = mountModal()
    const items = wrapper.findAll('.modules-item')

    const dataTransfer = { effectAllowed: '', setData: () => {} }
    await items[0]!.trigger('dragstart', { dataTransfer })
    await items[2]!.trigger('dragover')
    await wrapper.find('.modules-body').trigger('drop')

    const saveButton = wrapper.findAll('.modules-footer button')[1]!
    await saveButton.trigger('click')
    expect(wrapper.emitted('save')).toEqual([[[2, 3, 1]]])
  })

  it('disables save until the order changes', () => {
    const wrapper = mountModal()
    const saveButton = wrapper.findAll('.modules-footer button')[1]!
    expect(saveButton.attributes('disabled')).toBeDefined()
  })

  it('hides the save button with fewer than two modules', () => {
    const wrapper = mountModal({ modules: [baseModules[0]] })
    expect(wrapper.findAll('.modules-footer button')).toHaveLength(1)
  })

  it('syncs the local list when the modules prop refreshes', async () => {
    const wrapper = mountModal()
    await wrapper.setProps({ modules: [baseModules[2]!, baseModules[0]!] })
    const items = wrapper.findAll('.module-name')
    expect(items.map(i => i.text())).toEqual(['Prayer', 'Scripture'])
  })
})
