/**
 * Conversion-goal CRUD state and mutations for the experiment detail page.
 *
 * Encapsulates the add/edit modal form, deletion confirmation, and the
 * GraphQL mutations that persist changes. The metric type, event type, and
 * role option sets are also exported so the modal can render the same
 * vocabulary the server understands.
 *
 * The "any event type" sentinel (`__any__`) exists because the dropdown
 * cannot bind to an empty string and still distinguish "no value selected"
 * from "match every event type". It is translated to `null` before being
 * sent to the server.
 */

import type { SelectOption } from '@bosca/ui'
import gql from 'graphql-tag'

const ANY_EVENT_TYPE = '__any__'

const EVENT_TYPE_OPTIONS: SelectOption[] = [
  { value: ANY_EVENT_TYPE, label: '(any event type)' },
  { value: 'Session', label: 'Session' },
  { value: 'Interaction', label: 'Interaction' },
  { value: 'Impression', label: 'Impression' },
  { value: 'Completion', label: 'Completion' },
  { value: 'Installation', label: 'Installation' },
  { value: 'Error', label: 'Error' },
]

const METRIC_TYPE_OPTIONS: SelectOption[] = [
  { value: 'UNIQUE_CONVERSION', label: 'Unique Conversion (% of users who converted)' },
  { value: 'EVENT_COUNT', label: 'Event Count (events per user)' },
  { value: 'SESSION_DURATION', label: 'Session Duration (average observed seconds)' },
]

const ROLE_OPTIONS: SelectOption[] = [
  { value: 'PRIMARY', label: 'Primary (drives ship / no-ship)' },
  { value: 'SECONDARY', label: 'Secondary (informational; downgrades ship to inconclusive)' },
  { value: 'GUARDRAIL', label: 'Guardrail (regression triggers HALT and rollback)' },
]

const ITEM_EXTRA_KEY = /^[a-z][a-z0-9_]{0,127}$/

export interface ConversionGoalFormValues {
  name: string
  eventType: string
  elementType: string
  elementId: string
  pagePath: string
  pagePathPrefixes: string
  itemExtraKey: string
  itemExtraValueEnabled: boolean
  itemExtraValue: string
  metricType: string
  role: string
  cupedEnabled: boolean
  cupedEventType: string
  cupedElementType: string
  cupedElementId: string
  cupedPagePath: string
  cupedLookbackWindow: string
}

/** Builds and validates the complete GraphQL goal input from modal state. */
export function buildConversionGoalInput(values: ConversionGoalFormValues) {
  const name = values.name.trim()
  if (!name) throw new Error('Goal name is required')

  const isSessionDuration = values.metricType === 'SESSION_DURATION'
  const itemExtraKey = isSessionDuration ? null : values.itemExtraKey.trim() || null
  const pagePathPrefixes = isSessionDuration
    ? []
    : [...new Set(values.pagePathPrefixes.split(/[\n,]/).map((value) => value.trim()).filter(Boolean))]
  const itemExtraValue = isSessionDuration || !values.itemExtraValueEnabled ? null : values.itemExtraValue
  if (itemExtraValue !== null && !itemExtraKey) throw new Error('Item extra value requires a key')
  if (itemExtraKey && !ITEM_EXTRA_KEY.test(itemExtraKey)) {
    throw new Error('Item extra key must be 1–128 characters and match [a-z][a-z0-9_]*')
  }
  if (itemExtraValue !== null && itemExtraValue.length > 512) {
    throw new Error('Item extra value must be at most 512 characters')
  }

  const cupedCovariate = !isSessionDuration && values.cupedEnabled && values.metricType === 'EVENT_COUNT'
    ? {
        eventType: values.cupedEventType === ANY_EVENT_TYPE ? null : values.cupedEventType,
        elementType: values.cupedElementType.trim() || null,
        elementId: values.cupedElementId.trim() || null,
        pagePath: values.cupedPagePath.trim() || null,
        lookbackWindow: values.cupedLookbackWindow.trim() || 'P14D',
      }
    : null

  return {
    name,
    eventType: isSessionDuration || values.eventType === ANY_EVENT_TYPE ? null : values.eventType,
    elementType: isSessionDuration ? null : values.elementType.trim() || null,
    elementId: isSessionDuration ? null : values.elementId.trim() || null,
    pagePath: isSessionDuration ? null : values.pagePath.trim() || null,
    pagePathPrefixes,
    itemExtraKey,
    itemExtraValue,
    metricType: values.metricType,
    role: values.role,
    cupedCovariate,
  }
}

export interface ConversionGoalSnapshot {
  id: string
  name: string
  eventType: string | null
  elementType: string | null
  elementId: string | null
  metricType: string
  pagePath: string | null
  pagePathPrefixes: string[]
  itemExtraKey: string | null
  itemExtraValue: string | null
  role: string
  cupedCovariate: {
    eventType: string | null
    elementType: string | null
    elementId: string | null
    pagePath: string | null
    lookbackWindow: string | null
  } | null
}

const addGoalGql = gql`
  mutation AddConversionGoal($experimentId: UUID!, $goal: ConversionGoalInput!) {
    experiments { addConversionGoal(experimentId: $experimentId, goal: $goal) { id } }
  }
`

const editGoalGql = gql`
  mutation EditConversionGoal($id: UUID!, $goal: ConversionGoalInput!) {
    experiments { editConversionGoal(id: $id, goal: $goal) { id } }
  }
`

const deleteGoalGql = gql`
  mutation DeleteConversionGoal($id: UUID!) {
    experiments { deleteConversionGoal(id: $id) }
  }
`

export function useExperimentGoals(opts: {
  experimentId: Ref<string>
  refresh: () => Promise<unknown> | unknown
}) {
  const toast = useToast()
  const { mutation: gqlMutation } = useGraphQL()

  const goalModalOpen = ref(false)
  const editingGoalId = ref<string | null>(null)
  const goalSaving = ref(false)
  const goalDeleteTarget = ref<ConversionGoalSnapshot | null>(null)
  const goalDeleteLoading = ref(false)

  const goalName = ref('')
  const goalEventType = ref<string>(ANY_EVENT_TYPE)
  const goalElementType = ref('')
  const goalElementId = ref('')
  const goalPagePath = ref('')
  const goalPagePathPrefixes = ref('')
  const goalItemExtraKey = ref('')
  const goalItemExtraValueEnabled = ref(false)
  const goalItemExtraValue = ref('')
  const goalMetricType = ref<string>('UNIQUE_CONVERSION')
  const goalRole = ref<string>('PRIMARY')

  // CUPED covariate (only meaningful for EVENT_COUNT goals)
  const cupedEnabled = ref(false)
  const cupedEventType = ref<string>(ANY_EVENT_TYPE)
  const cupedElementType = ref('')
  const cupedElementId = ref('')
  const cupedPagePath = ref('')
  const cupedLookbackWindow = ref('P14D')

  function resetForm() {
    editingGoalId.value = null
    goalName.value = ''
    goalEventType.value = ANY_EVENT_TYPE
    goalElementType.value = ''
    goalElementId.value = ''
    goalPagePath.value = ''
    goalPagePathPrefixes.value = ''
    goalItemExtraKey.value = ''
    goalItemExtraValueEnabled.value = false
    goalItemExtraValue.value = ''
    goalMetricType.value = 'UNIQUE_CONVERSION'
    goalRole.value = 'PRIMARY'
    cupedEnabled.value = false
    cupedEventType.value = ANY_EVENT_TYPE
    cupedElementType.value = ''
    cupedElementId.value = ''
    cupedPagePath.value = ''
    cupedLookbackWindow.value = 'P14D'
  }

  function openAddGoal() {
    resetForm()
    goalModalOpen.value = true
  }

  function openEditGoal(goal: ConversionGoalSnapshot) {
    editingGoalId.value = goal.id
    goalName.value = goal.name ?? ''
    goalEventType.value = goal.eventType ?? ANY_EVENT_TYPE
    goalElementType.value = goal.elementType ?? ''
    goalElementId.value = goal.elementId ?? ''
    goalPagePath.value = goal.pagePath ?? ''
    goalPagePathPrefixes.value = goal.pagePathPrefixes?.join('\n') ?? ''
    goalItemExtraKey.value = goal.itemExtraKey ?? ''
    goalItemExtraValueEnabled.value = goal.itemExtraValue !== null
    goalItemExtraValue.value = goal.itemExtraValue ?? ''
    goalMetricType.value = goal.metricType ?? 'UNIQUE_CONVERSION'
    goalRole.value = goal.role ?? 'PRIMARY'
    if (goal.cupedCovariate) {
      cupedEnabled.value = true
      cupedEventType.value = goal.cupedCovariate.eventType ?? ANY_EVENT_TYPE
      cupedElementType.value = goal.cupedCovariate.elementType ?? ''
      cupedElementId.value = goal.cupedCovariate.elementId ?? ''
      cupedPagePath.value = goal.cupedCovariate.pagePath ?? ''
      cupedLookbackWindow.value = goal.cupedCovariate.lookbackWindow ?? 'P14D'
    } else {
      cupedEnabled.value = false
      cupedEventType.value = ANY_EVENT_TYPE
      cupedElementType.value = ''
      cupedElementId.value = ''
      cupedPagePath.value = ''
      cupedLookbackWindow.value = 'P14D'
    }
    goalModalOpen.value = true
  }

  watch(goalMetricType, (metricType) => {
    if (metricType !== 'SESSION_DURATION') return
    goalEventType.value = ANY_EVENT_TYPE
    goalElementType.value = ''
    goalElementId.value = ''
    goalPagePath.value = ''
    goalPagePathPrefixes.value = ''
    goalItemExtraKey.value = ''
    goalItemExtraValueEnabled.value = false
    goalItemExtraValue.value = ''
    cupedEnabled.value = false
  })

  async function saveGoal() {
    goalSaving.value = true
    try {
      const goal = buildConversionGoalInput({
        name: goalName.value,
        eventType: goalEventType.value,
        elementType: goalElementType.value,
        elementId: goalElementId.value,
        pagePath: goalPagePath.value,
        pagePathPrefixes: goalPagePathPrefixes.value,
        itemExtraKey: goalItemExtraKey.value,
        itemExtraValueEnabled: goalItemExtraValueEnabled.value,
        itemExtraValue: goalItemExtraValue.value,
        metricType: goalMetricType.value,
        role: goalRole.value,
        cupedEnabled: cupedEnabled.value,
        cupedEventType: cupedEventType.value,
        cupedElementType: cupedElementType.value,
        cupedElementId: cupedElementId.value,
        cupedPagePath: cupedPagePath.value,
        cupedLookbackWindow: cupedLookbackWindow.value,
      })

      if (editingGoalId.value) {
        await gqlMutation(editGoalGql, { id: editingGoalId.value, goal })
        toast.success('Goal updated')
      } else {
        await gqlMutation(addGoalGql, { experimentId: opts.experimentId.value, goal })
        toast.success('Goal added')
      }
      goalModalOpen.value = false
      resetForm()
      await opts.refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to save goal')
    } finally {
      goalSaving.value = false
    }
  }

  function requestDeleteGoal(goal: ConversionGoalSnapshot) {
    goalDeleteTarget.value = goal
  }

  async function confirmDeleteGoal() {
    if (!goalDeleteTarget.value) return
    goalDeleteLoading.value = true
    try {
      await gqlMutation(deleteGoalGql, { id: goalDeleteTarget.value.id })
      goalDeleteTarget.value = null
      toast.success('Goal deleted')
      await opts.refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete goal')
    } finally {
      goalDeleteLoading.value = false
    }
  }

  return {
    ANY_EVENT_TYPE,
    EVENT_TYPE_OPTIONS,
    METRIC_TYPE_OPTIONS,
    ROLE_OPTIONS,
    // Modal state
    goalModalOpen,
    editingGoalId,
    goalSaving,
    goalDeleteTarget,
    goalDeleteLoading,
    // Form fields
    goalName,
    goalEventType,
    goalElementType,
    goalElementId,
    goalPagePath,
    goalPagePathPrefixes,
    goalItemExtraKey,
    goalItemExtraValueEnabled,
    goalItemExtraValue,
    goalMetricType,
    goalRole,
    cupedEnabled,
    cupedEventType,
    cupedElementType,
    cupedElementId,
    cupedPagePath,
    cupedLookbackWindow,
    // Actions
    openAddGoal,
    openEditGoal,
    saveGoal,
    requestDeleteGoal,
    confirmDeleteGoal,
  }
}
