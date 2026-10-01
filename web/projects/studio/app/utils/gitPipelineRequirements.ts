export interface PipelineRequirementGateJob {
  status: string
  requirements: unknown
  pipelineRequirements: unknown
  requirementsSatisfiedAt: string | null
}

function hasEntries(value: unknown): boolean {
  return Array.isArray(value) && value.length > 0
}

/** Whether Studio may offer the explicit external-requirement override for this job/run state. */
export function canRunPipelineJobAnyway(job: PipelineRequirementGateJob, runStatus: string): boolean {
  const hasExternalRequirements = hasEntries(job.requirements) || hasEntries(job.pipelineRequirements)
  if (!hasExternalRequirements || job.requirementsSatisfiedAt !== null) return false
  if (job.status === 'QUEUED') return runStatus === 'QUEUED' || runStatus === 'RUNNING'
  const terminalJob = job.status === 'FAILURE' || job.status === 'CANCELLED'
  const terminalRun = runStatus === 'FAILURE' || runStatus === 'CANCELLED'
  return terminalJob && terminalRun
}
