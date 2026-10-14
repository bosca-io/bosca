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
  Button: { template: '<button><slot /></button>' },
  SectionCard: { template: '<section><slot /></section>' },
}
const mocks = { buildBreadcrumb: (...parts: string[]) => parts }

describe('Artifact detail settings', () => {
  it.each(['docker', 'raw'])('offers settings for %s without mounting sync controls on the detail page', async type => {
    repository.value.type = type
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ArtifactRepositorySync' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ArtifactRepositoryPublication' }).exists()).toBe(false)
    expect(wrapper.text()).toContain('Settings')
    isAdmin.value = false
    await flushPromises()
    expect(wrapper.text()).not.toContain('Settings')
  })

  it.each(['maven', 'npm', 'helm', 'ml'])('does not offer GitHub settings for %s artifacts', async type => {
    repository.value.type = type
    const wrapper = shallowMount(RepositoryPage, { global: { stubs, mocks } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('Settings')
  })
})
