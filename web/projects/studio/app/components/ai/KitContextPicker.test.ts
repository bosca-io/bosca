import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import KitContextPicker from './KitContextPicker.vue'

const Select = { props: ['options', 'modelValue'], emits: ['update:modelValue'],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="">Choose</option><option v-for="option in options" :value="option.value">{{ option.label }}</option></select>' }
const stubs = { Select, Modal: { template: '<div><slot /></div>' }, Button: { template: '<button><slot /></button>' }, ListPager: true }

describe('Context source picker', () => {
  it('browses bounded task and requirement pages using visible names', async () => {
    const query = vi.fn(async (document: { definitions: { name: { value: string } }[] }, _variables?: unknown) => {
      const name = document.definitions[0]!.name.value
      if (name === 'ContextProjects') return { workOps: { projects: { all: [{ id: 'project-id', name: 'Guides', archivedAt: null }] } } }
      if (name === 'ContextTasks') return { workOps: { tasks: { byProject: [{ id: 'task-id', summary: 'Write the guide' }] } } }
      if (name === 'ContextSpecs') return { workOps: { specs: { byProject: [{ id: 'spec-id', metadata: { name: 'Guide requirements' } }, { id: 'hidden', metadata: null }] } } }
      return { workOps: { requirements: { bySpec: [{ id: 'requirement-id', metadata: { name: 'Include examples' } }] } } }
    })
    vi.stubGlobal('useGraphQL', () => ({ query }))
    const wrapper = mount(KitContextPicker, { global: { stubs } })
    await flushPromises()
    await wrapper.findAll('select')[0]!.setValue('project-id')
    await flushPromises()
    expect(query.mock.calls[1]![1]).toEqual({ project: 'project-id', offset: 0, limit: 20 })
    expect(wrapper.text()).toContain('Write the guide')
    expect(wrapper.text()).not.toContain('task-id')
    await wrapper.findAll('button').find(button => button.text() === 'Write the guide')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[{ kind: 'TASK', id: 'task-id' }]])
    await wrapper.findAll('select')[1]!.setValue('REQUIREMENT')
    await flushPromises()
    expect(wrapper.text()).not.toContain('hidden')
    await wrapper.findAll('button').find(button => button.text() === 'Guide requirements')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Include examples')
    expect(wrapper.text()).not.toContain('requirement-id')
    await wrapper.findAll('button').find(button => button.text() === 'Include examples')!.trigger('click')
    expect(wrapper.emitted('select')![1]).toEqual([{ kind: 'REQUIREMENT', id: 'requirement-id' }])
    wrapper.unmount()
  })

  it('uses the existing read-only Git file picker without loading WorkOps catalogs', async () => {
    const query = vi.fn()
    vi.stubGlobal('useGraphQL', () => ({ query }))
    const GitFilePicker = { template: '<button @click="$emit(\'select\', { repositoryId: \'repository\', ref: \'main\', path: \'docs/plan.md\' })">Choose file</button>' }
    const wrapper = mount(KitContextPicker, { props: { git: true }, global: { stubs: { ...stubs, GitFilePicker } } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')).toEqual([[{ kind: 'GIT_FILE', id: 'repository', ref: 'main', path: 'docs/plan.md' }]])
    expect(query).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
