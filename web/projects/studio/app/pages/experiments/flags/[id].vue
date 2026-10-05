<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { SelectOption } from '@bosca/ui'
import TargetingRulesEditor from '~/components/experiments/TargetingRulesEditor.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const flagId = computed(() => route.params.id as string)

// ── Queries & Mutations ─────────────────────────────────────────────

const flagGql = gql`
  query GetFeatureFlag($id: UUID!) {
    featureFlags {
      flag(id: $id) {
        id
        key
        name
        description
        type
        status
        variations
        defaultVariationKey
        targetingRules
        salt
        created
        modified
        experiments {
          id
          name
          status
          targetingRuleId
          created
        }
      }
    }
  }
`

const editFlagGql = gql`
  mutation EditFeatureFlag($id: UUID!, $flag: FeatureFlagInput!) {
    featureFlags { edit(id: $id, flag: $flag) { id } }
  }
`

const setStatusGql = gql`
  mutation SetFlagStatus($id: UUID!, $status: FlagStatus!) {
    featureFlags { setStatus(id: $id, status: $status) { id status } }
  }
`

const regenerateSaltGql = gql`
  mutation RegenerateSalt($id: UUID!) {
    featureFlags { regenerateSalt(id: $id) { id salt } }
  }
`

// Per-variation current assignment counts. Historical transitions live
// in analytics; this query intentionally has no activity window.
const assignmentsGql = gql`
  query GetFlagVariationAssignments($id: UUID!) {
    featureFlags {
      flag(id: $id) {
        id
        variationAssignments {
          variationKey
          assignmentCount
        }
      }
    }
  }
`

// ── Types ───────────────────────────────────────────────────────────

// Conditions are a discriminated union on `type` — see
// `TargetingRulesEditor.vue` for the variant shapes. We keep the shape
// loose at this layer because the editor normalizes and validates
// every rule it commits back through v-model.
type TargetingRule = Record<string, unknown> & { id: string }

interface Variation {
  key: string
  name: string
  description?: string
  value: unknown
}

interface Flag {
  id: string
  key: string
  name: string
  description: string
  type: string
  status: string
  variations: Variation[]
  defaultVariationKey: string
  targetingRules: TargetingRule[]
  salt: string
  created: string
  modified: string
  experiments: Array<{ id: string; name: string; status: string; targetingRuleId: string | null; created: string }>
}

// ── Data Fetching ───────────────────────────────────────────────────

const { data, status, refresh } = useAsyncQuery<{
  featureFlags: { flag: Flag | null }
}>('flag-detail', flagGql, { id: flagId }, { server: false })

const flag = computed(() => data.value?.featureFlags?.flag ?? null)
const isLoading = computed(() => status.value === 'pending')

// ── Edit State ──────────────────────────────────────────────────────

const editKey = ref('')
const editName = ref('')
const editDescription = ref('')
const editType = ref('BOOLEAN')
const editVariations = ref<Variation[]>([])
const editDefaultVariationKey = ref('')
const editTargetingRules = ref<TargetingRule[]>([])
const saving = ref(false)

watch(flag, (f) => {
  if (f) {
    editKey.value = f.key
    editName.value = f.name
    editDescription.value = f.description
    editType.value = f.type
    editVariations.value = Array.isArray(f.variations) ? JSON.parse(JSON.stringify(f.variations)) : []
    editDefaultVariationKey.value = f.defaultVariationKey
    editTargetingRules.value = Array.isArray(f.targetingRules) ? JSON.parse(JSON.stringify(f.targetingRules)) : []
  }
}, { immediate: true })

// ── Variation Helpers ────────────────────────────────────────────────

function defaultVariationValueForType(type: string): unknown {
  switch (type) {
    case 'BOOLEAN': return false
    case 'PERCENTAGE': return 0
    case 'STRING': return ''
    case 'JSON': return {}
    default: return false
  }
}

function addVariation() {
  const idx = editVariations.value.length
  editVariations.value = [
    ...editVariations.value,
    {
      key: `variation-${idx + 1}`,
      name: `Variation ${idx + 1}`,
      description: '',
      value: defaultVariationValueForType(editType.value),
    },
  ]
}

function removeVariation(index: number) {
  const removed = editVariations.value[index]
  editVariations.value = editVariations.value.filter((_, i) => i !== index)
  if (removed?.key === editDefaultVariationKey.value) {
    editDefaultVariationKey.value = editVariations.value[0]?.key || ''
  }
}

function updateVariation(index: number, patch: Partial<Variation>) {
  const next = [...editVariations.value]
  const current = next[index]
  if (!current) return
  const oldKey = current.key
  next[index] = { ...current, ...patch }
  editVariations.value = next
  if (patch.key && patch.key !== oldKey) {
    if (editDefaultVariationKey.value === oldKey) {
      editDefaultVariationKey.value = patch.key
    }
    // Update rule rollouts to keep them valid
    editTargetingRules.value = editTargetingRules.value.map((rule) => ({
      ...rule,
      rollout: Array.isArray(rule.rollout)
        ? rule.rollout.map((r) => r.variationKey === oldKey ? { ...r, variationKey: patch.key! } : r)
        : rule.rollout,
    }))
  }
}

const variationKeyOptions = computed<SelectOption[]>(() =>
  editVariations.value
    .filter(v => v.key !== '' && v.key != null)
    .map(v => ({ label: v.name || v.key, value: v.key }))
)

// ── Save ────────────────────────────────────────────────────────────

async function save() {
  if (!flag.value) return
  saving.value = true
  try {
    const parsedRules = editTargetingRules.value.length > 0 ? editTargetingRules.value : null
    await gqlMutation(editFlagGql, {
      id: flagId.value,
      flag: {
        key: editKey.value,
        name: editName.value,
        description: editDescription.value || null,
        type: editType.value,
        variations: editVariations.value,
        defaultVariationKey: editDefaultVariationKey.value,
        targetingRules: parsedRules,
      },
    })
    toast.success('Flag updated')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save flag')
  } finally {
    saving.value = false
  }
}

// ── Status ──────────────────────────────────────────────────────────

const confirmStatusOpen = ref(false)
const confirmStatusTarget = ref('')

function confirmSetStatus(s: string) {
  confirmStatusTarget.value = s
  confirmStatusOpen.value = true
}

async function performSetStatus() {
  confirmStatusOpen.value = false
  try {
    await gqlMutation(setStatusGql, { id: flagId.value, status: confirmStatusTarget.value })
    toast.success(`Status set to ${confirmStatusTarget.value}`)
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update status')
  }
}

// ── Reshuffle Buckets ───────────────────────────────────────────────

const confirmReshuffleOpen = ref(false)

async function performReshuffle() {
  confirmReshuffleOpen.value = false
  try {
    await gqlMutation(regenerateSaltGql, { id: flagId.value })
    toast.success('Buckets reshuffled — users will be re-bucketed across all rule rollouts')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to regenerate salt')
  }
}

// ── Live distribution panel ─────────────────────────────────────────

interface AssignmentCountRow {
  variationKey: string
  assignmentCount: number
}

const { data: assignmentsData } = useAsyncQuery<{
  featureFlags: { flag: { id: string; variationAssignments: AssignmentCountRow[] } | null }
}>('flag-assignments', assignmentsGql, { id: flagId }, { server: false })

const currentAssignments = computed<AssignmentCountRow[]>(
  () => assignmentsData.value?.featureFlags?.flag?.variationAssignments ?? [],
)

// Render one row per variation in the flag palette so an unassigned
// variation remains visible instead of looking like a missing response.
const liveDistribution = computed(() => {
  const palette: Variation[] = Array.isArray(flag.value?.variations) ? flag.value!.variations : []
  const byKey = new Map<string, number>()
  let total = 0
  for (const assignment of currentAssignments.value) {
    byKey.set(assignment.variationKey, assignment.assignmentCount)
    total += assignment.assignmentCount
  }
  return palette.map((v) => {
    const count = byKey.get(v.key) ?? 0
    return {
      variationKey: v.key,
      variationName: v.name || v.key,
      count,
      sharePercent: total > 0 ? (count / total) * 100 : 0,
    }
  })
})

const totalCurrentAssignments = computed(() =>
  liveDistribution.value.reduce((sum, r) => sum + r.count, 0),
)

// Configured (potential) distribution derived from the saved rules +
// default. Answers: "if every user evaluated right now, how would the
// current configuration distribute them?" — independent of who's active.
interface ConfiguredBlock {
  label: string
  rows: { variationKey: string; variationName: string; sharePercent: number; weight: number }[]
}

const configuredDistribution = computed<ConfiguredBlock[]>(() => {
  const rules: Array<Record<string, unknown>> = Array.isArray(flag.value?.targetingRules)
    ? (flag.value!.targetingRules as unknown as Array<Record<string, unknown>>)
    : []
  const out: ConfiguredBlock[] = []
  rules.forEach((rule, idx) => {
    const rollout = (rule.rollout && typeof rule.rollout === 'object'
      ? rule.rollout as Record<string, unknown>
      : {})
    const weights = Array.isArray(rollout.variationWeights)
      ? rollout.variationWeights as Array<{ variationKey: string; weight: number }>
      : []
    const total = weights.reduce((sum, w) => sum + (w.weight || 0), 0)
    const ruleName = typeof rule.name === 'string' && rule.name ? rule.name : null
    const label = ruleName ? `${ruleName} (Rule ${idx + 1})` : `Rule ${idx + 1}`
    out.push({
      label,
      rows: weights.map((w) => {
        const variation = (flag.value?.variations ?? []).find((v) => v.key === w.variationKey)
        return {
          variationKey: w.variationKey,
          variationName: variation?.name || w.variationKey,
          sharePercent: total > 0 ? ((w.weight || 0) / total) * 100 : 0,
          weight: w.weight || 0,
        }
      }),
    })
  })
  const defaultKey = flag.value?.defaultVariationKey
  if (defaultKey) {
    const variation = (flag.value?.variations ?? []).find((v) => v.key === defaultKey)
    out.push({
      label: 'Default path (no rule matched)',
      rows: [{
        variationKey: defaultKey,
        variationName: variation?.name || defaultKey,
        sharePercent: 100,
        weight: 1,
      }],
    })
  }
  return out
})

// Map rule id → names of running experiments depending on it. Passed to
// the targeting editor so the delete-rule confirm prompt can warn about
// silent breakage. Only RUNNING experiments count; draft/completed ones
// are not at risk.
const runningExperimentsByRuleId = computed<Record<string, string[]>>(() => {
  const byRule: Record<string, string[]> = {}
  for (const exp of flag.value?.experiments ?? []) {
    if (exp.status !== 'RUNNING') continue
    const ruleId = exp.targetingRuleId
    if (!ruleId) continue
    if (!byRule[ruleId]) byRule[ruleId] = []
    byRule[ruleId].push(exp.name || '(unnamed experiment)')
  }
  return byRule
})

// ── Helpers ─────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  ENABLED: '#34d99a',
  DISABLED: '#f59e42',
  ARCHIVED: '#6c7388',
  DRAFT: '#6c7388',
}

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Experiments', 'Feature Flags', flag?.name ?? '…')"
        :title="flag?.name ?? 'Loading…'"
        :subtitle="flag ? `${flag.key} · ${flag.type}` : ''"
      >
        <template #actions>
          <Button
            v-if="flag?.status !== 'ENABLED'"
            size="sm"
            icon="check"
            primary
            accent="#34d99a"
            @click="confirmSetStatus('ENABLED')">Enable</Button>
          <Button
            v-if="flag?.status === 'ENABLED'"
            size="sm"
            icon="x"
            @click="confirmSetStatus('DISABLED')">Disable</Button>
          <Button
            v-if="flag?.status !== 'ARCHIVED'"
            size="sm"
            icon="archive"
            @click="confirmSetStatus('ARCHIVED')">Archive</Button>
          <Button
            size="sm"
            icon="refresh"
            @click="confirmReshuffleOpen = true">Reshuffle Buckets</Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving"
            @click="save">Save</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !flag" style="padding: 40px; text-align: center; color: var(--fg-3)">Loading…</div>

    <template v-else-if="flag">
      <div class="detail-layout">
        <div class="main-content">
          <!-- Configuration -->
          <SectionCard title="Configuration" padded>
            <div class="form-grid">
              <TextInput v-model="editKey" label="Key" mono />
              <TextInput v-model="editName" label="Name" />
            </div>
            <Textarea
              v-model="editDescription"
              label="Description"
              :rows="2"
              style="margin-top: 14px" />
            <div class="form-grid" style="margin-top: 14px">
              <Select
                v-model="editType"
                label="Type"
                :options="[
                  { value: 'BOOLEAN', label: 'Boolean' },
                  { value: 'STRING', label: 'String' },
                  { value: 'PERCENTAGE', label: 'Percentage' },
                  { value: 'JSON', label: 'JSON' },
                ]"
              />
            </div>
          </SectionCard>

          <!-- Variations -->
          <SectionCard title="Variations" padded>
            <template #right>
              <Button
                size="sm"
                icon="plus"
                :accent="accent"
                @click="addVariation">Add Variation</Button>
            </template>

            <div class="variations-list">
              <div
                v-for="(variation, vIdx) in editVariations"
                :key="vIdx"
                class="variation-card"
              >
                <div class="variation-card-header">
                  <Badge :color="accent">V{{ vIdx + 1 }}</Badge>
                  <span v-if="variation.key === editDefaultVariationKey" class="default-tag">DEFAULT</span>
                  <span class="spacer" />
                  <Button
                    size="sm"
                    icon="trash"
                    :disabled="editVariations.length <= 1"
                    @click="removeVariation(vIdx)"
                  />
                </div>

                <div class="form-grid">
                  <TextInput
                    :model-value="variation.key"
                    label="Key"
                    mono
                    @update:model-value="updateVariation(vIdx, { key: $event })"
                  />
                  <TextInput
                    :model-value="variation.name"
                    label="Name"
                    @update:model-value="updateVariation(vIdx, { name: $event })"
                  />
                </div>

                <TextInput
                  :model-value="variation.description ?? ''"
                  label="Description"
                  style="margin-top: 10px"
                  @update:model-value="updateVariation(vIdx, { description: $event })"
                />

                <div class="variation-value-section">
                  <label class="field-label">Value</label>
                  <Switch
                    v-if="editType === 'BOOLEAN'"
                    :model-value="!!variation.value"
                    :label="variation.value ? 'Enabled' : 'Disabled'"
                    @update:model-value="updateVariation(vIdx, { value: $event })"
                  />
                  <TextInput
                    v-else-if="editType === 'STRING'"
                    :model-value="(variation.value as string) ?? ''"
                    @update:model-value="updateVariation(vIdx, { value: $event })"
                  />
                  <TextInput
                    v-else-if="editType === 'PERCENTAGE'"
                    :model-value="String(variation.value)"
                    type="number"
                    @update:model-value="updateVariation(vIdx, { value: Number($event) })"
                  />
                  <JsonEditorVue
                    v-else
                    :model-value="variation.value"
                    :main-menu-bar="false"
                    :navigation-bar="false"
                    class="jse-theme-dark json-editor-sm"
                    @update:model-value="updateVariation(vIdx, { value: $event })"
                  />
                </div>
              </div>
            </div>

            <div style="margin-top: 14px">
              <Select
                v-model="editDefaultVariationKey"
                label="Default Variation"
                :options="variationKeyOptions"
              />
            </div>
          </SectionCard>

          <!--
            Live Distribution: two side-by-side panels.

            Actual — current assignment counts from PostgreSQL. Assignment
            history is emitted separately to analytics.

            Configured — derived from the rules + default. Answers
            "if every user evaluated right now, what would the rollout
            produce?" — independent of who is active. Compare the two
            to see whether live traffic matches intent.
          -->
          <SectionCard title="Live Distribution" padded>
            <template #right>
              <Popover
                trigger="mouseenter"
                placement="bottom-end"
                :delay="150"
              >
                <template #trigger>
                  <button
                    type="button"
                    class="distribution-help-button"
                    aria-label="How live distribution works"
                  >
                    <Icon name="help" :size="15" />
                  </button>
                </template>
                <div class="distribution-help">
                  <p class="distribution-help-title">How live distribution works</p>
                  <p>
                    <strong>Actual</strong> counts each installation once in its currently assigned
                    variation. Evaluating the same variation again does not change the assignment or
                    its assignment time.
                  </p>
                  <p>
                    When evaluation selects a different variation, the current assignment moves and an
                    assignment event is added to analytics for historical analysis. Current assignment
                    writes are asynchronous and best-effort, so these counts can lag during overload.
                    A later evaluation reconciles the installation's current assignment.
                  </p>
                  <p>
                    When an anonymous installation signs in, the principal takes ownership of that same
                    row. Once owned, anonymous evaluations or a different principal cannot replace it.
                  </p>
                  <p>
                    <strong>Configured</strong> shows the normalized weights for each targeting rule
                    plus the default path. It does not estimate how many people match each rule, so it
                    is not an overall predicted split when the flag has multiple rules.
                  </p>
                  <p>
                    After rules, weights, or bucket salt change, an identity keeps its recorded assignment
                    until it evaluates again. If the resulting variation changes, Actual moves it to the
                    new variation and records the transition in analytics.
                  </p>
                </div>
              </Popover>
            </template>
            <div class="distribution-grid">
              <div class="distribution-panel">
                <div class="distribution-panel-header">
                  <span class="distribution-panel-title">Actual</span>
                  <span class="distribution-panel-meta">
                    {{ totalCurrentAssignments.toLocaleString() }} current assignment{{ totalCurrentAssignments === 1 ? '' : 's' }}
                  </span>
                </div>
                <div v-if="totalCurrentAssignments === 0" class="distribution-empty">
                  No installations have been assigned yet.
                </div>
                <div v-else class="distribution-bars">
                  <div
                    v-for="row in liveDistribution"
                    :key="row.variationKey"
                    class="distribution-bar-row"
                  >
                    <span class="distribution-bar-label">{{ row.variationName }}</span>
                    <div class="distribution-bar-track">
                      <div
                        class="distribution-bar-fill"
                        :style="{ width: `${row.sharePercent}%`, background: accent }"
                      />
                    </div>
                    <div class="distribution-bar-values">
                      <span class="mono tabular">{{ row.count.toLocaleString() }}</span>
                      <span class="distribution-bar-pct">{{ row.sharePercent.toFixed(1) }}%</span>
                    </div>
                  </div>
                </div>
              </div>

              <div class="distribution-panel">
                <div class="distribution-panel-header">
                  <span class="distribution-panel-title">Configured</span>
                  <span class="distribution-panel-meta">from rules + default</span>
                </div>
                <div v-if="!configuredDistribution.length" class="distribution-empty">
                  No targeting rules and no default variation set.
                </div>
                <div v-else class="configured-blocks">
                  <div
                    v-for="block in configuredDistribution"
                    :key="block.label"
                    class="configured-block"
                  >
                    <p class="configured-block-label">{{ block.label }}</p>
                    <div
                      v-for="row in block.rows"
                      :key="row.variationKey"
                      class="distribution-bar-row"
                    >
                      <span class="distribution-bar-label">{{ row.variationName }}</span>
                      <div class="distribution-bar-track">
                        <div
                          class="distribution-bar-fill"
                          :style="{ width: `${row.sharePercent}%`, background: '#5ec5ff' }"
                        />
                      </div>
                      <div class="distribution-bar-values">
                        <span class="distribution-bar-pct">{{ row.sharePercent.toFixed(1) }}%</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <p class="distribution-blurb">
              Actual is current operational state. Historical assignment changes are
              recorded as analytics events and are not mixed into these counts.
            </p>
          </SectionCard>

          <!-- Targeting Rules -->
          <SectionCard title="Targeting Rules" padded>
            <p class="targeting-blurb">
              Rules are evaluated top-to-bottom. Each rule's rollout splits matched
              users across variations by weight.
            </p>
            <TargetingRulesEditor
              v-model="editTargetingRules"
              :variations="editVariations"
              :accent="accent"
              :running-experiments-by-rule-id="runningExperimentsByRuleId"
            />
          </SectionCard>

        </div>

        <!-- Sidebar -->
        <div class="sidebar">
          <SectionCard title="Status" padded>
            <div class="status-info">
              <div class="status-row">
                <span class="status-label">Status</span>
                <Badge :color="STATUS_COLORS[flag.status] ?? '#6c7388'">{{ flag.status }}</Badge>
              </div>
              <div class="status-row">
                <span class="status-label">Type</span>
                <Badge :color="accent">{{ flag.type }}</Badge>
              </div>
              <div class="status-row">
                <span class="status-label">Default</span>
                <span class="mono value-text">{{ flag.defaultVariationKey }}</span>
              </div>
              <div class="status-row">
                <span class="status-label">Variations</span>
                <span class="mono value-text">{{ flag.variations?.length ?? 0 }}</span>
              </div>
              <div class="status-row">
                <span class="status-label">Rules</span>
                <span class="mono value-text">{{ flag.targetingRules?.length ?? 0 }}</span>
              </div>
              <div class="status-row">
                <span class="status-label">Salt</span>
                <span class="mono value-text salt-text">{{ flag.salt?.slice(0, 12) }}…</span>
              </div>
            </div>
          </SectionCard>

          <SectionCard v-if="flag.experiments?.length" title="Experiments" padded>
            <div class="experiment-list">
              <div
                v-for="exp in flag.experiments"
                :key="exp.id"
                class="experiment-row"
                @click="router.push(`/experiments/exp/${exp.id}`)"
              >
                <Icon name="beaker" :size="14" :color="accent" />
                <div class="experiment-info">
                  <span class="experiment-name">{{ exp.name }}</span>
                  <span class="experiment-meta">
                    {{ exp.status?.toLowerCase() }}
                    <template v-if="exp.targetingRuleId"> · rule {{ exp.targetingRuleId.slice(0, 8) }}</template>
                    <template v-else> · default path</template>
                  </span>
                </div>
                <Badge :color="exp.status === 'RUNNING' ? 'var(--brand-2)' : '#6c7388'">{{ exp.status }}</Badge>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Dates" padded>
            <div class="status-info">
              <div class="status-row">
                <span class="status-label">Created</span>
                <span class="value-text">{{ formatDate(flag.created) }}</span>
              </div>
              <div class="status-row">
                <span class="status-label">Modified</span>
                <span class="value-text">{{ formatDate(flag.modified) }}</span>
              </div>
            </div>
          </SectionCard>
        </div>
      </div>

      <!-- Confirmation Modals -->
      <ConfirmModal
        v-if="confirmStatusOpen"
        :title="`${confirmStatusTarget.charAt(0)}${confirmStatusTarget.slice(1).toLowerCase()} this flag?`"
        :subtitle="confirmStatusTarget === 'ARCHIVED' ? 'Archiving cannot be easily reversed.' : 'This will change the flag evaluation behavior.'"
        :confirm-label="confirmStatusTarget.charAt(0) + confirmStatusTarget.slice(1).toLowerCase()"
        @close="confirmStatusOpen = false"
        @confirm="performSetStatus"
      >
        <p v-if="confirmStatusTarget === 'ARCHIVED'" class="modal-text">
          Archiving this flag will make it unavailable for evaluation. Running experiments
          that depend on it may be affected.
        </p>
        <p v-else-if="confirmStatusTarget === 'DISABLED'" class="modal-text">
          Disabling this flag will stop it from being evaluated. All users will receive the
          default variation until the flag is re-enabled.
        </p>
        <p v-else class="modal-text">
          Enabling this flag will make it live. Users will start receiving variations
          according to the configured targeting rules.
        </p>
      </ConfirmModal>

      <ConfirmModal
        v-if="confirmReshuffleOpen"
        title="Reshuffle buckets?"
        subtitle="This action cannot be undone."
        confirm-label="Reshuffle"
        @close="confirmReshuffleOpen = false"
        @confirm="performReshuffle"
      >
        <p class="modal-text">
          This will regenerate the flag's salt and re-bucket every user across all rule rollouts.
          Existing experiment results may be invalidated.
        </p>
      </ConfirmModal>
    </template>
  </PageShell>
</template>

<style scoped>
.detail-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 18px;
  align-items: start;
}

.main-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

/* Variations */
.variations-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.variation-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.variation-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.default-tag {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: v-bind(accent);
}

.variation-value-section {
  margin-top: 6px;
}

.field-label {
  display: block;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fg-3);
  margin-bottom: 6px;
}

.json-editor-sm {
  max-height: 160px;
  border-radius: var(--r-sm);
  overflow: hidden;
}

/* Experiments */
.experiment-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.experiment-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background 0.15s;
}

.experiment-row:hover {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

.experiment-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.experiment-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.experiment-meta {
  font-size: 11px;
  color: var(--fg-3);
}

/* Sidebar */
.status-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-label {
  font-size: 12px;
  color: var(--fg-3);
  font-weight: 500;
}

.value-text {
  font-size: 12px;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
  text-align: right;
}

.salt-text {
  font-size: 11px;
}

/* Modal body text */
.modal-text {
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.5;
  margin: 0;
}

/* Live Distribution */
.distribution-help-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--fg-3);
  cursor: help;
  transition: color 0.1s, background 0.1s, border-color 0.1s;
}

.distribution-help-button:hover,
.distribution-help-button:focus-visible {
  color: var(--fg-0);
  background: var(--bg-2);
  border-color: var(--line);
  outline: none;
}

.distribution-help {
  display: flex;
  flex-direction: column;
  gap: 9px;
  min-width: 320px;
  line-height: 1.5;
  color: var(--fg-2);
}

.distribution-help p {
  margin: 0;
}

.distribution-help strong {
  color: var(--fg-0);
  font-weight: 600;
}

.distribution-help-title {
  color: var(--fg-0);
  font-size: 13px;
  font-weight: 600;
}

.distribution-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

@media (max-width: 960px) {
  .distribution-grid {
    grid-template-columns: 1fr;
  }
}

.distribution-panel {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
}

.distribution-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.distribution-panel-title {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.distribution-panel-meta {
  font-size: 11.5px;
  color: var(--fg-3);
}

.distribution-empty {
  padding: 16px 0;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}

.distribution-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.distribution-bar-row {
  display: grid;
  grid-template-columns: 110px 1fr 120px;
  align-items: center;
  gap: 10px;
}

.distribution-bar-label {
  font-size: 12px;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.distribution-bar-track {
  height: 8px;
  background: var(--bg-3);
  border-radius: 4px;
  overflow: hidden;
}

.distribution-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.2s ease;
}

.distribution-bar-values {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-1);
}

.distribution-bar-pct {
  font-size: 11px;
  color: var(--fg-3);
}

.configured-blocks {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.configured-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.configured-block-label {
  margin: 0 0 4px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2);
}

.distribution-blurb {
  margin: 14px 0 0;
  font-size: 11.5px;
  color: var(--fg-3);
  line-height: 1.5;
}

.targeting-blurb {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--fg-3);
}
</style>
