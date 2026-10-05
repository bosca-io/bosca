import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { useWorkflowValidation } from './useWorkflowValidation'

const mockWarn = vi.fn()
vi.stubGlobal('useToast', () => ({ warn: mockWarn }))

beforeEach(() => vi.clearAllMocks())

describe('useWorkflowValidation', () => {
  it('returns draft state for null workflow', () => {
    const wf = ref(null)
    const { workflowState } = useWorkflowValidation(wf)
    expect(workflowState.value).toBe('draft')
  })

  it('returns pending state when present', () => {
    const wf = ref({ state: 'draft', pending: 'review', stateValid: null, running: 0 })
    const { workflowState } = useWorkflowValidation(wf)
    expect(workflowState.value).toBe('review')
  })

  it('returns current state when no pending', () => {
    const wf = ref({ state: 'published', pending: null, stateValid: null, running: 0 })
    const { workflowState } = useWorkflowValidation(wf)
    expect(workflowState.value).toBe('published')
  })

  it('canPublish is false when already published', () => {
    const wf = ref({ state: 'published', pending: null, stateValid: null, running: 0 })
    const { canPublish } = useWorkflowValidation(wf)
    expect(canPublish.value).toBe(false)
  })

  it('canPublish is false when running', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 1 })
    const { canPublish } = useWorkflowValidation(wf)
    expect(canPublish.value).toBe(false)
  })

  it('canPublish is true for draft not running', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 0 })
    const { canPublish } = useWorkflowValidation(wf)
    expect(canPublish.value).toBe(true)
  })

  it('isRunning reflects workflow running state', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 1 })
    const { isRunning } = useWorkflowValidation(wf)
    expect(isRunning.value).toBe(true)
  })

  it('isStateValid is true when stateValid is in the future', () => {
    const future = new Date(Date.now() + 60000).toISOString()
    const wf = ref({ state: 'draft', pending: null, stateValid: future, running: 0 })
    const { isStateValid } = useWorkflowValidation(wf)
    expect(isStateValid.value).toBe(true)
  })

  it('isStateValid is false when stateValid is in the past', () => {
    const past = new Date(Date.now() - 60000).toISOString()
    const wf = ref({ state: 'draft', pending: null, stateValid: past, running: 0 })
    const { isStateValid } = useWorkflowValidation(wf)
    expect(isStateValid.value).toBe(false)
  })

  it('isStateValid is false when stateValid is null', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 0 })
    const { isStateValid } = useWorkflowValidation(wf)
    expect(isStateValid.value).toBe(false)
  })

  it('validateBeforeTransition warns when running', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 1 })
    const { validateBeforeTransition } = useWorkflowValidation(wf)
    expect(validateBeforeTransition()).toBe(false)
    expect(mockWarn).toHaveBeenCalledWith('A workflow job is running — please wait')
  })

  it('validateBeforeTransition returns true when not running', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 0 })
    const { validateBeforeTransition } = useWorkflowValidation(wf)
    expect(validateBeforeTransition()).toBe(true)
    expect(mockWarn).not.toHaveBeenCalled()
  })

  it('publishBlocked returns reason when running', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 1 })
    const { publishBlocked } = useWorkflowValidation(wf)
    expect(publishBlocked.value).toBe('A workflow job is currently running')
  })

  it('publishBlocked returns reason when published', () => {
    const wf = ref({ state: 'published', pending: null, stateValid: null, running: 0 })
    const { publishBlocked } = useWorkflowValidation(wf)
    expect(publishBlocked.value).toBe('Already published')
  })

  it('publishBlocked is null when can publish', () => {
    const wf = ref({ state: 'draft', pending: null, stateValid: null, running: 0 })
    const { publishBlocked } = useWorkflowValidation(wf)
    expect(publishBlocked.value).toBeNull()
  })
})
