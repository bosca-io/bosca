import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import { buildSchema, print, validate, type DocumentNode } from 'graphql'
import Settings from './RepositorySettings.vue'
import Protection from './RepositoryBranchProtection.vue'
import Permissions from './RepositoryPermissions.vue'
import Webhooks from './RepositoryWebhooks.vue'
import Utilities from './RepositoryUtilities.vue'
import Tabs from '../../../../../packages/ui/src/components/Tabs.vue'

const schema = buildSchema(readFileSync(resolve('schema.graphqls'), 'utf8'))
enableAutoUnmount(afterEach)
const query = vi.fn()
const mutation = vi.fn()
const push = vi.fn()
const toast = { success: vi.fn(), error: vi.fn() }
const route = reactive({ query: {} as Record<string, string> })
const isAdmin = ref(true)
const repository = { id: 'repo-1', name: 'One', diskSizeBytes: 1024 }
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#64748b' }))
vi.stubGlobal('useToast', () => toast)
vi.stubGlobal('useRoute', () => route)
vi.stubGlobal('useRouter', () => ({ push }))
vi.stubGlobal('usePersonas', () => ({ isAdmin }))
function check(document: DocumentNode) { expect(validate(schema, document).map(error => error.message)).toEqual([]) }
vi.stubGlobal('useGraphQL', () => ({
  query: (document: DocumentNode, variables: unknown) => { check(document); return query(document, variables) },
  mutation: (document: DocumentNode, variables: unknown) => { check(document); return mutation(document, variables) },
}))
const Field = defineComponent({
  props: ['modelValue', 'label', 'disabled', 'options'], emits: ['update:modelValue'],
  template: '<label>{{ label }}<input :aria-label="label" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
})
const Pair = defineComponent({ props: ['repositoryId'], emits: ['enabled'], template: '<div>Pair {{ repositoryId }}</div>' })
const History = defineComponent({ props: ['repositoryId', 'enabled'], template: '<div>History {{ repositoryId }}</div>' })
const stubs = {
  Tabs,
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  Button: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  Select: Field, TextInput: Field,
  Modal: { template: '<section class="modal"><slot /><footer><slot name="footer" /></footer></section>' },
  ConfirmModal: { emits: ['confirm', 'close'], template: '<button class="confirm-delete" @click="$emit(\'confirm\')">Confirm removal</button>' },
  Icon: { template: '<span />' }, Badge: { template: '<span><slot /></span>' },
  GlassTable: { props: ['rows'], emits: ['row-action'], template: '<div><button v-for="row in rows" :key="row.groupId" @click="$emit(\'row-action\', { action: \'delete\', row })">Remove {{ row.action }}</button></div>' },
  GitHubRepositorySync: Pair, GitHubSyncHistory: History,
  RepositoryWebhooks: Webhooks, RepositoryBranchProtection: Protection,
  RepositoryPermissions: Permissions, RepositoryUtilities: Utilities,
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const result = wrapper.findAll('button').find(button => button.text() === label)
  if (!result) throw new Error(`Missing button: ${label}`)
  return result
}
const rule = { id: 'rule-1', repositoryId: 'repo-1', pattern: 'main', requiredApprovals: 1,
  requirePullRequest: true, dismissStaleReviews: false, requireCodeOwnerReview: false,
  requireLinearHistory: false, allowForcePush: false, allowDeletion: false, requireStatusChecks: [] }

describe('repository settings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.query = { tab: 'Settings', setting: 'github', ref: 'main' }
    isAdmin.value = true
    push.mockImplementation(({ query }) => { route.query = query })
    mutation.mockResolvedValue({ git: {} })
    query.mockImplementation((document: DocumentNode) => {
      const text = print(document)
      if (text.includes('PermGroups')) return { security: { groups: { all: [{ id: 'group-1', name: 'Builders' }] } } }
      if (text.includes('RepoPerms')) return { git: { repositoryById: { id: 'repo-1', permissions: [{ groupId: 'group-1', action: 'EXECUTE' }] } } }
      if (text.includes('BranchProtection')) return { git: { branchProtectionRules: [rule] } }
      if (text.includes('Webhooks')) return { git: { webhooks: [] } }
      throw new Error(`Unexpected query: ${text}`)
    })
  })

  it('uses the open repository and keeps the selected setting in the URL', async () => {
    const wrapper = mount(Settings, { props: { repository }, global: { stubs } })
    expect(wrapper.findComponent(Tabs).props('modelValue')).toBe('GitHub Sync')
    expect(wrapper.findComponent(Pair).props('repositoryId')).toBe('repo-1')
    expect(wrapper.findComponent(History).props('enabled')).toBe(false)
    wrapper.findComponent(Pair).vm.$emit('enabled', true)
    await flushPromises()
    expect(wrapper.findComponent(History).props('enabled')).toBe(true)
    await button(wrapper, 'Branch Protection').trigger('click')
    await flushPromises()
    expect(push).toHaveBeenLastCalledWith({ query: { tab: 'Settings', setting: 'protection', ref: 'main' } })
    expect(wrapper.findComponent(Tabs).props('modelValue')).toBe('Branch Protection')
    expect(query.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1' })
    expect(wrapper.find('input[aria-label="Repository"]').exists()).toBe(false)
    route.query.setting = 'github'
    await flushPromises()
    const first = wrapper.findComponent(Pair).vm
    await wrapper.setProps({ repository: { ...repository, id: 'repo-2' } })
    expect(wrapper.findComponent(Pair).vm).not.toBe(first)
    expect(wrapper.findComponent(Pair).props('repositoryId')).toBe('repo-2')
    expect(wrapper.findComponent(History).props('enabled')).toBe(false)
  })

  it('does not mount administrator-only GitHub controls for a repository viewer', async () => {
    isAdmin.value = false
    const wrapper = mount(Settings, { props: { repository }, global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('GitHub Sync')
    expect(wrapper.findComponent(Pair).exists()).toBe(false)
    expect(wrapper.findComponent(History).exists()).toBe(false)
    expect(button(wrapper, 'New Webhook').exists()).toBe(true)
    expect(query.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1' })
    expect(query.mock.calls.every(([document]) => !print(document).includes('GitHub'))).toBe(true)
  })

  it('creates and updates protection rules for the open repository', async () => {
    const wrapper = mount(Protection, { props: { repositoryId: 'repo-1' }, global: { stubs } })
    await flushPromises()
    expect(query.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1' })
    await button(wrapper, 'New Rule').trigger('click')
    await wrapper.get('input[aria-label="Branch Pattern"]').setValue('release/*')
    await button(wrapper, 'Create Rule').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toMatchObject({ repositoryId: 'repo-1', input: { pattern: 'release/*' } })
    await wrapper.get('.edit-btn').trigger('click')
    await wrapper.get('input[aria-label="Required Approvals"]').setValue('2')
    await button(wrapper, 'Update Rule').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1]).toMatchObject({ id: rule.id, input: { requiredApprovals: 2 } })
  })

  it('grants and removes Execute permission on the open repository', async () => {
    const wrapper = mount(Permissions, { props: { repositoryId: 'repo-1' }, global: { stubs } })
    await flushPromises()
    await button(wrapper, 'Add Permission').trigger('click')
    expect(wrapper.findAllComponents(Field).find(field => field.props('label') === 'Action')?.props('options')).toContainEqual({ value: 'EXECUTE', label: 'EXECUTE' })
    await wrapper.get('input[aria-label="Group"]').setValue('group-1')
    await wrapper.get('input[aria-label="Action"]').setValue('EXECUTE')
    await button(wrapper, 'Add').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ permission: { entityId: 'repo-1', groupId: 'group-1', action: 'EXECUTE' } })
    await button(wrapper, 'Remove EXECUTE').trigger('click')
    await wrapper.get('.confirm-delete').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1]).toEqual({ permission: { entityId: 'repo-1', groupId: 'group-1', action: 'EXECUTE' } })
  })

  it('creates an outgoing webhook for the open repository', async () => {
    const wrapper = mount(Webhooks, { props: { repositoryId: 'repo-1' }, global: { stubs } })
    await flushPromises()
    expect(query.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1' })
    await button(wrapper, 'New Webhook').trigger('click')
    await wrapper.get('input[aria-label="Payload URL"]').setValue('https://example.test/hook')
    await wrapper.get('input[aria-label="Secret"]').setValue('fixture-secret')
    await button(wrapper, 'Create Webhook').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ repositoryId: 'repo-1', input: {
      url: 'https://example.test/hook', secret: 'fixture-secret', events: ['PUSH'], active: true,
    } })
  })

  it.each(['Run GC', 'Run Repair'])('confirms %s for the open repository and refreshes its metadata', async (label) => {
    const wrapper = mount(Utilities, { props: { repository }, global: { stubs } })
    expect(query).not.toHaveBeenCalled()
    await button(wrapper, label).trigger('click')
    expect(mutation).not.toHaveBeenCalled()
    await button(wrapper, 'Run').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ id: 'repo-1' })
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it.each([Protection, Permissions, Webhooks])('surfaces failures loading repository settings', async (component) => {
    query.mockRejectedValue(new Error('Access denied'))
    const wrapper = mount(component, { props: { repositoryId: 'repo-1' }, global: { stubs } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('Access denied')
    expect(mutation).not.toHaveBeenCalled()
  })
})
