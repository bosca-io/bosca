import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import PipelineRunPage from './[id].vue'

const refresh = vi.fn()
const query = vi.fn()
const useAsyncQuery = vi.fn()
const push = vi.fn()
const toast = {
  success: vi.fn(),
  error: vi.fn(),
}

const baseRun = {
  id: 'run-1',
  pipelineId: 'pipeline-1',
  repositoryId: 'repository-1',
  commitSha: 'abcdef1234567890',
  ref: 'refs/heads/main',
  triggerType: 'PUSH',
  triggeredBy: null,
  status: 'FAILURE',
  number: 42,
  concurrencyGroup: null,
  created: '2026-07-27T00:00:00Z',
  started: '2026-07-27T00:01:00Z',
  finished: '2026-07-27T00:02:00Z',
  durationSeconds: 60,
  jobs: [{
    id: 'job-1',
    pipelineRunId: 'run-1',
    name: 'build-server',
    status: 'FAILURE',
    runnerLabel: 'linux',
    agentId: null,
    agent: null,
    matrixValues: {},
    dependsOn: [],
    requirements: [],
    pipelineRequirements: [],
    requirementsDeadline: null,
    awaitingRequirements: false,
    errorMessage: 'Pod could not be scheduled',
    started: null,
    finished: '2026-07-27T00:02:00Z',
    steps: [],
  }],
  artifacts: [],
}

let currentRun = structuredClone(baseRun)
let canExecute = true

vi.stubGlobal('useRoute', () => ({ params: { id: 'run-1' } }))
vi.stubGlobal('useRouter', () => ({ push }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('buildBreadcrumb', (...parts: unknown[]) => parts)
vi.stubGlobal('useToast', () => toast)
vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery,
  query,
}))

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    template: '<header><slot name="actions" /></header>',
    props: ['accent', 'breadcrumb', 'title', 'subtitle'],
  },
  Button: {
    template: '<button :disabled="disabled" :title="title"><slot /></button>',
    props: ['size', 'icon', 'accent', 'disabled', 'title'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  PipelineStepLogs: { template: '<div />' },
  SectionCard: { template: '<section><slot /></section>', props: ['title', 'glass'] },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
  ConfirmModal: {
    template: '<div class="confirm-modal"><button class="confirm-delete" @click="$emit(\'confirm\')">Confirm delete</button></div>',
    props: ['title', 'subtitle', 'loading'],
    emits: ['close', 'confirm'],
  },
}

function mountPage() {
  return mount(PipelineRunPage, { global: { stubs } })
}

describe('Pipeline Run Page job controls', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    currentRun = structuredClone(baseRun)
    canExecute = true
    useAsyncQuery.mockReturnValue({
      data: ref({ git: { pipelineRun: currentRun } }),
      status: ref('success'),
      refresh,
    })
    query.mockImplementation(async (document: DocumentNode) => {
      const operation = print(document)
      if (operation.includes('RepoName')) {
        return { git: {
          repositoryById: { name: 'Server', canExecute },
          pipelines: [
            { id: 'another-pipeline', name: 'Deploy' },
            { id: 'pipeline-1', name: 'Build server' },
          ],
        } }
      }
      if (operation.includes('RerunPipelineJob')) {
        return { git: { rerunPipelineJob: { id: 'job-1', status: 'QUEUED' } } }
      }
      if (operation.includes('RerunFailedPipelineJobs')) {
        return { git: { rerunFailedJobs: { id: 'run-1', status: 'QUEUED' } } }
      }
      if (operation.includes('CancelPipelineJob')) {
        return { git: { cancelPipelineJob: { id: 'job-1', status: 'CANCELLED' } } }
      }
      if (operation.includes('DeletePipelineRun')) {
        return { git: { deletePipelineRun: true } }
      }
      throw new Error(`Unexpected operation: ${operation}`)
    })
  })

  it('shows the matching pipeline name with the run number in the heading and breadcrumb', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const header = wrapper.findComponent(stubs.PageHeader)
    expect(header.props('title')).toBe('Build server · Run #42')
    expect(header.props('breadcrumb')).toContain('Build server')
    expect(header.props('breadcrumb').at(-1)).toBe('Run #42')
    const metadataCall = query.mock.calls.find(([document]) => print(document as DocumentNode).includes('RepoName'))
    expect(print(metadataCall![0] as DocumentNode)).toContain('pipelines(repositoryId: $id)')
    expect(metadataCall![1]).toEqual({ id: 'repository-1' })
    wrapper.unmount()
  })

  it('keeps the run heading when its pipeline is unavailable', async () => {
    currentRun.pipelineId = 'missing-pipeline'
    const wrapper = mountPage()
    await flushPromises()

    const header = wrapper.findComponent(stubs.PageHeader)
    expect(header.props('title')).toBe('Run #42')
    expect(header.props('breadcrumb')).not.toContain('Build server')
    expect(header.props('breadcrumb')).not.toContain('Deploy')
    wrapper.unmount()
  })

  it('reruns exactly the selected failed job', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const rerunJob = wrapper.findAll('button').find(button => button.text() === 'Rerun job')
    expect(rerunJob).toBeDefined()

    await rerunJob!.trigger('click')
    await flushPromises()

    const rerunCall = query.mock.calls.find(([document]) =>
      print(document as DocumentNode).includes('RerunPipelineJob'),
    )
    expect(rerunCall?.[1]).toEqual({ jobId: 'job-1' })
    expect(toast.success).toHaveBeenCalledWith('build-server queued again')
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('disables all rerun controls without repository execution permission', async () => {
    canExecute = false
    currentRun.jobs.push({ ...currentRun.jobs[0]!, id: 'job-2', name: 'test-web' })
    const wrapper = mountPage()
    await flushPromises()
    const controls = wrapper.findAll('button').filter(button => ['Rerun', 'Rerun job', 'Rerun failed jobs'].includes(button.text()))
    expect(controls).toHaveLength(4)
    for (const control of controls) {
      expect(control.attributes('disabled')).toBeDefined()
      expect(control.attributes('title')).toBe('Execute permission on the repository is required.')
      await control.trigger('click')
    }
    expect(query.mock.calls.some(([document]) => print(document as DocumentNode).includes('mutation Rerun'))).toBe(false)
    wrapper.unmount()
  })

  it('reruns multiple failed jobs in one mutation before reopening the run', async () => {
    currentRun.jobs.push({
      ...currentRun.jobs[0]!,
      id: 'job-2',
      name: 'test-web',
    })
    const wrapper = mountPage()
    await flushPromises()

    const rerunFailedJobs = wrapper.findAll('button').find(button => button.text() === 'Rerun failed jobs')
    expect(rerunFailedJobs).toBeDefined()

    await rerunFailedJobs!.trigger('click')
    await flushPromises()

    const rerunCall = query.mock.calls.find(([document]) =>
      print(document as DocumentNode).includes('RerunFailedPipelineJobs'),
    )
    expect(rerunCall?.[1]).toEqual({ runId: 'run-1' })
    expect(toast.success).toHaveBeenCalledWith('2 jobs queued again')
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('cancels exactly the selected active job', async () => {
    currentRun.status = 'RUNNING'
    currentRun.jobs[0]!.status = 'RUNNING'
    const wrapper = mountPage()
    await flushPromises()

    const cancelJob = wrapper.findAll('button').find(button => button.text() === 'Cancel job')
    expect(cancelJob).toBeDefined()

    await cancelJob!.trigger('click')
    await flushPromises()

    const cancelCall = query.mock.calls.find(([document]) =>
      print(document as DocumentNode).includes('CancelPipelineJob'),
    )
    expect(cancelCall?.[1]).toEqual({ jobId: 'job-1' })
    expect(toast.success).toHaveBeenCalledWith('build-server cancelled')
    wrapper.unmount()
  })

  it('does not offer a job control for a successful job', async () => {
    currentRun.status = 'SUCCESS'
    currentRun.jobs[0]!.status = 'SUCCESS'

    const wrapper = mountPage()
    await flushPromises()

    const labels = wrapper.findAll('button').map(button => button.text())
    expect(labels).not.toContain('Rerun job')
    expect(labels).not.toContain('Cancel job')
  })

  it('confirms and deletes a terminal run before returning to its pipeline list', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'Delete')!.trigger('click')
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)

    await wrapper.find('.confirm-delete').trigger('click')
    await flushPromises()

    const deleteCall = query.mock.calls.find(([document]) =>
      print(document as DocumentNode).includes('DeletePipelineRun'),
    )
    expect(deleteCall?.[1]).toEqual({ id: 'run-1' })
    expect(toast.success).toHaveBeenCalledWith('Pipeline run #42 deleted')
    expect(push).toHaveBeenCalledWith('/git/repositories/repository-1?tab=Pipelines')
  })

  it('does not offer deletion while a run is active', async () => {
    currentRun.status = 'RUNNING'
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').map(button => button.text())).not.toContain('Delete')
    wrapper.unmount()
  })

  it('shows why a failed job cannot be rerun until its run finishes', async () => {
    currentRun.status = 'RUNNING'
    currentRun.jobs[0]!.status = 'FAILURE'

    const wrapper = mountPage()
    await flushPromises()

    const rerunJob = wrapper.findAll('button').find(button => button.text() === 'Rerun job')
    expect(rerunJob).toBeDefined()
    expect(rerunJob!.attributes('disabled')).toBeDefined()
    expect(rerunJob!.attributes('title')).toBe('Wait for the pipeline run to finish before rerunning this job.')
    wrapper.unmount()
  })

  it('shows the artifact and repository requirements blocking a queued job', async () => {
    currentRun.status = 'QUEUED'
    Object.assign(currentRun.jobs[0]!, {
      status: 'QUEUED',
      errorMessage: null,
      finished: null,
      awaitingRequirements: true,
      requirements: [{
        type: 'maven',
        namespace: 'bosca-maven',
        coordinate: 'io.bosca:core-git:6.8.0',
      }],
      pipelineRequirements: [{
        repository: 'bosca-core',
        pipeline: 'release',
        ref: 'refs/tags/6.8.0',
      }],
      requirementsDeadline: '2026-07-27T00:30:00Z',
    })

    const wrapper = mountPage()
    await flushPromises()

    const waiting = wrapper.get('.job-waiting')
    expect(waiting.text()).toContain('This job is queued until its requirements are available.')
    expect(waiting.text()).toContain('Waiting on artifact')
    expect(waiting.text()).toContain('bosca-maven / io.bosca:core-git:6.8.0 (maven)')
    expect(waiting.text()).toContain('Waiting on repository')
    expect(waiting.text()).toContain('bosca-core / release @ refs/tags/6.8.0')
    expect(waiting.text()).toContain('Requirement deadline:')

    const runQuery = print(useAsyncQuery.mock.calls[0]![1] as DocumentNode)
    expect(runQuery).toContain('requirements')
    expect(runQuery).toContain('pipelineRequirements')
    expect(runQuery).toContain('requirementsDeadline')
    expect(runQuery).toContain('awaitingRequirements')
    wrapper.unmount()
  })

  it('disables run controls while a job mutation is pending', async () => {
    let resolveRerun: ((value: unknown) => void) | undefined
    query.mockImplementation(async (document: DocumentNode) => {
      const operation = print(document)
      if (operation.includes('RepoName')) {
        return { git: { repositoryById: { name: 'Server' } } }
      }
      if (operation.includes('RerunPipelineJob')) {
        return new Promise(resolve => { resolveRerun = resolve })
      }
      throw new Error(`Unexpected operation: ${operation}`)
    })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'Rerun job')!.trigger('click')
    expect(wrapper.findAll('button').find(button => button.text() === 'Rerun')!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').find(button => button.text() === 'Rerun job')!.attributes('disabled')).toBeDefined()

    resolveRerun?.({ git: { rerunPipelineJob: { id: 'job-1', status: 'QUEUED' } } })
    await flushPromises()
  })
})
