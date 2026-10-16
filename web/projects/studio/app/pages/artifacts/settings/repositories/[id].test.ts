import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { computed, ref } from 'vue'
import RepositoryPage from './[id].vue'

enableAutoUnmount(afterEach)
const repository = ref({
  id: 'image-1', name: 'server', namespace: { id: 'namespace-1', name: 'bosca' },
  type: 'docker', versions: [], tags: [], versionCount: 0, tagCount: 0,
})
const isAdmin = ref(true)
const hasRepository = ref(true)
const status = ref('success')
const error = ref<Error | null>(null)
vi.stubGlobal('usePersonas', () => ({ isAdmin }))
vi.stubGlobal('useRoute', () => ({ params: { id: 'image-1' } }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#64748b' }))
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({
    data: computed(() => ({ artifactsAdmin: { repository: hasRepository.value ? repository.value : null } })),
    status, error, refresh: vi.fn(),
  }),
  mutation: vi.fn(),
}))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)
beforeEach(() => {
  repository.value = { ...repository.value, id: 'image-1', type: 'docker' }
  isAdmin.value = true
  hasRepository.value = true
  status.value = 'success'
  error.value = null
})
const stubs = {
  PageShell: { template: '<main><slot name="header" /><slot /></main>' },
  PageHeader: { props: ['breadcrumb', 'title', 'subtitle', 'accent'], template: '<header><slot name="actions" /></header>' },
  Button: { template: '<button><slot /></button>' },
  SectionCard: { template: '<section><slot /></section>' },
}
const mocks = { buildBreadcrumb: (...parts: string[]) => parts }

describe('Repository sync settings page', () => {
  it.each(['idle', 'pending'])('renders the same loading state while the client query is %s', (state) => {
    hasRepository.value = false
    status.value = state
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    expect(wrapper.text()).toContain('Loading repository…')
    expect(wrapper.text()).not.toContain('Repository not found.')
  })
  it('reports a missing repository only after its lookup succeeds', () => {
    hasRepository.value = false
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    expect(wrapper.text()).toContain('Repository not found.')
    expect(wrapper.text()).not.toContain('Loading repository…')
  })
  it('reports a failed lookup without treating the repository as missing', () => {
    hasRepository.value = false
    status.value = 'error'
    error.value = new Error('Access denied')
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    expect(wrapper.text()).toContain('Could not load repository settings.')
    expect(wrapper.text()).not.toContain('Repository not found.')
  })
  it.each([
    ['docker', 'ArtifactRepositorySync'], ['raw', 'ArtifactRepositoryPublication'],
  ])('mounts %s controls only in settings and scopes them to the repository', async (type, component) => {
    repository.value.type = type
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    const original = wrapper.getComponent({ name: component })
    expect(original.props('repositoryId')).toBe('image-1')
    repository.value = { ...repository.value, id: 'image-2' }
    await flushPromises()
    const next = wrapper.getComponent({ name: component })
    expect(next.props('repositoryId')).toBe('image-2')
    expect(next.vm).not.toBe(original.vm)
    isAdmin.value = false
    await flushPromises()
    expect(wrapper.findComponent({ name: component }).exists()).toBe(false)
  })
  it('does not mount GitHub controls for unsupported artifact types', async () => {
    repository.value.type = 'maven'
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ArtifactRepositorySync' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ArtifactRepositoryPublication' }).exists()).toBe(false)
  })
})
