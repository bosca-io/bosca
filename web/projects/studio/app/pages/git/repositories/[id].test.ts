import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import RepositoryPage from './[id].vue'

const query = vi.fn()
const mutation = vi.fn()
const route = reactive({ params: { id: 'repo' }, query: {} as Record<string, string> })
const repository = {
  id: 'repo', name: 'Repository', slug: 'repository', ownerId: 'owner', defaultBranch: 'main',
  visibility: 'PRIVATE', archived: false, configuration: {},
}
const names = ['v2', 'v10', 'v1', ...Array.from({ length: 23 }, (_, index) => `older-${index}`)]
const tags = names.map(name => ({ name, sha: 'a'.repeat(40), isAnnotated: false, message: null }))

vi.stubGlobal('useRoute', () => route)
vi.stubGlobal('useRouter', () => ({
  push: ({ query: next }: { query: Record<string, string> }) => { route.query = next },
  beforeEach: () => vi.fn(),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('resolveGitCommitAuthor', () => ({ authorName: 'Author', authorEmail: 'author@example.test' }))
vi.stubGlobal('buildBreadcrumb', () => [])
vi.stubGlobal('useGraphQL', () => ({
  query, mutation,
  useAsyncQuery: () => ({ data: ref({ git: { repositoryById: repository } }), status: ref('success'), refresh: vi.fn() }),
}))

function mountPage() {
  return shallowMount(RepositoryPage, {
    global: {
      renderStubDefaultSlot: true,
      mocks: { buildBreadcrumb: () => [] },
      stubs: {
        PageShell: { template: '<div><slot name="header" /><slot /></div>' },
        SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
        Button: { template: '<button :disabled="disabled"><slot /></button>', props: ['disabled'] },
        ConfirmModal: { template: '<button class="confirm" @click="$emit(\'confirm\')">Confirm</button>', emits: ['confirm'] },
        PageHeader: true, Icon: true, Badge: true, Select: true, Input: true, Modal: true,
        StatTile: true, CodeEditor: true, RepositoryReadme: true, RepositorySettings: true,
      },
    },
  })
}

describe('repository tag pages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.query = { tab: 'Tags' }
    query.mockImplementation(async (document: DocumentNode, variables: { offset: number; limit: number }) => {
      const operation = print(document)
      if (operation.includes('query Tags(')) return { git: { tags: tags.slice(variables.offset, variables.offset + variables.limit) } }
      if (operation.includes('query Branches(')) return { git: { branches: [] } }
      if (operation.includes('query Tree(')) return { git: { tree: [] } }
      if (operation.includes('query RepoStats(')) return { git: { stats: null } }
      if (operation.includes('query OwnerProfile(')) return { profiles: { profile: null } }
      throw new Error(`Unexpected operation: ${operation}`)
    })
    mutation.mockResolvedValue({ git: { deleteTag: true } })
  })

  it('preserves API ordering and navigates bounded pages through the URL', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.findAll('.branch-name').map(tag => tag.text())).toEqual(names.slice(0, 25))
    expect(wrapper.get('.section-meta').text()).toBe('25 shown')
    expect(query.mock.calls.find(([document]) => print(document).includes('query Tags('))?.[1])
      .toEqual({ repositoryId: 'repo', limit: 26, offset: 0 })
    await wrapper.get('nav[aria-label="Tag pages"] button:last-child').trigger('click')
    await flushPromises()
    expect(route.query.tagOffset).toBe('25')
    expect(wrapper.findAll('.branch-name').map(tag => tag.text())).toEqual(['older-22'])
    expect(wrapper.get('nav[aria-label="Tag pages"] button:last-child').attributes('disabled')).toBeDefined()
    await wrapper.get('nav[aria-label="Tag pages"] button:first-child').trigger('click')
    await flushPromises()
    expect(route.query.tagOffset).toBeUndefined()
    expect(wrapper.findAll('.branch-name')).toHaveLength(25)
    wrapper.unmount()
  })

  it('loads tags only on the Tags tab and restores an offset from the URL', async () => {
    route.query = { tab: 'Branches', tagOffset: '25' }
    const wrapper = mountPage()
    await flushPromises()
    expect(query.mock.calls.some(([document]) => print(document).includes('query Tags('))).toBe(false)
    route.query.tab = 'Tags'
    await flushPromises()
    expect(wrapper.findAll('.branch-name').map(tag => tag.text())).toEqual(['older-22'])
    wrapper.unmount()
  })

  it('shows failed page loads and lets the user retry', async () => {
    const original = query.getMockImplementation()!
    const wrapper = mountPage()
    await flushPromises()
    query.mockImplementation(async (document: DocumentNode, variables: unknown) => {
      if (print(document).includes('query Tags(')) throw new Error('Tags unavailable')
      return original(document, variables)
    })
    await wrapper.get('nav[aria-label="Tag pages"] button:last-child').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toContain('Tags unavailable')
    query.mockImplementation(original)
    await wrapper.get('[role=alert] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(wrapper.findAll('.branch-name').map(tag => tag.text())).toEqual(['older-22'])
    wrapper.unmount()
  })

  it('returns to the previous page after deleting the final tag on a page', async () => {
    route.query.tagOffset = '25'
    const wrapper = mountPage()
    await flushPromises()
    const original = query.getMockImplementation()!
    query.mockImplementation(async (document: DocumentNode, variables: { offset: number }) => {
      if (print(document).includes('query Tags(') && variables.offset === 25) return { git: { tags: [] } }
      return original(document, variables)
    })
    await wrapper.get('.tag-delete-btn').trigger('click')
    await wrapper.get('.confirm').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { repositoryId: 'repo', tag: 'older-22' })
    expect(route.query.tagOffset).toBeUndefined()
    expect(wrapper.findAll('.branch-name')).toHaveLength(25)
    wrapper.unmount()
  })
})
