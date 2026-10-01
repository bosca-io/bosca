import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import ReleasePipelineSection from './ReleasePipelineSection.vue'

const repository = { repositoryId: 'repo', canEdit: true, canExecute: true }
const plan = {
  pipelineId: 'pipeline', pipelineName: 'Release', repositoryId: 'repo', repositorySlug: 'server',
  triggerType: 'RELEASE', environment: null, promotesFrom: null, inputs: [], jobs: [],
}
const access = ref({
  canManage: true, repositories: [{ ...repository }], environments: [{ key: 'staging', canExecute: true }],
})
const run = {
  id: 'run', pipelineId: 'pipeline', repositoryId: 'repo', ref: 'refs/tags/v1',
  triggerType: 'RELEASE', status: 'FAILURE', number: 1, parameters: {},
  created: '2026-10-01T00:00:00Z', started: null, finished: null, durationSeconds: null,
  jobs: [{
    id: 'job', name: 'build', status: 'FAILURE', dependsOn: [], requirements: [], pipelineRequirements: [],
    requirementsDeadline: null, awaitingRequirements: false, environment: 'staging', approvalRequired: true,
    approvedAt: null, approvalComment: null, awaitingApproval: false, errorMessage: null, attempt: 1,
    started: null, finished: null, steps: [],
  }],
}
let currentRun = structuredClone(run)
const mutation = vi.fn()
const toast = { success: vi.fn(), error: vi.fn(), warn: vi.fn() }

vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useToast', () => toast)
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({
    data: ref({ workOps: { crossProject: {
      releasePlans: [plan, { ...plan, triggerType: 'PROMOTION', environment: 'staging' }],
      releaseRuns: [{ runId: 'run', status: currentRun.status, startedAt: run.created, finishedAt: null, durationMs: null }],
      releaseActionAccess: access.value,
    } } }),
    status: ref('success'), refresh: vi.fn(),
  }),
  query: vi.fn(async () => ({ git: { pipelineRun: currentRun } })),
  mutation,
}))

function mountCockpit() {
  return mount(ReleasePipelineSection, {
    props: { releaseId: 'release', projects: [] },
    global: { stubs: {
      SectionCard: { template: '<section><slot /></section>' },
      Button: {
        props: ['disabled', 'title'], emits: ['click'],
        template: '<button :disabled="disabled" :title="title" @click="$emit(\'click\')"><slot /></button>',
      },
      Badge: { template: '<span><slot /></span>' },
      NuxtLink: { template: '<a><slot /></a>' },
      Modal: { template: '<div class="modal"><slot /><slot name="footer" /></div>' },
    } },
  })
}
let wrapper: ReturnType<typeof mountCockpit> | undefined

function button(label: string) {
  const found = wrapper?.findAll('button').find(candidate => candidate.text() === label)
  if (!found) throw new Error(`Button not found: ${label}`)
  return found
}

describe('release build permissions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    currentRun = structuredClone(run)
    access.value = { canManage: true, repositories: [{ ...repository }], environments: [{ key: 'staging', canExecute: true }] }
    mutation.mockResolvedValue({})
  })
  afterEach(() => wrapper?.unmount())

  it('requires execution permission for release starts, promotions, and job reruns', async () => {
    access.value.repositories[0]!.canExecute = false
    wrapper = mountCockpit()
    await flushPromises()
    for (const label of ['Promote', 'Rerun job']) {
      expect(button(label).attributes('disabled')).toBeDefined()
      expect(button(label).attributes('title')).toContain('Execute permission')
    }
    wrapper.vm.openStart()
    expect(toast.warn).toHaveBeenCalledWith('Execute permission is required on server.')
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(mutation).not.toHaveBeenCalled()
  })

  it('allows an executor to rerun a job while release starts still require edit permission', async () => {
    access.value.repositories[0]!.canEdit = false
    wrapper = mountCockpit()
    await flushPromises()
    wrapper.vm.openStart()
    expect(toast.warn).toHaveBeenCalledWith('Edit permission is required on server.')
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(button('Rerun job').attributes('disabled')).toBeUndefined()
    await button('Rerun job').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ jobId: 'job' })
  })

  it.each([
    { canEdit: true, canExecute: false, environment: true, approveDisabled: true, rejectDisabled: true },
    { canEdit: false, canExecute: true, environment: true, approveDisabled: false, rejectDisabled: false },
    { canEdit: true, canExecute: true, environment: false, approveDisabled: true, rejectDisabled: true },
  ])('gates approval decisions on execute and environment grants, not edit: $canEdit/$canExecute/$environment', async (permissions) => {
    access.value.repositories[0] = { repositoryId: 'repo', canEdit: permissions.canEdit, canExecute: permissions.canExecute }
    access.value.environments[0]!.canExecute = permissions.environment
    currentRun.status = 'QUEUED'
    currentRun.jobs[0]!.status = 'QUEUED'
    currentRun.jobs[0]!.awaitingApproval = true
    wrapper = mountCockpit()
    await flushPromises()
    expect(button('Approve').attributes('disabled') !== undefined).toBe(permissions.approveDisabled)
    expect(button('Reject').attributes('disabled') !== undefined).toBe(permissions.rejectDisabled)
  })
})
