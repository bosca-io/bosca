import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import MoveProjectModal from './MoveProjectModal.vue'

interface MovableProject {
  id: string
  name: string
  program: { id: string; key: string; name: string } | null
  version: number
}

const currentProject: MovableProject = {
  id: 'project-1',
  name: 'GraphQL',
  program: { id: 'program-1', key: 'CORE', name: 'Core' },
  version: 4,
}

const programs = [
  { id: 'program-1', key: 'CORE', name: 'Core', archivedAt: null },
  { id: 'program-2', key: 'PLATFORM', name: 'Platform', archivedAt: null },
  { id: 'program-3', key: 'OLD', name: 'Old', archivedAt: '2026-07-01T00:00:00Z' },
]

const movedProject: MovableProject = {
  ...currentProject,
  program: { id: 'program-2', key: 'PLATFORM', name: 'Platform' },
  version: 5,
}

const mockMutation = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  mutation: mockMutation,
  useAsyncQuery: () => ({
    data: ref({ workOps: { programs: { all: programs } } }),
    status: ref('success'),
  }),
}))

const SelectStub = defineComponent({
  name: 'Select',
  props: {
    modelValue: String,
    options: Array,
    label: String,
    placeholder: String,
    loading: Boolean,
    disabled: Boolean,
    accent: String,
  },
  emits: ['update:modelValue'],
  template: '<select class="mock-select" />',
})

const ButtonStub = defineComponent({
  name: 'Button',
  props: {
    disabled: Boolean,
    primary: Boolean,
    accent: String,
  },
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
})

function mountModal() {
  return mount(MoveProjectModal, {
    props: { project: currentProject, accent: '#a78bff' },
    global: {
      stubs: {
        Modal: {
          template: '<div class="mock-modal"><slot /><slot name="footer" /></div>',
          props: ['title', 'icon', 'accent'],
        },
        Select: SelectStub,
        Button: ButtonStub,
      },
    },
  })
}

describe('MoveProjectModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockMutation.mockResolvedValue({
      workOps: { projects: { move: movedProject } },
    })
  })

  it('offers only other active programs', () => {
    const wrapper = mountModal()

    expect(wrapper.getComponent(SelectStub).props('options')).toEqual([
      { value: 'program-2', label: 'PLATFORM — Platform' },
    ])
    expect(wrapper.text()).toContain('Core')
  })

  it('moves the project with its current version and emits the result', async () => {
    const wrapper = mountModal()
    wrapper.getComponent(SelectStub).vm.$emit('update:modelValue', 'program-2')
    await wrapper.vm.$nextTick()

    const moveButton = wrapper.findAll('button').find(button => button.text() === 'Move Project')
    expect(moveButton).toBeDefined()
    await moveButton!.trigger('click')
    await flushPromises()

    expect(mockMutation).toHaveBeenCalledOnce()
    expect(mockMutation.mock.calls[0]?.[1]).toEqual({
      id: 'project-1',
      programId: 'program-2',
      expectedVersion: 4,
    })
    expect(wrapper.emitted('moved')).toEqual([[movedProject]])
  })

  it('surfaces move failures and keeps the modal open', async () => {
    mockMutation.mockRejectedValue(new Error('Project changed; reload and try again.'))
    const wrapper = mountModal()
    wrapper.getComponent(SelectStub).vm.$emit('update:modelValue', 'program-2')
    await wrapper.vm.$nextTick()

    await wrapper.findAll('button').find(button => button.text() === 'Move Project')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Project changed; reload and try again.')
    expect(wrapper.emitted('moved')).toBeUndefined()
  })
})
