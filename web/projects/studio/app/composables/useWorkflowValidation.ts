import type { Ref } from 'vue'

interface Workflow {
  state: string
  stateValid?: string | null
  pending?: string | null
  // Schema type is Int (count of currently-running workflow steps). A prior
  // boolean signature meant `=== true` was always false and `canPublish`
  // wrongly permitted publishing while a job was already running.
  running?: number | null
}

export function useWorkflowValidation(workflow: Ref<Workflow | null | undefined>) {
  const toast = useToast()

  const workflowState = computed(() => {
    const wf = workflow.value
    return wf?.pending ?? wf?.state ?? 'draft'
  })

  const isRunning = computed(() => (workflow.value?.running ?? 0) > 0)

  const stateValidUntil = computed(() => {
    const sv = workflow.value?.stateValid
    return sv ? new Date(sv) : null
  })

  const isStateValid = computed(() => {
    const until = stateValidUntil.value
    if (!until) return false
    return until.getTime() > Date.now()
  })

  const canPublish = computed(() => {
    if (workflowState.value === 'published') return false
    if (isRunning.value) return false
    return true
  })

  const publishBlocked = computed(() => {
    if (isRunning.value) return 'A workflow job is currently running'
    if (workflowState.value === 'published') return 'Already published'
    return null
  })

  function validateBeforeTransition(): boolean {
    if (isRunning.value) {
      toast.warn('A workflow job is running — please wait')
      return false
    }
    return true
  }

  return {
    workflowState,
    isRunning,
    stateValidUntil,
    isStateValid,
    canPublish,
    publishBlocked,
    validateBeforeTransition,
  }
}
