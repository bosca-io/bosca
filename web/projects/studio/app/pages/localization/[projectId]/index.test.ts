import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import type { DocumentNode, OperationDefinitionNode } from 'graphql'
import LocalizationProjectDetail from './index.vue'
import { NO_WORKOPS_PROJECT } from '~/utils/localizationProjectAttributes'

function operationName(document: DocumentNode): string {
  const operation = document.definitions.find(
    (definition): definition is OperationDefinitionNode => definition.kind === 'OperationDefinition',
  )
  return operation?.name?.value ?? ''
}

const project = {
  id: 'localization-1',
  name: 'Studio',
  description: 'Studio strings',
  sourceLanguage: 'en-US',
  attributes: {
    provider: { key: 'phrase' },
    workopsProjectId: 'workops-1',
  },
  created: '2026-08-01T00:00:00Z',
  modified: '2026-08-01T00:00:00Z',
  languages: [],
  formats: [],
  documents: [],
}

const projectData = ref({ localization: { project } })
const workOpsProjectsData = ref({
  workOps: {
    projects: {
      all: [
        { id: 'workops-1', key: 'STUDIO', name: 'Studio', archivedAt: null },
        { id: 'workops-2', key: 'SITE', name: 'Site', archivedAt: null },
        { id: 'workops-3', key: 'WEB', name: 'Web', archivedAt: null },
      ],
    },
  },
  localization: {
    projects: [
      { id: 'localization-1', name: 'Studio', attributes: project.attributes },
      { id: 'localization-2', name: 'Website', attributes: { workopsProjectId: 'workops-2' } },
    ],
  },
})

const mutation = vi.fn().mockResolvedValue({})
const refreshProject = vi.fn()

vi.stubGlobal('useRoute', () => ({ params: { projectId: 'localization-1' } }))
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: ref('#5ec5ff') }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: vi.fn() }))
vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn(),
  mutation,
  useAsyncQuery: (key: string) => {
    if (key === 'localization-project') {
      return { data: projectData, status: ref('success'), refresh: refreshProject }
    }
    if (key === 'localization-workops-projects') {
      return { data: workOpsProjectsData, status: ref('success'), refresh: vi.fn() }
    }
    return { data: ref(null), status: ref('success'), refresh: vi.fn() }
  },
}))

const PageHeaderStub = defineComponent({
  emits: ['tab'],
  template: '<div><button class="settings-tab" @click="$emit(\'tab\', \'Settings\')">Settings</button><slot name="actions" /></div>',
})

const SelectStub = defineComponent({
  name: 'Select',
  props: ['modelValue', 'options', 'loading', 'placeholder', 'searchable', 'accent'],
  emits: ['update:modelValue'],
  template: '<select />',
})

const ButtonStub = defineComponent({
  name: 'Button',
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
})

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: PageHeaderStub,
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  FormField: { template: '<label><slot /></label>' },
  TextInput: { template: '<input />', props: ['modelValue'] },
  Textarea: { template: '<textarea />', props: ['modelValue'] },
  Select: SelectStub,
  Button: ButtonStub,
  Badge: { template: '<span><slot /></span>' },
  Icon: { template: '<span />' },
  Switch: { template: '<input type="checkbox" />' },
  Modal: { template: '<div><slot /><slot name="footer" /></div>' },
  ConfirmModal: { template: '<div><slot /></div>' },
  SearchInput: { template: '<input />' },
  ProgressBar: { template: '<div />' },
  GlassTable: { template: '<div />' },
  Pagination: { template: '<div />' },
}

function mountSettings() {
  const wrapper = shallowMount(LocalizationProjectDetail, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...labels: string[]) => labels },
    },
  })
  wrapper.find('.settings-tab').trigger('click')
  return wrapper
}

function saveButton(wrapper: ReturnType<typeof mountSettings>) {
  const button = wrapper.findAllComponents(ButtonStub).find(candidate => candidate.text().includes('Save'))
  expect(button).toBeDefined()
  return button!
}

enableAutoUnmount(afterEach)

describe('Localization project WorkOps binding', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the current binding and prevents selecting a project linked elsewhere', async () => {
    const wrapper = mountSettings()
    await flushPromises()

    const select = wrapper.findComponent(SelectStub)
    expect(select.props('modelValue')).toBe('workops-1')
    expect(select.props('options')).toContainEqual(expect.objectContaining({
      value: 'workops-2',
      disabled: true,
    }))
  })

  it('saves a new binding while preserving unrelated attributes', async () => {
    const wrapper = mountSettings()
    await flushPromises()

    wrapper.findComponent(SelectStub).vm.$emit('update:modelValue', 'workops-3')
    await flushPromises()
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const editCall = mutation.mock.calls.find(([document]) => operationName(document) === 'EditLocalizationProject')
    expect(editCall?.[1]).toEqual({
      id: 'localization-1',
      input: {
        name: 'Studio',
        description: 'Studio strings',
        sourceLanguage: 'en-US',
        attributes: {
          provider: { key: 'phrase' },
          workopsProjectId: 'workops-3',
        },
      },
    })
  })

  it('can clear the binding without clearing other attributes', async () => {
    const wrapper = mountSettings()
    await flushPromises()

    wrapper.findComponent(SelectStub).vm.$emit('update:modelValue', NO_WORKOPS_PROJECT)
    await flushPromises()
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const editCall = mutation.mock.calls.find(([document]) => operationName(document) === 'EditLocalizationProject')
    expect(editCall?.[1].input.attributes).toEqual({ provider: { key: 'phrase' } })
  })
})
