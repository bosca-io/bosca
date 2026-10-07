import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import RepositoryPage from './[id].vue'

enableAutoUnmount(afterEach)
const repository = ref({
  id: 'image-1', name: 'server', namespace: { id: 'namespace-1', name: 'bosca' },
  type: 'docker', versions: [], tags: [], versionCount: 0, tagCount: 0,
})
const isAdmin = ref(true)
vi.stubGlobal('usePersonas', () => ({ isAdmin }))
vi.stubGlobal('useRoute', () => ({ params: { id: 'image-1' } }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#64748b' }))
vi.stubGlobal('useRawArtifactUploadStaging', () => ({ stage: vi.fn() }))
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({
    data: ref({ artifactsAdmin: { repository } }), status: ref('success'), refresh: vi.fn(),
  }),
  mutation: vi.fn(),
}))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)
beforeEach(() => {
  repository.value = { ...repository.value, id: 'image-1', type: 'docker' }
  isAdmin.value = true
})
const stubs = {
  PageShell: { template: '<main><slot name="header" /><slot /></main>' },
  PageHeader: { props: ['breadcrumb', 'title', 'subtitle', 'accent'], template: '<header><slot name="actions" /></header>' },
  SectionCard: { template: '<section><slot /></section>' },
}
const mocks = { buildBreadcrumb: (...parts: string[]) => parts }

describe('Artifact detail sync controls', () => {
  it('keeps ordinary repository viewing available without mounting admin sync controls', async () => {
    isAdmin.value = false
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    expect(wrapper.text()).toContain('server')
    expect(wrapper.findComponent({ name: 'ArtifactRepositorySync' }).exists()).toBe(false)
    isAdmin.value = true
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ArtifactRepositorySync' }).exists()).toBe(true)
  })
  it('scopes sync controls to the Docker image and remounts them when navigating to another artifact', async () => {
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    const original = wrapper.getComponent({ name: 'ArtifactRepositorySync' })
    expect(original.props('repositoryId')).toBe('image-1')
    repository.value = { ...repository.value, id: 'image-2' }
    await flushPromises()
    const next = wrapper.getComponent({ name: 'ArtifactRepositorySync' })
    expect(next.props('repositoryId')).toBe('image-2')
    expect(next.vm).not.toBe(original.vm)
  })

  it.each(['maven', 'npm', 'raw', 'helm', 'ml'])('does not offer Docker syncing for %s artifacts', async (type) => {
    repository.value.type = type
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ArtifactRepositorySync' }).exists()).toBe(false)
  })
})
