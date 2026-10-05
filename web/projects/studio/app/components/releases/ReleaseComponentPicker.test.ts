import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import ReleaseComponentPicker from './ReleaseComponentPicker.vue'

const projects = [
  { id: 'project-1', key: 'WEB', name: 'Web' },
]
const existingVersion = { id: 'version-1', name: '1.0.0', released: true, sequenceNumber: 1 }
const createdVersion = { id: 'version-2', name: '1.1.0', released: false, sequenceNumber: 2 }

const projectsData = ref({ workOps: { projects: { byProgram: projects } } })
const versionsData = ref({ workOps: { versions: { byProject: [existingVersion] } } })
const refreshVersions = vi.fn()
const mockMutation = vi.fn()
const toastSuccess = vi.fn()
const toastWarn = vi.fn()

vi.stubGlobal('useCurrentSubsystem', () => ({ accent: ref('#5ec5ff') }))
vi.stubGlobal('useToast', () => ({ success: toastSuccess, warn: toastWarn }))
vi.stubGlobal('useGraphQL', () => ({
  mutation: mockMutation,
  useAsyncQuery: (key: string) => key === 'release-picker-projects'
    ? { data: projectsData, status: ref('success'), refresh: vi.fn() }
    : { data: versionsData, status: ref('success'), refresh: refreshVersions },
}))

const SelectStub = defineComponent({
  name: 'Select',
  props: {
    modelValue: String,
    options: Array,
    placeholder: String,
    size: String,
    accent: String,
  },
  emits: ['update:modelValue'],
  template: '<select />',
})

const ButtonStub = defineComponent({
  name: 'Button',
  props: {
    disabled: Boolean,
    primary: Boolean,
    accent: String,
    icon: String,
    size: String,
  },
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
})

const TextInputStub = defineComponent({
  name: 'TextInput',
  props: {
    modelValue: String,
    label: String,
    placeholder: String,
    type: String,
    autofocus: Boolean,
  },
  emits: ['update:modelValue'],
  template: '<input :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})

const ModalStub = defineComponent({
  name: 'Modal',
  props: {
    title: String,
    subtitle: String,
    icon: String,
    accent: String,
  },
  emits: ['close'],
  template: '<div class="mock-modal"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></div>',
})

function mountPicker() {
  return mount(ReleaseComponentPicker, {
    props: { programId: 'program-1' },
    global: {
      stubs: {
        Select: SelectStub,
        Button: ButtonStub,
        TextInput: TextInputStub,
        Modal: ModalStub,
      },
    },
  })
}

function button(wrapper: ReturnType<typeof mountPicker>, label: string) {
  const found = wrapper.findAll('button').find(candidate => candidate.text() === label)
  if (!found) throw new Error(`Button not found: ${label}`)
  return found
}

describe('ReleaseComponentPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    versionsData.value = { workOps: { versions: { byProject: [existingVersion] } } }
    mockMutation.mockResolvedValue({ workOps: { versions: { create: createdVersion } } })
    refreshVersions.mockImplementation(async () => {
      versionsData.value = { workOps: { versions: { byProject: [createdVersion, existingVersion] } } }
    })
  })

  it('creates, selects, and adds a version for the chosen project', async () => {
    const wrapper = mountPicker()
    const selects = wrapper.findAllComponents(SelectStub)
    selects[0]!.vm.$emit('update:modelValue', 'project-1')
    await wrapper.vm.$nextTick()

    await button(wrapper, 'New version').trigger('click')
    expect(wrapper.get('.mock-modal').text()).toContain('New WEB version')

    await wrapper.get('input[aria-label="Name"]').setValue(' 1.1.0 ')
    await wrapper.get('input[aria-label="Description"]').setValue('Next release')
    await wrapper.get('input[aria-label="Start date"]').setValue('2026-08-04')
    await wrapper.get('input[aria-label="Release date"]').setValue('2026-08-11')
    await button(wrapper, 'Create').trigger('click')
    await flushPromises()

    expect(mockMutation).toHaveBeenCalledOnce()
    expect(mockMutation.mock.calls[0]?.[1]).toEqual({
      input: {
        projectId: 'project-1',
        name: '1.1.0',
        description: 'Next release',
        startDate: '2026-08-04T00:00:00.000Z',
        releaseDate: '2026-08-11T00:00:00.000Z',
      },
    })
    expect(refreshVersions).toHaveBeenCalledOnce()
    expect(toastSuccess).toHaveBeenCalledWith('1.1.0 created')

    const versionSelect = wrapper.findAllComponents(SelectStub)[1]!
    expect(versionSelect.props('modelValue')).toBe('version-2')
    expect(versionSelect.props('options')).toEqual([
      { value: 'version-2', label: '1.1.0' },
      { value: 'version-1', label: '1.0.0 · released' },
    ])

    await button(wrapper, 'Add').trigger('click')
    expect(wrapper.emitted('add')).toEqual([[
      { projectId: 'project-1', projectKey: 'WEB', versionId: 'version-2', versionName: '1.1.0' },
    ]])
  })

  it('keeps a created version selectable when refreshing the list fails', async () => {
    refreshVersions.mockRejectedValue(new Error('offline'))
    const wrapper = mountPicker()
    wrapper.findAllComponents(SelectStub)[0]!.vm.$emit('update:modelValue', 'project-1')
    await wrapper.vm.$nextTick()

    await button(wrapper, 'New version').trigger('click')
    await wrapper.get('input[aria-label="Name"]').setValue('1.1.0')
    await button(wrapper, 'Create').trigger('click')
    await flushPromises()

    const versionSelect = wrapper.findAllComponents(SelectStub)[1]!
    expect(versionSelect.props('modelValue')).toBe('version-2')
    expect(versionSelect.props('options')).toContainEqual({ value: 'version-2', label: '1.1.0' })
    expect(toastWarn).toHaveBeenCalledWith('Version created, but the list could not be refreshed: offline')
  })

  it('shows creation failures without closing the version form', async () => {
    mockMutation.mockRejectedValue(new Error('Version name already exists'))
    const wrapper = mountPicker()
    wrapper.findAllComponents(SelectStub)[0]!.vm.$emit('update:modelValue', 'project-1')
    await wrapper.vm.$nextTick()

    await button(wrapper, 'New version').trigger('click')
    await wrapper.get('input[aria-label="Name"]').setValue('1.1.0')
    await button(wrapper, 'Create').trigger('click')
    await flushPromises()

    expect(wrapper.get('.mock-modal').text()).toContain('Version name already exists')
    expect(wrapper.find('.mock-modal').exists()).toBe(true)
    expect(wrapper.emitted('add')).toBeUndefined()
  })
})
