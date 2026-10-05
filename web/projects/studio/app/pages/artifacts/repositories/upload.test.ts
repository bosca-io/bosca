import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import UploadPage from './upload.vue'

const auth = vi.hoisted(() => ({
  getAuthHeaders: vi.fn(),
}))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth }),
}))

const router = { push: vi.fn(), back: vi.fn() }
const toast = { success: vi.fn(), error: vi.fn() }
const mutation = vi.fn()
const stagedFiles = ref<File[]>([])
const clearStagedFiles = vi.fn(() => { stagedFiles.value = [] })
const targets = ref({
  artifactsAdmin: {
    namespaces: [{ id: 'namespace-id', name: 'bosca' }],
    repositories: [{
      id: 'repository-id',
      namespaceId: 'namespace-id',
      name: 'cli',
      type: 'raw',
      namespace: { id: 'namespace-id', name: 'bosca' },
    }],
  },
})

vi.stubGlobal('useRoute', () => ({ query: { repositoryId: 'repository-id' } }))
vi.stubGlobal('useRouter', () => router)
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#c084fc' }))
vi.stubGlobal('useToast', () => toast)
vi.stubGlobal('useRuntimeConfig', () => ({ public: { artifactsUrl: 'http://localhost:8080/' } }))
vi.stubGlobal('useRawArtifactUploadStaging', () => ({
  stagedFiles,
  clear: clearStagedFiles,
}))
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: targets, status: ref('success') }),
  mutation,
}))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<main><slot name="header" /><slot /></main>' },
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  SectionCard: { template: '<section><slot /></section>', props: ['title', 'subtitle', 'padded'] },
  Button: {
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  Select: { template: '<div />', props: ['modelValue', 'options', 'label'] },
  TextInput: {
    props: ['modelValue', 'label', 'disabled'],
    emits: ['update:modelValue'],
    template: '<label>{{ label }}<input :data-field="label" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
  },
  RawArtifactDropZone: { template: '<div />' },
  Icon: { template: '<span />' },
}

beforeEach(() => {
  vi.clearAllMocks()
  stagedFiles.value = [new File(['binary'], 'bosca-cli', { type: 'application/octet-stream' })]
  auth.getAuthHeaders.mockResolvedValue({ Authorization: 'Bearer studio-token' })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    status: 201,
    text: async () => '',
  }))
})

describe('Raw Artifact Upload Page', () => {
  it('keeps dropped files across navigation and readies the preselected repository', async () => {
    const wrapper = mount(UploadPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('bosca-cli')
    expect(clearStagedFiles).toHaveBeenCalledOnce()

    await wrapper.get('[data-field="Version"]').setValue('2.0.0')
    const uploadButton = wrapper.findAll('button').find(button => button.text().includes('Upload 1 file'))
    expect(uploadButton?.attributes('disabled')).toBeUndefined()
  })
})
