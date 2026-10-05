import gql from 'graphql-tag'

const cancelJobGql = gql`
  mutation CancelJob($jobId: UUID!) {
    jobs {
      cancel(jobId: $jobId)
    }
  }
`

export interface JobCancelTarget {
  jobName: string
  displayName: string
  jobId: string
}

const confirmOpen = ref(false)
const pendingJob = ref<JobCancelTarget | null>(null)
const cancellingJobId = ref<string | null>(null)

export function useJobCancel() {
  const { mutation: gqlMutation } = useGraphQL()
  const toast = useToast()

  function requestCancel(job: JobCancelTarget) {
    pendingJob.value = job
    confirmOpen.value = true
  }

  function dismissCancel() {
    confirmOpen.value = false
    pendingJob.value = null
  }

  async function confirmCancel() {
    const job = pendingJob.value
    if (!job) return
    confirmOpen.value = false
    cancellingJobId.value = job.jobId
    try {
      await gqlMutation(cancelJobGql, { jobId: job.jobId })
      toast.success(`Job cancelled: ${job.displayName}`)
      window.dispatchEvent(new CustomEvent('job-cancelled'))
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to cancel job')
    } finally {
      cancellingJobId.value = null
      pendingJob.value = null
    }
  }

  return {
    requestCancel,
    confirmCancel,
    dismissCancel,
    confirmOpen,
    pendingJob,
    cancellingJobId,
  }
}
