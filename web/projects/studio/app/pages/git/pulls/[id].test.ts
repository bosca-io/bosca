import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import PullRequestPage from './[id].vue'
import CommonMarkdown from '~/components/common/CommonMarkdown.vue'

const mockQuery = vi.fn()
const mockMutation = vi.fn()
let mergePlan: Array<Record<string, unknown>> = []
let mergePlanFailure: Error | null = null
let directDependencies: Array<Record<string, unknown>> = []
let directDependents: Array<Record<string, unknown>> = []
let diffFiles: Array<Record<string, unknown>> = []
let description: string | null = null
const storedReviewState = new Map<string, string>()
const localStorageMock: Storage = {
  get length() { return storedReviewState.size },
  clear: () => storedReviewState.clear(),
  getItem: key => storedReviewState.get(key) ?? null,
  key: index => Array.from(storedReviewState.keys())[index] ?? null,
  removeItem: key => storedReviewState.delete(key),
  setItem: (key, value) => storedReviewState.set(key, value),
}

vi.stubGlobal('localStorage', localStorageMock)
Object.defineProperty(window, 'localStorage', { value: localStorageMock, configurable: true })
vi.stubGlobal('useRoute', () => ({
  query: reactive({
    repo: 'repository-1',
    number: '7',
  }),
}))
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('useGraphQL', () => ({ query: mockQuery, mutation: mockMutation }))
vi.stubGlobal('useProfileSearch', () => ({ searchProfiles: vi.fn() }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    template: '<button class="files-tab" @click="$emit(\'tab\', \'Files Changed\')"><slot name="title" /><slot name="actions" /></button>',
    emits: ['tab'],
  },
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  Button: { template: '<button><slot /></button>' },
  Badge: { template: '<span><slot /></span>' },
  Icon: { template: '<span />' },
  Avatar: { template: '<span />' },
  Select: {
    template: '<button class="mock-select" @click="$emit(\'update:modelValue\', \'dependency-pr\')" />',
    emits: ['update:modelValue'],
  },
  TextInput: { template: '<input />' },
  Modal: { template: '<div><slot /><slot name="footer" /></div>' },
  MDC: {
    name: 'MDC',
    props: ['value', 'parserOptions', 'cacheKey', 'tag'],
    template: '<article class="mdc-stub">{{ value }}</article>',
  },
}

enableAutoUnmount(afterEach)

describe('Pull Request Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorageMock.clear()
    mergePlanFailure = null
    directDependencies = []
    directDependents = []
    description = null
    mergePlan = [{
      id: 'pull-request-1', repositoryId: 'repository-1', number: 7,
      title: 'Large generated diff', status: 'OPEN', mergeable: true,
    }]
    diffFiles = [{
      oldPath: null,
      newPath: 'generated.txt',
      changeType: 'ADD',
      hunks: [{
        oldStart: 0,
        oldCount: 0,
        newStart: 1,
        newCount: 20_001,
        totalLineCount: 20_001,
        lines: [{ content: 'first line', type: 'ADD', oldLineNumber: null, newLineNumber: 1 }],
      }],
    }]
    mockQuery.mockImplementation(async (document: DocumentNode) => {
      const operation = print(document)
      if (operation.includes('query GetPullRequestMergePlan')) {
        if (mergePlanFailure) throw mergePlanFailure
        return { git: { pullRequestMergePlan: mergePlan } }
      }
      if (operation.includes('query DependencyCandidates')) {
        return { git: { allPullRequests: [] } }
      }
      if (operation.includes('query GetPRDiff')) {
        return {
          git: {
            pullRequest: {
              diff: diffFiles,
            },
          },
        }
      }
      if (operation.includes('query GetPRReviews')) {
        return { git: { pullRequest: { reviews: [] } } }
      }
      if (operation.includes('query RepositoriesForPR')) {
        return {
          git: {
            repositoryById: { id: 'repository-1', name: 'Workspace', slug: 'workspace', defaultBranch: 'main' },
            repositories: [
              { id: 'repository-1', name: 'Workspace', slug: 'workspace', defaultBranch: 'main' },
              { id: 'repository-git', name: 'Git', slug: 'git', defaultBranch: 'main' },
              { id: 'repository-core', name: 'Core', slug: 'core', defaultBranch: 'main' },
            ],
          },
        }
      }
      return {
        git: {
          pullRequest: {
            id: 'pull-request-1',
            number: 7,
            title: 'Large generated diff',
            description,
            status: 'OPEN',
            sourceBranch: 'feature',
            targetBranch: 'main',
            authorId: 'profile-1',
            mergeable: true,
            created: '2026-08-04T12:00:00Z',
            updated: '2026-08-04T12:00:00Z',
            mergedAt: null,
            mergedBy: null,
            mergeSha: null,
            mergeStrategy: null,
            repositoryId: 'repository-1',
            sourceRepositoryId: null,
            conflictingFiles: [],
            assignees: [],
            dependencies: directDependencies,
            dependents: directDependents,
          },
        },
      }
    })
    mockMutation.mockImplementation(async (document: DocumentNode) => {
      const operation = print(document)
      if (operation.includes('mutation AddPullRequestDependency')) {
        return {
          git: {
            addPullRequestDependency: {
              dependencies: [{
                id: 'dependency-pr', repositoryId: 'repository-git', number: 4,
                title: 'Git dependency', status: 'OPEN',
              }],
            },
          },
        }
      }
      if (operation.includes('mutation RemovePullRequestDependency')) {
        return { git: { removePullRequestDependency: { dependencies: [] } } }
      }
      if (operation.includes('mutation MergePRWithDependencies')) {
        return { git: { mergePullRequestWithDependencies: mergePlan.map(item => ({ id: item.id, status: 'MERGED' })) } }
      }
      return { git: { mergePullRequest: { id: 'pull-request-1', status: 'MERGED' } } }
    })
  })

  it('renders the description as Markdown with raw HTML disabled', async () => {
    description = '## Why\n\n- keeps **structure**\n\n<script>alert(1)</script>'
    const wrapper = mount(PullRequestPage, {
      global: { stubs, components: { CommonMarkdown }, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    const rendered = wrapper.get('.pr-description').get('.mdc-stub')
    expect(rendered.text()).toBe(description)
    expect(wrapper.getComponent({ name: 'MDC' }).props('parserOptions')).toMatchObject({
      remark: { plugins: { 'remark-mdc': false } },
      rehype: { options: { allowDangerousHtml: false }, plugins: { 'rehype-raw': false } },
    })
    // A cache key that changes with the PR keeps an edited description from showing stale output.
    expect(wrapper.getComponent({ name: 'MDC' }).props('cacheKey')).toBe('pull-request-description-pull-request-1-2026-08-04T12:00:00Z')
  })

  it('shows a placeholder when there is no description', async () => {
    const wrapper = mount(PullRequestPage, {
      global: { stubs, components: { CommonMarkdown }, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    expect(wrapper.get('.pr-description').text()).toBe('No description provided.')
    expect(wrapper.find('.mdc-stub').exists()).toBe(false)
  })

  it('bounds large diff hunks and explains when lines are truncated', async () => {
    const wrapper = mount(PullRequestPage, {
      global: {
        stubs,
        mocks: { buildBreadcrumb: (...parts: string[]) => parts },
      },
    })
    await flushPromises()

    const diffCall = mockQuery.mock.calls.find(([document]) => print(document).includes('query GetPRDiff'))
    if (!diffCall) throw new Error('Expected the pull request diff query')
    expect(diffCall[1]).toEqual({ repositoryId: 'repository-1', number: 7, lineLimit: 2_000 })
    expect(print(diffCall[0])).toMatch(/totalLineCount\s+lines\(limit: \$lineLimit\)/)

    await wrapper.get('.files-tab').trigger('click')

    expect(wrapper.get('.diff-truncated').text()).toContain('Showing the first 1 of 20,001 lines')
  })

  it('tracks viewed files, filters to unviewed work, and persists progress', async () => {
    diffFiles.push({
      oldPath: 'src/old.ts',
      newPath: 'src/current.ts',
      changeType: 'RENAME',
      hunks: [{
        oldStart: 1,
        oldCount: 1,
        newStart: 1,
        newCount: 1,
        totalLineCount: 2,
        lines: [
          { content: '-before', type: 'DELETE', oldLineNumber: 1, newLineNumber: null },
          { content: '+after', type: 'ADD', oldLineNumber: null, newLineNumber: 1 },
        ],
      }],
    })
    const wrapper = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()
    await wrapper.get('.files-tab').trigger('click')

    expect(wrapper.findAll('.changed-file-link')).toHaveLength(2)
    expect(wrapper.get('.review-progress-title').text()).toContain('0 of 2 files viewed')
    expect(wrapper.get('.diff-file-stats').text()).toContain('+1')

    await wrapper.get('input[aria-label="Mark generated.txt viewed"]').setValue(true)
    expect(wrapper.get('.review-progress-title').text()).toContain('1 of 2 files viewed')
    expect(window.localStorage.getItem('bosca:git:pull-request:pull-request-1:viewed-files')).toContain('generated.txt')

    await wrapper.get('.review-filter-unviewed').trigger('click')
    expect(wrapper.findAll('.changed-file-link')).toHaveLength(1)
    expect(wrapper.find('.changed-file-link[title="src/current.ts"]').exists()).toBe(true)
    expect(wrapper.find('.changed-file-link[title="generated.txt"]').exists()).toBe(false)

    wrapper.unmount()
    const restored = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()
    await restored.get('.files-tab').trigger('click')
    expect(restored.get('input[aria-label="Mark generated.txt viewed"]').attributes('checked')).toBeDefined()
    expect(restored.get('.review-progress-title').text()).toContain('1 of 2 files viewed')
  })

  it('resets viewed state when the contents of a file change', async () => {
    const wrapper = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()
    await wrapper.get('.files-tab').trigger('click')
    await wrapper.get('input[aria-label="Mark generated.txt viewed"]').setValue(true)
    wrapper.unmount()

    diffFiles = [{
      ...diffFiles[0],
      hunks: [{
        oldStart: 0,
        oldCount: 0,
        newStart: 1,
        newCount: 1,
        totalLineCount: 1,
        lines: [{ content: 'changed line', type: 'ADD', oldLineNumber: null, newLineNumber: 1 }],
      }],
    }]
    const changed = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()
    await changed.get('.files-tab').trigger('click')

    expect(changed.get('input[aria-label="Mark generated.txt viewed"]').attributes('checked')).toBeUndefined()
    expect(changed.get('.review-progress-title').text()).toContain('0 of 1 files viewed')
  })

  it('shows nested dependencies in merge order and only cascades after confirmation', async () => {
    directDependencies = [{
      id: 'git-pr', repositoryId: 'repository-git', number: 4,
      title: 'Git dependency', status: 'OPEN',
    }]
    mergePlan = [
      { id: 'core-pr', repositoryId: 'repository-core', number: 2, title: 'Core prerequisite', status: 'OPEN', mergeable: true },
      { id: 'git-pr', repositoryId: 'repository-git', number: 4, title: 'Git dependency', status: 'OPEN', mergeable: true },
      { id: 'pull-request-1', repositoryId: 'repository-1', number: 7, title: 'Workspace parent', status: 'OPEN', mergeable: true },
    ]
    const wrapper = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    await wrapper.get('.merge-action').trigger('click')
    await flushPromises()

    const planItems = wrapper.findAll('.merge-plan-item').map(item => item.text())
    expect(planItems[0]).toContain('Core #2: Core prerequisite')
    expect(planItems[1]).toContain('Git #4: Git dependency')
    expect(planItems[2]).toContain('Workspace #7: Workspace parent')
    expect(mockMutation).not.toHaveBeenCalled()

    await wrapper.get('.merge-all-action').trigger('click')
    await flushPromises()

    const cascadeCall = mockMutation.mock.calls.find(([document]) => print(document).includes('mutation MergePRWithDependencies'))
    expect(cascadeCall?.[1]).toEqual({ id: 'pull-request-1', strategy: 'MERGE_COMMIT' })
    expect(wrapper.text()).toContain('merged')
  })

  it('does not expose a partial merge plan when a dependency repository is hidden', async () => {
    mergePlanFailure = new Error('You cannot view every pull request required for this merge')
    const wrapper = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    await wrapper.get('.merge-action').trigger('click')
    await flushPromises()

    expect(wrapper.find('.merge-plan-list').exists()).toBe(false)
    expect(wrapper.text()).toContain('You cannot view every pull request required for this merge')
    expect(mockMutation).not.toHaveBeenCalled()
  })

  it('adds a cross-repository dependency from the pull request page', async () => {
    const wrapper = mount(PullRequestPage, {
      global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } },
    })
    await flushPromises()

    await wrapper.get('.add-dependency-action').trigger('click')
    await wrapper.get('.dependency-select').trigger('click')
    await flushPromises()

    const addCall = mockMutation.mock.calls.find(([document]) => print(document).includes('mutation AddPullRequestDependency'))
    expect(addCall?.[1]).toEqual({ id: 'pull-request-1', dependencyId: 'dependency-pr' })
    expect(wrapper.text()).toContain('Git #4: Git dependency')
  })
})
