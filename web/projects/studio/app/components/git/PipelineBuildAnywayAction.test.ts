import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PipelineBuildAnywayAction from './PipelineBuildAnywayAction.vue'

const query = vi.fn()
const toast = { success: vi.fn(), error: vi.fn() }
const waitingJob: {
  id: string
  name: string
  status: string
  requirements: unknown
  pipelineRequirements: unknown
  requirementsSatisfiedAt: string | null
} = {
  id: 'job-1',
  name: 'publish',
  status: 'QUEUED',
  requirements: [],
  pipelineRequirements: [{ repository: 'bosca', pipeline: 'build' }],
  requirementsSatisfiedAt: null,
}

const ButtonStub = {
  emits: ['click'],
  template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
}

const ModalStub = {
  emits: ['close'],
  template: '<div class="modal-stub"><slot /><slot name="footer" /></div>',
}

function mountAction(job = waitingJob, runStatus = 'RUNNING') {
  return mount(PipelineBuildAnywayAction, {
    props: { job, runStatus },
    global: { stubs: { Button: ButtonStub, Modal: ModalStub } },
  })
}

describe('PipelineBuildAnywayAction', () => {
  beforeEach(() => {
    query.mockReset()
    toast.success.mockReset()
    toast.error.mockReset()
    vi.stubGlobal('useGraphQL', () => ({ query }))
    vi.stubGlobal('useToast', () => toast)
  })

  it('confirms and submits the explicit override reason, then reports completion', async () => {
    query.mockResolvedValue({ git: { runPipelineJobAnyway: { id: 'job-1', status: 'QUEUED' } } })
    const wrapper = mountAction()

    await wrapper.get('.build-anyway-trigger').trigger('click')
    await wrapper.get('#build-anyway-reason').setValue('upstream is being repaired')
    await wrapper.get('.build-anyway-confirm').trigger('click')
    await flushPromises()

    expect(query).toHaveBeenCalledWith(expect.anything(), {
      jobId: 'job-1',
      reason: 'upstream is being repaired',
    })
    expect(toast.success).toHaveBeenCalledWith('publish queued to build anyway')
    expect(wrapper.emitted('busy')).toEqual([[true], [false]])
    expect(wrapper.emitted('completed')).toHaveLength(1)
  })

  it('is hidden when the external requirement gate is already satisfied', () => {
    const wrapper = mountAction({ ...waitingJob, requirementsSatisfiedAt: '2026-08-10T00:00:00Z' })
    expect(wrapper.find('.build-anyway-trigger').exists()).toBe(false)
  })

  it('reports mutation failures without completing', async () => {
    query.mockRejectedValue(new Error('The job changed concurrently'))
    const wrapper = mountAction()

    await wrapper.get('.build-anyway-trigger').trigger('click')
    await wrapper.get('.build-anyway-confirm').trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('The job changed concurrently')
    expect(wrapper.emitted('completed')).toBeUndefined()
  })
})
