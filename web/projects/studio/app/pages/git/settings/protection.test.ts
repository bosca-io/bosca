import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref, unref, type Ref } from 'vue'
import { print, type DocumentNode } from 'graphql'
import ProtectionPage from './protection.vue'

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ profile: ref(null) }),
}))

const repositories = [
  { id: 'repository-1', name: 'Primary Repository' },
  { id: 'repository-2', name: 'Secondary Repository' },
]

let repositoriesDocument: DocumentNode
let repositoriesVariables: Record<string, unknown>
const repositoriesError: Ref<Error | null> = ref(null)
const mockQuery = vi.fn()
const saveLastOwner = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
  mutation: vi.fn(),
  useAsyncQuery: (
    _key: string,
    document: DocumentNode,
    variables: Record<string, unknown>,
  ) => {
    repositoriesDocument = document
    repositoriesVariables = variables
    return {
      data: ref({ git: { repositories } }),
      status: ref('success'),
      error: repositoriesError,
    }
  },
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('useLastGitOwner', () => ({
  load: () => ({ id: 'owner-1', label: 'Repository Owner' }),
  save: saveLastOwner,
}))
vi.stubGlobal('useProfileSearch', () => ({ searchProfiles: vi.fn().mockResolvedValue([]) }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const SelectStub = defineComponent({
  name: 'Select',
  props: {
    modelValue: String,
    options: Array,
    placeholder: String,
    searchable: Boolean,
    searchFn: Function,
    accent: [String, Object],
    size: String,
  },
  emits: ['update:modelValue'],
  template: '<div class="mock-select" :data-placeholder="placeholder" />',
})

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    template: '<div class="mock-header"><slot name="actions" /></div>',
    props: ['accent', 'breadcrumb', 'title', 'subtitle'],
  },
  Select: SelectStub,
  Button: {
    template: '<button :disabled="disabled"><slot /></button>',
    props: ['primary', 'icon', 'size', 'accent', 'disabled'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span><slot /></span>', props: ['color'] },
  TextInput: {
    template: '<input />',
    props: ['modelValue', 'label', 'placeholder', 'mono', 'disabled', 'type'],
  },
  Modal: {
    template: '<div><slot /><slot name="footer" /></div>',
    props: ['title', 'icon', 'accent'],
  },
}

function mountPage() {
  return mount(ProtectionPage, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })
}

describe('Branch Protection Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    repositoriesError.value = null
    mockQuery.mockResolvedValue({ git: { branchProtectionRules: [] } })
  })

  it('loads repositories for the selected owner and lets the repository be selected', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(print(repositoriesDocument)).toMatch(/repositories\(ownerId: \$ownerId\)/)
    expect(unref(repositoriesVariables.ownerId)).toBe('owner-1')

    const selects = wrapper.findAllComponents(SelectStub)
    expect(selects).toHaveLength(2)
    expect(selects[0]!.props('options')).toEqual([
      { value: 'owner-1', label: 'Repository Owner' },
    ])
    expect(selects[1]!.props('options')).toEqual([
      { value: 'repository-1', label: 'Primary Repository' },
      { value: 'repository-2', label: 'Secondary Repository' },
    ])

    selects[1]!.vm.$emit('update:modelValue', 'repository-2')
    await flushPromises()

    expect(mockQuery).toHaveBeenCalledTimes(1)
    expect(mockQuery.mock.calls[0]![1]).toEqual({ repositoryId: 'repository-2' })
    expect(wrapper.text()).toContain('No branch protection rules for this repository.')
  })

  it('surfaces a repository loading failure', async () => {
    repositoriesError.value = new Error('request failed')

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('.error-state').text()).toBe('Could not load repositories for this owner.')
  })
})
