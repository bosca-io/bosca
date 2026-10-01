import { describe, expect, it } from 'vitest'
import { canRunPipelineJobAnyway } from './gitPipelineRequirements'

const waitingJob = {
  status: 'QUEUED',
  requirements: [],
  pipelineRequirements: [{ repository: 'bosca', pipeline: 'build' }],
  requirementsSatisfiedAt: null,
}

describe('canRunPipelineJobAnyway', () => {
  it('allows a queued external-requirement gate in an active run', () => {
    expect(canRunPipelineJobAnyway(waitingJob, 'RUNNING')).toBe(true)
  })

  it('allows reopening a terminal requirement failure only in a terminal failed run', () => {
    expect(canRunPipelineJobAnyway({ ...waitingJob, status: 'FAILURE' }, 'FAILURE')).toBe(true)
    expect(canRunPipelineJobAnyway({ ...waitingJob, status: 'FAILURE' }, 'RUNNING')).toBe(false)
  })

  it('does not offer an override for ungated or already satisfied jobs', () => {
    expect(canRunPipelineJobAnyway({ ...waitingJob, pipelineRequirements: [] }, 'RUNNING')).toBe(false)
    expect(canRunPipelineJobAnyway({ ...waitingJob, requirementsSatisfiedAt: '2026-08-10T00:00:00Z' }, 'RUNNING')).toBe(false)
  })
})
