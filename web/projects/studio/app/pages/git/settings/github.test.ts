import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { buildSchema, extendSchema, parse, print, validate, type DocumentNode } from 'graphql'
import Pair from '../../../components/git/GitHubRepositorySync.vue'
import History from '../../../components/git/GitHubSyncHistory.vue'
import Mappings from '../../../components/git/GitHubUserMappings.vue'
import Page from './github.vue'
import Tabs from '../../../../../../packages/ui/src/components/Tabs.vue'

vi.mock('@bosca/auth-client-browser', () => ({ useAuth: () => ({ profile: ref(null) }) }))

const checkedInSchema = buildSchema(readFileSync(resolve('schema.graphqls'), 'utf8'))
const schema = checkedInSchema.getType('GitHub') ? checkedInSchema : extendSchema(
  checkedInSchema,
  parse(readFileSync(resolve('../../../git/git/src/main/resources/graphql/github.graphqls'), 'utf8')),
)
const query = vi.fn()
const mutation = vi.fn()
let gitServerUrl = 'http://localhost:8080'
vi.stubGlobal('useRuntimeConfig', () => ({ public: { gitServerUrl } }))
function validateDocument(document: DocumentNode) {
  expect(validate(schema, document).map(error => error.message)).toEqual([])
}
vi.stubGlobal('useGraphQL', () => ({
  query: (document: DocumentNode, variables: Record<string, unknown>) => {
    validateDocument(document)
    return query(document, variables)
  },
  mutation: (document: DocumentNode, variables: Record<string, unknown>) => {
    validateDocument(document)
    return mutation(document, variables)
  },
  useAsyncQuery: (_key: string, document: DocumentNode, _variables: Record<string, unknown>) => {
    validateDocument(document)
    return {
      data: ref({ git: { repositories: [{ id: 'repo-1', name: 'One' }, { id: 'repo-2', name: 'Two' }] } }),
      status: ref('success'), error: ref(null),
    }
  },
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#64748b' }))
vi.stubGlobal('useProfileSearch', () => ({ searchProfiles: vi.fn().mockResolvedValue([]) }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const Field = defineComponent({
  props: ['modelValue', 'label', 'disabled', 'options', 'loading', 'type'],
  emits: ['update:modelValue'],
  template: '<label>{{ label }}<input :aria-label="label" :type="type || \'text\'" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
})
const Table = defineComponent({
  props: ['rows', 'columns', 'loading', 'emptyText'],
  template: '<div><p v-if="loading">Loading…</p><template v-else><p v-if="!rows.length">{{ emptyText }}</p><div v-for="(row, i) in rows" :key="i" class="test-row"><div v-for="column in columns" :key="column.key"><slot :name="\'col-\' + column.key" :row="row">{{ row[column.key] }}</slot></div></div></template></div>',
})
const stubs = {
  Tabs,
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<div><slot name="actions" /></div>' },
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  Button: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  TextInput: Field,
  Select: Field,
  Modal: { props: ['title'], emits: ['close'], template: '<div class="test-modal"><h2>{{ title }}</h2><button type="button" @click="$emit(\'close\')">Close secret editor</button><slot /></div>' },
  GlassTable: Table,
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
}
const pair = {
  repositoryId: 'repo-1', githubRepositoryId: 42, owner: 'bosca', name: 'sync',
  webhookSecretName: 'webhook', tokenSecretName: 'token', enabled: false, version: 7,
}
const principal = { id: 'principal-1', primaryProfileId: 'profile-1', profiles: [{ id: 'profile-1', name: 'Ada' }], credentials: [] }
function mountPair() { return mount(Pair, { props: { repositoryId: 'repo-1' }, global: { stubs } }) }
function mountHistory() { return mount(History, { props: { repositoryId: 'repo-1', enabled: true }, global: { stubs } }) }
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const result = wrapper.findAll('button').find(button => button.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}
function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}

describe('GitHub sync administration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    gitServerUrl = 'http://localhost:8080'
    query.mockImplementation((document: DocumentNode) => {
      const text = print(document)
      if (text.includes('GitHubSyncPair')) return { github: { pair }, pipelines: { secrets: [{ name: 'token' }, { name: 'webhook' }] } }
      if (text.includes('GitHubSyncPrincipals')) return { security: { principals: { all: [principal] } } }
      return { github: { refStates: [], pullRequestStates: [], deliveries: [], users: [] } }
    })
    mutation.mockImplementation((document: DocumentNode, variables: Record<string, unknown>) => {
      if (print(document).includes('SetGitHubSyncSecret')) return { pipelines: { setSecret: { name: variables.name } } }
      return { github: { savePair: { ...pair, version: 8 }, mapUserByUsername: { githubUserId: 99, githubUsername: "octocat", principalId: principal.id }, unmapUser: true } }
    })
  })

  it('loads and saves separate automatic push and pull filters and reloads saved values', async () => {
    const configured = { ...pair, pushBranchIncludes: ['main', 'release/*'], pushBranchExcludes: ['release/private'],
      pullBranchIncludes: ['feature/**'], pullBranchExcludes: ['feature/wip/**'] }
    query.mockResolvedValue({ github: { pair: configured }, pipelines: { secrets: [{ name: 'token' }, { name: 'webhook' }] } })
    const wrapper = mountPair()
    await flushPromises()
    expect((wrapper.get('input[aria-label="Push: include branches"]').element as HTMLInputElement).value).toBe('main, release/*')
    expect((wrapper.get('input[aria-label="Pull: exclude branches"]').element as HTMLInputElement).value).toBe('feature/wip/**')
    await wrapper.get('input[aria-label="Push: include branches"]').setValue(' main, release/*, main, ')
    await wrapper.get('input[aria-label="Pull: include branches"]').setValue('')
    mutation.mockResolvedValueOnce({ github: { savePair: { ...configured, pullBranchIncludes: [], version: 8 } } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].input).toMatchObject({ pushBranchIncludes: ['main', 'release/*'],
      pushBranchExcludes: ['release/private'], pullBranchIncludes: [], pullBranchExcludes: ['feature/wip/**'] })
    expect((wrapper.get('input[aria-label="Pull: include branches"]').element as HTMLInputElement).value).toBe('')
    await button(wrapper, 'Reload pairing').trigger('click')
    await flushPromises()
    expect((wrapper.get('input[aria-label="Pull: include branches"]').element as HTMLInputElement).value).toBe('feature/**')
    expect(wrapper.text()).toContain('Tags and manual Pull, Push, Reconcile, and Resolve actions are unaffected')
  })

  it('saves secret references and uses the returned version on the next save', async () => {
    const wrapper = mountPair()
    await flushPromises()
    expect(wrapper.find('input[aria-label="GitHub repository ID"]').exists()).toBe(false)
    await wrapper.get('input[aria-label="GitHub owner"]').setValue('renamed')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({
      input: { repositoryId: pair.repositoryId, owner: 'renamed', name: pair.name,
        webhookSecretName: pair.webhookSecretName, tokenSecretName: pair.tokenSecretName,
        enabled: pair.enabled, version: pair.version,
        pushBranchIncludes: [], pushBranchExcludes: [], pullBranchIncludes: [], pullBranchExcludes: [] },
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].input.version).toBe(8)
    expect(wrapper.get('.webhook-url').text()).toBe('http://localhost:8080/api/webhooks/github/repo-1')
  })

  it.each([
    ['https://git.example.test', 'https://git.example.test/api/webhooks/github/repo-1'],
    ['https://git.example.test/', 'https://git.example.test/api/webhooks/github/repo-1'],
    ['https://git.example.test/bosca///', 'https://git.example.test/bosca/api/webhooks/github/repo-1'],
  ])('shows the full webhook URL for Git server configuration %s', async (configured, expected) => {
    gitServerUrl = configured
    const wrapper = mountPair()
    await flushPromises()
    expect(wrapper.get('.webhook-url').text()).toBe(expected)
  })

  it('creates disabled pairs at version zero without requiring a repository ID', async () => {
    query.mockResolvedValue({ github: { pair: null }, pipelines: { secrets: [{ name: 'token' }, { name: 'webhook' }] } })
    const wrapper = mountPair()
    await flushPromises()
    for (const [label, value] of [['GitHub owner', 'bosca'], ['GitHub repository name', 'sync'], ['Webhook secret name', 'webhook'], ['Token secret name', 'token']]) {
      await wrapper.get(`input[aria-label="${label}"]`).setValue(value)
    }
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].input).toEqual({
      repositoryId: 'repo-1', owner: 'bosca', name: 'sync', webhookSecretName: 'webhook',
      tokenSecretName: 'token', version: 0, enabled: false,
      pushBranchIncludes: [], pushBranchExcludes: [], pullBranchIncludes: [], pullBranchExcludes: [],
    })
  })

  it('requires a configured token for first save and owner or repository changes', async () => {
    query.mockResolvedValueOnce({ github: { pair: null }, pipelines: { secrets: [] } })
    const wrapper = mountPair()
    await flushPromises()
    for (const [label, value] of [['GitHub owner', 'bosca'], ['GitHub repository name', 'sync'], ['Webhook secret name', 'webhook'], ['Token secret name', 'token']]) {
      await wrapper.get(`input[aria-label="${label}"]`).setValue(value)
    }
    await wrapper.get('form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('so Bosca can look up the GitHub repository')
    query.mockResolvedValueOnce({ github: { pair }, pipelines: { secrets: [] } })
    await button(wrapper, 'Reload pairing').trigger('click')
    await flushPromises()
    await wrapper.get('input[aria-label="GitHub repository name"]').setValue('renamed')
    await wrapper.get('form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('keeps entered owner and name after the server cannot resolve a repository', async () => {
    query.mockResolvedValueOnce({ github: { pair: null }, pipelines: { secrets: [{ name: 'token' }] } })
    const wrapper = mountPair()
    await flushPromises()
    for (const [label, value] of [['GitHub owner', 'bosca'], ['GitHub repository name', 'missing'], ['Webhook secret name', 'webhook'], ['Token secret name', 'token']]) {
      await wrapper.get(`input[aria-label="${label}"]`).setValue(value)
    }
    const lookupError = 'GitHub repository lookup failed: HTTP 404. The repository was not found or is not accessible to the selected token. Check the GitHub owner and repository name, and ensure the token has access to this repository. For organization repositories, check token approval and SSO authorization.'
    mutation.mockRejectedValueOnce(new Error(lookupError))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe(lookupError)
    expect((wrapper.get('input[aria-label="GitHub owner"]').element as HTMLInputElement).value).toBe('bosca')
    expect((wrapper.get('input[aria-label="GitHub repository name"]').element as HTMLInputElement).value).toBe('missing')
    await wrapper.get('input[aria-label="GitHub repository name"]').setValue('sync')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].input).toMatchObject({ owner: 'bosca', name: 'sync', version: 0 })
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Repository pairing saved.')
  })

  it('allows a pair with missing secrets to be disabled, but prevents enabling it', async () => {
    query.mockResolvedValue({ github: { pair }, pipelines: { secrets: [] } })
    const wrapper = mountPair()
    await flushPromises()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Add both secrets')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].input.enabled).toBe(false)
  })

  it('reports load and concurrent-save failures without resetting the pairing', async () => {
    query.mockRejectedValueOnce(new Error('Load failed'))
    const wrapper = mountPair()
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('Load failed')
    await button(wrapper, 'Retry').trigger('click')
    await flushPromises()
    mutation.mockRejectedValueOnce(new Error('Repository pair changed concurrently'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Repository pair changed concurrently')
    expect(wrapper.find('input[aria-label="GitHub repository ID"]').exists()).toBe(false)
    query.mockResolvedValueOnce({ github: { pair: { ...pair, version: 9 } }, pipelines: { secrets: [{ name: 'token' }, { name: 'webhook' }] } })
    await button(wrapper, 'Reload pairing').trigger('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].input.version).toBe(9)
  })

  it('creates both secrets inline and selects their names without losing pairing edits', async () => {
    query.mockResolvedValueOnce({ github: { pair: null }, pipelines: { secrets: [] } })
    const wrapper = mountPair()
    await flushPromises()
    await wrapper.get('input[aria-label="GitHub owner"]').setValue('new-owner')
    await wrapper.get('input[aria-label="GitHub repository name"]').setValue('new-repo')
    for (const kind of ['webhook', 'token']) {
      await button(wrapper, `Add ${kind} secret`).trigger('click')
      await wrapper.get('.secret-form input[aria-label="Secret name"]').setValue(` new-${kind} `)
      const value = wrapper.get('.secret-form input[type="password"]')
      await value.setValue(`test-${kind}-value`)
      await wrapper.get('.secret-form').trigger('submit')
      await flushPromises()
      expect(wrapper.find('.secret-form').exists()).toBe(false)
    }
    expect(query).toHaveBeenCalledTimes(1)
    expect(mutation.mock.calls[0]?.[1]).toEqual({ name: 'new-webhook', value: 'test-webhook-value' })
    expect(mutation.mock.calls[1]?.[1]).toEqual({ name: 'new-token', value: 'test-token-value' })
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[2]?.[1]).toEqual({ input: {
      repositoryId: 'repo-1', owner: 'new-owner', name: 'new-repo',
      webhookSecretName: 'new-webhook', tokenSecretName: 'new-token', enabled: true, version: 0,
      pushBranchIncludes: [], pushBranchExcludes: [], pullBranchIncludes: [], pullBranchExcludes: [],
    } })
  })

  it('clears secret drafts on cancel and explains replacing a shared secret', async () => {
    const wrapper = mountPair()
    await flushPromises()
    await button(wrapper, 'Replace token secret').trigger('click')
    expect(wrapper.text()).toContain('replaces its value everywhere this secret is used')
    await wrapper.get('.secret-form input[type="password"]').setValue('cancelled-value')
    await button(wrapper, 'Cancel').trigger('click')
    await button(wrapper, 'Replace token secret').trigger('click')
    expect((wrapper.get('.secret-form input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(mutation).not.toHaveBeenCalled()
    await button(wrapper, 'Close secret editor').trigger('click')
    expect(wrapper.find('.secret-form').exists()).toBe(false)
  })

  it('keeps secret failures visible and blocks empty or repeated saves', async () => {
    const wrapper = mountPair()
    await flushPromises()
    await button(wrapper, 'Replace token secret').trigger('click')
    await wrapper.get('.secret-form input[type="password"]').setValue('   ')
    await wrapper.get('.secret-form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
    await wrapper.get('.secret-form input[type="password"]').setValue('replacement-token')
    mutation.mockRejectedValueOnce(new Error('Secret store unavailable'))
    await wrapper.get('.secret-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.secret-form').text()).toContain('Secret store unavailable')
    const pending = deferred<unknown>()
    mutation.mockReturnValueOnce(pending.promise)
    await wrapper.get('.secret-form').trigger('submit')
    await wrapper.get('.secret-form').trigger('submit')
    await button(wrapper, 'Close secret editor').trigger('click')
    expect(wrapper.find('.secret-form').exists()).toBe(true)
    expect(mutation).toHaveBeenCalledTimes(2)
    pending.resolve({ pipelines: { setSecret: { name: 'token' } } })
    await flushPromises()
    expect(wrapper.find('.secret-form').exists()).toBe(false)
    await button(wrapper, 'Replace token secret').trigger('click')
    expect((wrapper.get('.secret-form input[type="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('uses scoped offset pagination with one lookahead row', async () => {
    query.mockResolvedValue({ github: { refStates: Array.from({ length: 26 }, (_, i) => ({
      ref: `refs/heads/branch-${i}`, synchronized: false, conflict: i === 0,
      sha: null, boscaSha: 'native', githubSha: 'remote', modified: '2026-10-01T00:00:00Z',
    })) } })
    const wrapper = mountHistory()
    await flushPromises()
    expect(wrapper.findAll('.test-row')).toHaveLength(25)
    expect(wrapper.text()).toContain('Conflict')
    expect(wrapper.text()).toContain('native')
    expect(wrapper.text()).toContain('remote')
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(query.mock.calls[1]?.[1]).toEqual({ repositoryId: 'repo-1', offset: 25, limit: 26 })
    await button(wrapper, 'Deliveries').trigger('click')
    await flushPromises()
    expect(query.mock.calls[2]?.[1].offset).toBe(0)
  })

  it('keeps delayed responses from replacing a newly selected history tab', async () => {
    const pending = deferred<unknown>()
    query.mockReturnValueOnce(pending.promise)
    const wrapper = mountHistory()
    query.mockResolvedValueOnce({ github: { deliveries: [{ deliveryId: 'delivery-1', event: 'push', ignored: false, principalId: null, githubUserId: 99, created: '2026-10-01T00:00:00Z' }] } })
    await button(wrapper, 'Deliveries').trigger('click')
    await flushPromises()
    pending.resolve({ github: { refStates: [{ ref: 'obsolete-branch' }] } })
    await flushPromises()
    expect(wrapper.text()).toContain('delivery-1')
    expect(wrapper.text()).toContain('Unattributed')
    expect(wrapper.text()).not.toContain('obsolete-branch')
    expect(wrapper.findAll('button').some(button => button.text() === 'Reconcile')).toBe(false)
  })

  it('shows unmapped delivery recovery and opens branches without transferring refs', async () => {
    const wrapper = mountHistory()
    await flushPromises()
    query.mockResolvedValueOnce({ github: { deliveries: [{
      deliveryId: 'blocked', event: 'push', ignored: false, principalId: null, githubUserId: 99,
      created: '2026-10-01T00:00:00Z', problem: 'The originating GitHub user requires EDIT permission',
      importProblem: 'No Bosca user was mapped when this delivery arrived. Use Pull from GitHub.',
    }] } })
    await button(wrapper, 'Deliveries').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('No Bosca user was mapped')
    expect(wrapper.findAll('a').some(link => link.attributes('href') === '/git/settings/github')).toBe(true)
    await button(wrapper, 'Open branches and tags').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Pull from GitHub')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('links a mapped delivery permission blocker directly to repository permissions', async () => {
    const wrapper = mountHistory()
    await flushPromises()
    query.mockResolvedValueOnce({ github: { deliveries: [{
      deliveryId: 'blocked', event: 'push', ignored: false, principalId: principal.id, githubUserId: 99,
      created: '2026-10-01T00:00:00Z', problem: null,
      importProblem: 'Grant repository Edit to a group the user belongs to.',
    }] } })
    await button(wrapper, 'Deliveries').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('repository Edit')
    expect(wrapper.findAll('a').some(link => link.attributes('href') === '/git/repositories/repo-1?tab=Settings&setting=permissions')).toBe(true)
  })

  it('refreshes persisted observations after a failed reconciliation', async () => {
    const wrapper = mountHistory()
    await flushPromises()
    mutation.mockRejectedValueOnce(new Error('Provider unavailable'))
    await button(wrapper, 'Reconcile').trigger('click')
    await flushPromises()
    expect(print(mutation.mock.calls[0]?.[0])).toContain('reconcileRefs(repositoryId: $repositoryId)')
    expect(query).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Provider unavailable')
  })

  it.each([
    ['Pull from GitHub', 'Pulling…', 'pullRefs', 'Pull finished.'],
    ['Push to GitHub', 'Pushing…', 'pushRefs', 'Push finished.'],
  ])('runs %s in the selected direction and blocks overlapping operations', async (label, running, field, notice) => {
    const wrapper = mountHistory()
    await flushPromises()
    const pending = deferred<unknown>()
    mutation.mockReturnValueOnce(pending.promise)
    await button(wrapper, label).trigger('click')
    expect(print(mutation.mock.calls[0]?.[0])).toContain(`${field}(repositoryId: $repositoryId)`)
    expect(mutation.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1' })
    for (const control of [running, 'Reconcile', 'Refresh', 'Pull requests']) {
      const element = button(wrapper, control).element
      expect(element.hasAttribute('disabled') || element.closest('fieldset')?.hasAttribute('disabled')).toBe(true)
    }
    await button(wrapper, 'Reconcile').trigger('click')
    expect(mutation).toHaveBeenCalledOnce()
    pending.resolve({ github: { [field]: [] } })
    await flushPromises()
    expect(query).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(notice)
    expect(button(wrapper, label).attributes('disabled')).toBeUndefined()
  })

  it.each(['Pull from GitHub', 'Push to GitHub'])('refreshes committed observations after %s fails and permits retry', async (label) => {
    const wrapper = mountHistory()
    await flushPromises()
    mutation.mockRejectedValueOnce(new Error('Branch is protected'))
    await button(wrapper, label).trigger('click')
    await flushPromises()
    expect(query).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="alert"]').text()).toBe('Branch is protected')
    await button(wrapper, label).trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await button(wrapper, 'Pull requests').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text() === label)).toBe(false)
  })

  it.each(['GITHUB', 'BOSCA'])('resolves one reviewed conflict using %s and blocks overlapping actions', async (choice) => {
    const conflict = { ref: 'refs/heads/main', sha: null, synchronized: false, conflict: true,
      boscaSha: 'a'.repeat(40), githubSha: 'b'.repeat(40), modified: '2026-10-01T00:00:00Z' }
    query.mockResolvedValueOnce({ github: { refStates: [conflict] } })
    const wrapper = mountHistory()
    await flushPromises()
    await button(wrapper, 'Resolve').trigger('click')
    expect(wrapper.get('.test-modal').text()).toContain(conflict.boscaSha)
    expect(wrapper.get('.test-modal').text()).toContain(conflict.githubSha)
    expect(button(wrapper, 'Resolve conflict').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Cancel').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
    await button(wrapper, 'Resolve').trigger('click')
    await wrapper.get('input[aria-label="Keep value from"]').setValue(choice)
    const host = choice === 'GITHUB' ? 'GitHub' : 'Bosca'
    const target = choice === 'GITHUB' ? 'Bosca' : 'GitHub'
    expect(wrapper.get('.test-modal').text()).toContain(`This replaces the ${target} value`)
    const pending = deferred<unknown>()
    mutation.mockReturnValueOnce(pending.promise)
    await wrapper.get('form').trigger('submit')
    expect(print(mutation.mock.calls[0]?.[0])).toContain('resolveRef(input: $input)')
    expect(mutation.mock.calls[0]?.[1]).toEqual({ input: {
      repositoryId: 'repo-1', ref: conflict.ref, resolution: choice,
      expectedBoscaSha: conflict.boscaSha, expectedGitHubSha: conflict.githubSha,
    } })
    for (const control of ['Resolving…', 'Cancel', 'Refresh', 'Push to GitHub', 'Pull requests']) {
      const element = button(wrapper, control).element
      expect(element.hasAttribute('disabled') || element.closest('fieldset')?.hasAttribute('disabled')).toBe(true)
    }
    await wrapper.get('.test-modal > button').trigger('click')
    expect(wrapper.find('.test-modal').exists()).toBe(true)
    const selected = choice === 'GITHUB' ? conflict.githubSha : conflict.boscaSha
    query.mockResolvedValueOnce({ github: { refStates: [{ ...conflict, sha: selected, boscaSha: selected, githubSha: selected, synchronized: true, conflict: false }] } })
    pending.resolve({ github: { resolveRef: {} } })
    await flushPromises()
    expect(wrapper.find('.test-modal').exists()).toBe(false)
    expect(wrapper.text()).toContain(`Conflict resolved using the ${host} value.`)
    expect(wrapper.text()).toContain('In sync')
    expect(wrapper.findAll('button').some(button => button.text() === 'Resolve')).toBe(false)
  })

  it.each(['GITHUB', 'BOSCA'])('reviews an absent %s tag as an explicit deletion', async (choice) => {
    const conflict = { ref: 'refs/tags/release', sha: null, synchronized: false, conflict: true,
      boscaSha: choice === 'BOSCA' ? null : 'a'.repeat(40), githubSha: choice === 'GITHUB' ? null : 'b'.repeat(40), modified: '2026-10-01T00:00:00Z' }
    query.mockResolvedValueOnce({ github: { refStates: [conflict] } })
    const wrapper = mountHistory()
    await flushPromises()
    await button(wrapper, 'Resolve').trigger('click')
    await wrapper.get('input[aria-label="Keep value from"]').setValue(choice)
    expect(wrapper.get('.test-modal').text()).toContain(`This deletes the ref from ${choice === 'GITHUB' ? 'Bosca' : 'GitHub'}`)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ input: {
      repositoryId: 'repo-1', ref: conflict.ref, resolution: choice,
      expectedBoscaSha: conflict.boscaSha, expectedGitHubSha: conflict.githubSha,
    } })
  })

  it('retains a rejected review and requires reopening to use refreshed observations', async () => {
    const conflict = { ref: 'refs/heads/main', sha: null, synchronized: false, conflict: true,
      boscaSha: 'a'.repeat(40), githubSha: 'b'.repeat(40), modified: '2026-10-01T00:00:00Z' }
    query.mockResolvedValueOnce({ github: { refStates: [conflict] } })
    const wrapper = mountHistory()
    await flushPromises()
    await button(wrapper, 'Resolve').trigger('click')
    await wrapper.get('input[aria-label="Keep value from"]').setValue('GITHUB')
    mutation.mockRejectedValueOnce(new Error('The refs changed during resolution'))
    query.mockResolvedValueOnce({ github: { refStates: [{ ...conflict, githubSha: 'c'.repeat(40) }] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('The refs changed during resolution')
    expect(wrapper.get('.test-modal').text()).toContain(conflict.githubSha)
    await button(wrapper, 'Cancel').trigger('click')
    await button(wrapper, 'Resolve').trigger('click')
    expect(wrapper.get('.test-modal').text()).toContain('c'.repeat(40))
    expect(wrapper.get<HTMLInputElement>('input[aria-label="Keep value from"]').element.value).toBe('')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('keeps reconciliation disabled until pairing is enabled', async () => {
    const wrapper = mount(History, { props: { repositoryId: 'repo-1', enabled: false }, global: { stubs } })
    await flushPromises()
    expect(button(wrapper, 'Reconcile').attributes('disabled')).toBeDefined()
    expect(button(wrapper, 'Pull from GitHub').attributes('disabled')).toBeDefined()
    expect(button(wrapper, 'Push to GitHub').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Enable the repository pairing')
    await wrapper.setProps({ enabled: true })
    await button(wrapper, 'Reconcile').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledOnce()
  })

  it('shows PR problems with both observed snapshots and reconciles PRs', async () => {
    const wrapper = mountHistory()
    await flushPromises()
    query.mockResolvedValueOnce({ github: { pullRequestStates: [{
      pullRequestId: 'pr-1', githubNumber: 12, snapshot: null,
      bosca: { title: 'Bosca edit', description: 'Native text', sourceBranch: 'branch', targetBranch: 'main', status: 'OPEN', mergeSha: null },
      github: { title: 'GitHub edit', description: 'Remote text', sourceBranch: 'branch', targetBranch: 'main', status: 'OPEN', mergeSha: null },
      problem: 'Concurrent edits', modified: '2026-10-01T00:00:00Z',
    }] } })
    await button(wrapper, 'Pull requests').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Concurrent edits')
    expect(wrapper.text()).toContain('Native text')
    expect(wrapper.text()).toContain('Remote text')
    expect(wrapper.get('a').attributes('href')).toBe('/git/pulls/pr-1')
    await button(wrapper, 'Reconcile').trigger('click')
    await flushPromises()
    expect(print(mutation.mock.calls[0]?.[0])).toContain('reconcilePullRequests')
  })

  it('maps the selected principal independently of its profile and removes mappings', async () => {
    const wrapper = mount(Mappings, { global: { stubs } })
    await flushPromises()
    expect(print(query.mock.calls[1]?.[0])).toContain('includeDeleted: false')
    expect(wrapper.findComponent(Field).exists()).toBe(true)
    await wrapper.get('input[aria-label="GitHub username"]').setValue('@octocat')
    await wrapper.get('input[aria-label="Bosca user"]').setValue(principal.id)
    query.mockResolvedValueOnce({ github: { users: [{ githubUserId: 99, principalId: principal.id }] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ username: 'octocat', principalId: 'principal-1' })
    await button(wrapper, 'Remove mapping').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1]).toEqual({ githubUserId: 99 })
    expect(wrapper.text()).toContain('Previous deliveries retain their original attribution')
  })

  it('pages principal choices and surfaces mapping failures', async () => {
    query.mockImplementation((document: DocumentNode) => print(document).includes('GitHubSyncPrincipals')
      ? { security: { principals: { all: Array.from({ length: 26 }, () => principal) } } }
      : { github: { users: [] } })
    const wrapper = mount(Mappings, { global: { stubs } })
    await flushPromises()
    await button(wrapper, 'Next principals').trigger('click')
    await flushPromises()
    expect(query.mock.calls[2]?.[1]).toEqual({ offset: 25, limit: 26 })
    await wrapper.get('input[aria-label="GitHub username"]').setValue('@octocat')
    await wrapper.get('input[aria-label="Bosca user"]').setValue(principal.id)
    mutation.mockRejectedValueOnce(new Error('Principal is deleted'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Principal is deleted')
  })

  it('keeps only shared user mappings on the global GitHub settings page', async () => {
    const wrapper = mount(Page, { global: { stubs: { ...stubs, GitHubRepositorySync: Pair, GitHubSyncHistory: History, GitHubUserMappings: Mappings }, mocks: { buildBreadcrumb: (...parts: string[]) => parts } } })
    await flushPromises()
    expect(wrapper.findComponent(Mappings).exists()).toBe(true)
    expect(wrapper.findComponent(Pair).exists()).toBe(false)
    expect(wrapper.findComponent(History).exists()).toBe(false)
    expect(wrapper.find('input[aria-label="Repository"]').exists()).toBe(false)
    expect(query.mock.calls.every(([document]) => !print(document).includes('GitHubSyncPair'))).toBe(true)
  })
})
