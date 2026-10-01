/**
 * Rollout-policy editor state and persistence for the experiment detail page.
 *
 * The four supported modes have asymmetric input requirements (steps for
 * SCHEDULED/ADAPTIVE_STEPS, increment % for ADAPTIVE_CONTINUOUS, nothing
 * for MANUAL), so the form fields stay independent and `rolloutPolicyPayload()`
 * decides which to include when serialising. `hydrate()` populates form
 * state from the loaded experiment and seeds sensible defaults when no
 * policy has been saved yet.
 *
 * The composable does NOT own the save mutation — `savePolicy` builds the
 * input via the caller-supplied `buildExperimentInput` so it goes through
 * the same full-experiment edit path that every other field uses, which
 * prevents unrelated fields from being silently nulled out.
 */

import type { SelectOption } from '@bosca/ui'

export type RolloutMode = 'MANUAL' | 'SCHEDULED_STEPS' | 'ADAPTIVE_STEPS' | 'ADAPTIVE_CONTINUOUS'

const ROLLOUT_MODE_OPTIONS: SelectOption[] = [
  { value: 'MANUAL', label: 'Manual (halt-only, no auto-advance)' },
  { value: 'SCHEDULED_STEPS', label: 'Scheduled Steps (time-driven ramp)' },
  { value: 'ADAPTIVE_STEPS', label: 'Adaptive Steps (advance per SHIP verdict)' },
  { value: 'ADAPTIVE_CONTINUOUS', label: 'Adaptive Continuous (+N% per SHIP verdict)' },
]

interface PolicyStep {
  weightPercent: number
  afterDuration: string
}

interface RolloutPolicySnapshot {
  mode: string
  treatmentVariationKey: string | null
  steps: Array<{ weightPercent: number, afterDuration: string | null }>
  incrementPercent: number | null
  minConfidence: number | null
  guardrailThreshold: number | null
  guardrailMinRegressionPercent: number | null
  haltOnGuardrail: boolean | null
}

export function useExperimentRolloutPolicy(opts: {
  involvedVariationKeys: Ref<string[]>
  controlVariationKey: Ref<string | null>
  savePolicy: (payload: ReturnType<ReturnType<typeof useExperimentRolloutPolicy>['rolloutPolicyPayload']>) => Promise<void>
}) {
  const toast = useToast()

  const policyEnabled = ref(false)
  const policyMode = ref<RolloutMode>('MANUAL')
  const policyTreatmentVariationKey = ref('')
  const policySteps = ref<PolicyStep[]>([])
  const policyIncrementPercent = ref(5)
  const policyMinConfidence = ref(0.95)
  const policyGuardrailThreshold = ref(0.95)
  const policyGuardrailMinRegressionPercent = ref(0.5)
  const policyHaltOnGuardrail = ref(true)
  const savingPolicy = ref(false)

  function defaultSteps(): PolicyStep[] {
    return [
      { weightPercent: 10, afterDuration: 'P1D' },
      { weightPercent: 25, afterDuration: 'P1D' },
      { weightPercent: 50, afterDuration: 'P1D' },
      { weightPercent: 100, afterDuration: 'P1D' },
    ]
  }

  function hydrate(policy: RolloutPolicySnapshot | null | undefined) {
    if (!policy) {
      policyEnabled.value = false
      policyMode.value = 'MANUAL'
      policyTreatmentVariationKey.value =
        opts.involvedVariationKeys.value.find((k) => k !== opts.controlVariationKey.value) ?? ''
      policySteps.value = defaultSteps()
      policyIncrementPercent.value = 5
      policyMinConfidence.value = 0.95
      policyGuardrailThreshold.value = 0.95
      policyGuardrailMinRegressionPercent.value = 0.5
      policyHaltOnGuardrail.value = true
      return
    }
    policyEnabled.value = true
    policyMode.value = (policy.mode as RolloutMode) ?? 'MANUAL'
    policyTreatmentVariationKey.value = policy.treatmentVariationKey ?? ''
    policySteps.value = Array.isArray(policy.steps) && policy.steps.length > 0
      ? policy.steps.map((s) => ({
          weightPercent: s.weightPercent ?? 0,
          afterDuration: s.afterDuration ?? 'P1D',
        }))
      : defaultSteps()
    policyIncrementPercent.value = policy.incrementPercent ?? 5
    policyMinConfidence.value = policy.minConfidence ?? 0.95
    policyGuardrailThreshold.value = policy.guardrailThreshold ?? 0.95
    policyGuardrailMinRegressionPercent.value = policy.guardrailMinRegressionPercent ?? 0.5
    policyHaltOnGuardrail.value = policy.haltOnGuardrail ?? true
  }

  function addPolicyStep() {
    const last = policySteps.value[policySteps.value.length - 1]
    const lastWeight = last?.weightPercent ?? 0
    const suggested = Math.min(100, Math.max(lastWeight + 10, Math.round((lastWeight + 100) / 2)))
    policySteps.value.push({ weightPercent: suggested, afterDuration: 'P1D' })
  }

  function removePolicyStep(index: number) {
    policySteps.value.splice(index, 1)
  }

  function rolloutPolicyPayload(): Record<string, unknown> | null {
    if (!policyEnabled.value) return null
    return {
      mode: policyMode.value,
      treatmentVariationKey: policyTreatmentVariationKey.value,
      steps: (policyMode.value === 'SCHEDULED_STEPS' || policyMode.value === 'ADAPTIVE_STEPS')
        ? policySteps.value.map((s) => ({
            weightPercent: Number(s.weightPercent),
            // Adaptive steps don't consume duration — the controller advances
            // off SHIP verdicts instead — so leave it null on the wire.
            afterDuration: policyMode.value === 'SCHEDULED_STEPS' ? (s.afterDuration || 'P1D') : null,
          }))
        : null,
      incrementPercent: policyMode.value === 'ADAPTIVE_CONTINUOUS' ? Number(policyIncrementPercent.value) : null,
      minConfidence: Number(policyMinConfidence.value),
      guardrailThreshold: Number(policyGuardrailThreshold.value),
      guardrailMinRegressionPercent: Number(policyGuardrailMinRegressionPercent.value),
      haltOnGuardrail: !!policyHaltOnGuardrail.value,
    }
  }

  async function save() {
    if (policyEnabled.value && !policyTreatmentVariationKey.value) {
      toast.error('Treatment variation is required')
      return
    }
    if (
      policyEnabled.value
      && (policyMode.value === 'SCHEDULED_STEPS' || policyMode.value === 'ADAPTIVE_STEPS')
      && policySteps.value.length === 0
    ) {
      toast.error('Step list is required for this mode')
      return
    }
    savingPolicy.value = true
    try {
      await opts.savePolicy(rolloutPolicyPayload())
      toast.success(policyEnabled.value ? 'Rollout policy saved' : 'Rollout policy cleared')
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to save rollout policy')
    } finally {
      savingPolicy.value = false
    }
  }

  return {
    ROLLOUT_MODE_OPTIONS,
    policyEnabled,
    policyMode,
    policyTreatmentVariationKey,
    policySteps,
    policyIncrementPercent,
    policyMinConfidence,
    policyGuardrailThreshold,
    policyGuardrailMinRegressionPercent,
    policyHaltOnGuardrail,
    savingPolicy,
    hydrate,
    addPolicyStep,
    removePolicyStep,
    rolloutPolicyPayload,
    save,
  }
}
