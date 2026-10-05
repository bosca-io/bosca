import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import PullRequestsPage from './index.vue'

interface QueryCall {
  status: string | null
  offset: number
  limit: number
}

const repositories = [
  { id: 'owned-repository', name: 'Owned', slug: 'owned', ownerId: 'profile-1' },
  { id: 'shared-repository', name: 'Shared', slug: 'shared', ownerId: 'profile-2' },
]

let repositoriesDocument: DocumentNode
let repositoriesVariables: Record<string, unknown> | undefined
const mockQuery = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: (
    _key: string,
    document: DocumentNode,
    variables?: Record<string, unknown>,
  ) => {
    repositoriesDocument = document
    repositoriesVariables = variables
    return { data: ref({ git: { repositories } }), status: ref('success'), error: ref(null) }
  },
  query: mockQuery,
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    template: '<div class="mock-header" :data-subtitle="subtitle" />',
    props: ['accent', 'breadcrumb', 'title', 'subtitle', 'tabs', 'activeTab'],
  },
  Button: { template: '<button><slot /></button>', props: ['size', 'accent'] },
  Badge: { template: '<span><slot /></span>', props: ['color'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
}

function mountPage() {
  return mount(PullRequestsPage, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })
}

function pullRequest(repositoryId: string, number: number) {
  return {
    id: `${repositoryId}-${number}`,
    number,
    title: `Pull request ${number}`,
    description: null,
    status: 'OPEN',
    sourceBranch: `feature-${number}`,
    targetBranch: 'main',
    authorId: 'profile-1',
    created: new Date(number * 1000).toISOString(),
    updated: new Date(number * 1000).toISOString(),
    repositoryId,
  }
}

enableAutoUnmount(afterEach)

describe('Pull Requests Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const pullRequests = [
      ...Array.from({ length: 100 }, (_, index) => pullRequest('owned-repository', index + 1)),
      pullRequest('shared-repository', 1),
    ]
    mockQuery.mockImplementation(async (_document: DocumentNode, variables: QueryCall) => ({
      git: {
        allPullRequests: pullRequests.slice(variables.offset, variables.offset + variables.limit),
      },
    }))
  })

  it('loads accessible open pull requests without a per-repository fan-out', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(print(repositoriesDocument)).toMatch(/repositories \{/)
    expect(print(repositoriesDocument)).not.toMatch(/repositories\(/)
    expect(repositoriesVariables).toEqual({})
    expect(mockQuery.mock.calls.map(([, variables]) => variables)).toEqual([
      { status: 'OPEN', offset: 0, limit: 100 },
      { status: 'OPEN', offset: 100, limit: 100 },
    ])
    expect(wrapper.get('.mock-header').attributes('data-subtitle')).toBe('101 open across 2 repositories')
  })

  it('shows a load error instead of silently presenting an incomplete result', async () => {
    mockQuery.mockRejectedValue(new Error('request failed'))

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('.error-state').text()).toBe('Could not load pull requests.')
  })
})
