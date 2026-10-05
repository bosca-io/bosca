<script setup lang="ts">
/**
 * Admin surface for Personalization Signals (Phase 0): the config for how profile
 * attributes / segments personalize recommendations. Each signal derives a keyed, typed value from a
 * JSONata expression (authored + previewed via the shared PipelineJsonataField), used as a model
 * feature and/or one or more "people like you" cohort memberships.
 */
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface SignalDefinition {
  id: string
  key: string
  sourceType: 'ATTRIBUTE' | 'SEGMENT'
  sourceId: string
  expression: string
  valueType: 'CATEGORICAL' | 'MULTI_CATEGORICAL' | 'NUMERIC' | 'BOOLEAN'
  priority: number
  useAsFeature: boolean
  useAsCohort: boolean
  enabled: boolean
}

const listGql = gql`
  query PersonalizationSignals {
    recommendation {
      personalizationSignals {
        all(offset: 0, limit: 200) {
          id key sourceType sourceId expression valueType priority useAsFeature useAsCohort enabled
        }
      }
    }
  }
`
const { data, status, error, refresh } = useAsyncQuery<{ recommendation: { personalizationSignals: { all: SignalDefinition[] } } }>(
  'personalization-signals', listGql,
)
const signals = computed(() => data.value?.recommendation?.personalizationSignals?.all ?? [])

// Source pickers — the sourceId is chosen from real attribute types / segments, not typed by hand.
const attributeTypesGql = gql`
  query PersonalizationSignalAttributeTypes {
    profiles { attributeTypes { all { id name } } }
  }
`
const segmentsGql = gql`
  query PersonalizationSignalSegments {
    segments { all(offset: 0, limit: 200) { id name } }
  }
`
const { data: attributeTypesData } = useAsyncQuery<{ profiles: { attributeTypes: { all: { id: string; name: string }[] } } }>(
  'signal-attribute-types', attributeTypesGql, {}, { server: false },
)
const { data: segmentsData } = useAsyncQuery<{ segments: { all: { id: string; name: string }[] } }>(
  'signal-segments', segmentsGql, {}, { server: false },
)
const sourceOptions = computed<SelectOption[]>(() => {
  if (form.sourceType === 'SEGMENT') {
    return (segmentsData.value?.segments?.all ?? []).map(s => ({ value: s.id, label: s.name }))
  }
  // The attribute type's id is the meaningful key (e.g. bosca.profiles.age), so surface it alongside the name.
  return (attributeTypesData.value?.profiles?.attributeTypes?.all ?? []).map(t => ({ value: t.id, label: `${t.name} · ${t.id}` }))
})

const SOURCE_TYPE_OPTIONS: SelectOption[] = [
  { value: 'ATTRIBUTE', label: 'Profile attribute' },
  { value: 'SEGMENT', label: 'Segment' },
]
const VALUE_TYPE_OPTIONS: SelectOption[] = [
  { value: 'CATEGORICAL', label: 'Categorical (single label)' },
  { value: 'MULTI_CATEGORICAL', label: 'Multi-categorical (array of labels)' },
  { value: 'NUMERIC', label: 'Numeric' },
  { value: 'BOOLEAN', label: 'Boolean' },
]
const VALUE_TYPE_LABELS: Record<string, string> = Object.fromEntries(VALUE_TYPE_OPTIONS.map(o => [o.value, o.label]))

// ── create / edit form ──────────────────────────────────────────────────────────────────────────
const editing = ref<SignalDefinition | null>(null)
const formOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive({
  key: '',
  sourceType: 'ATTRIBUTE' as SignalDefinition['sourceType'],
  sourceId: '',
  expression: '',
  valueType: 'CATEGORICAL' as SignalDefinition['valueType'],
  priority: 0,
  useAsFeature: true,
  useAsCohort: false,
  enabled: true,
})

// Client mirror of the backend rule: every cohort membership must be a finite label.
const cohortTypeInvalid = computed(() =>
  form.useAsCohort && form.valueType !== 'CATEGORICAL' && form.valueType !== 'BOOLEAN')

function openCreate() {
  editing.value = null
  Object.assign(form, {
    key: '', sourceType: 'ATTRIBUTE', sourceId: '', expression: '', valueType: 'CATEGORICAL',
    priority: 0, useAsFeature: true, useAsCohort: false, enabled: true,
  })
  formError.value = ''
  formOpen.value = true
}
function openEdit(s: SignalDefinition) {
  editing.value = s
  Object.assign(form, {
    key: s.key, sourceType: s.sourceType, sourceId: s.sourceId, expression: s.expression,
    valueType: s.valueType, priority: s.priority, useAsFeature: s.useAsFeature,
    useAsCohort: s.useAsCohort, enabled: s.enabled,
  })
  formError.value = ''
  formOpen.value = true
}

const addGql = gql`
  mutation AddPersonalizationSignal($signal: PersonalizationSignalDefinitionInput!) {
    recommendation { personalizationSignals { add(signal: $signal) { id } } }
  }
`
const editGql = gql`
  mutation EditPersonalizationSignal($id: UUID!, $signal: PersonalizationSignalDefinitionInput!) {
    recommendation { personalizationSignals { edit(id: $id, signal: $signal) { id } } }
  }
`
async function handleSave() {
  if (!form.key.trim()) { formError.value = 'Key is required.'; return }
  if (!form.sourceId.trim()) { formError.value = 'Source is required.'; return }
  if (!form.expression.trim()) { formError.value = 'A JSONata expression is required.'; return }
  if (cohortTypeInvalid.value) { formError.value = 'A cohort signal must be Categorical or Boolean.'; return }
  saving.value = true
  formError.value = ''
  try {
    const signal = {
      key: form.key.trim(),
      sourceType: form.sourceType,
      sourceId: form.sourceId.trim(),
      expression: form.expression,
      valueType: form.valueType,
      priority: form.priority,
      useAsFeature: form.useAsFeature,
      useAsCohort: form.useAsCohort,
      enabled: form.enabled,
    }
    if (editing.value) await mutation(editGql, { id: editing.value.id, signal })
    else await mutation(addGql, { signal })
    formOpen.value = false
    toast.success(editing.value ? 'Signal updated' : 'Signal created')
    await refresh()
  } catch (e: unknown) {
    // Surface the backend validation message (key clash, invalid JSONata, cohort/type rule).
    formError.value = e instanceof Error ? e.message : 'Failed to save the signal'
  } finally {
    saving.value = false
  }
}

const deleteTarget = ref<SignalDefinition | null>(null)
const deleting = ref(false)
const deleteGql = gql`
  mutation DeletePersonalizationSignal($id: UUID!) {
    recommendation { personalizationSignals { delete(id: $id) } }
  }
`
async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Signal deleted')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete the signal')
  } finally {
    deleting.value = false
  }
}

function roleLabel(s: SignalDefinition): string {
  const roles = [s.useAsFeature ? 'Feature' : null, s.useAsCohort ? 'Cohort' : null].filter(Boolean)
  return roles.length ? roles.join(' + ') : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Personalization Signals')"
        title="Personalization Signals"
        subtitle="Which profile attributes & segments personalize recommendations — and how each is derived, typed, and used">
        <template #actions>
          <Button
            icon="plus"
            size="sm"
            primary
            :accent="accent"
            @click="openCreate">New signal</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load signals — {{ error.message }}</div>
    <div v-else-if="status === 'pending' && !signals.length" class="state">Loading…</div>
    <div v-else-if="!signals.length" class="state">
      No personalization signals yet. Create one to feed a profile attribute (e.g. age, gender, country) into the recommender.
    </div>

    <SectionCard
      v-else
      title="Signals"
      :subtitle="`${signals.length} configured`"
      padded>
      <div class="sig-table">
        <div class="sig-head">
          <span>Key</span><span>Source</span><span>Type</span><span>Roles</span><span class="num">Priority</span><span>Status</span><span />
        </div>
        <div
          v-for="s in signals"
          :key="s.id"
          class="sig-row"
          :class="{ disabled: !s.enabled }"
          @click="openEdit(s)">
          <span class="sig-key">{{ s.key }}</span>
          <span class="sig-src"><code>{{ s.sourceId }}</code><em>{{ s.sourceType === 'SEGMENT' ? 'segment' : 'attribute' }}</em></span>
          <span>{{ VALUE_TYPE_LABELS[s.valueType] ?? s.valueType }}</span>
          <span>{{ roleLabel(s) }}</span>
          <span class="num">{{ s.priority }}</span>
          <span><Badge :color="s.enabled ? '#10b981' : '#64748b'">{{ s.enabled ? 'Enabled' : 'Disabled' }}</Badge></span>
          <span class="sig-actions">
            <button
              type="button"
              class="icon-btn"
              title="Delete"
              @click.stop="deleteTarget = s">
              <Icon name="trash" :size="14" />
            </button>
          </span>
        </div>
      </div>
    </SectionCard>

    <!-- Create / edit -->
    <Modal
      v-if="formOpen"
      :title="editing ? 'Edit signal' : 'New signal'"
      subtitle="Author the JSONata that derives this signal's value from the attribute; preview it against a sample."
      icon="sliders"
      width="min(92vw, 900px)"
      @close="formOpen = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput
            v-model="form.key"
            label="Key"
            mono
            placeholder="age_band" />
          <NumberInput v-model="form.priority" label="Priority" :min="0" />
        </div>
        <div class="form-row">
          <Select
            v-model="form.sourceType"
            label="Source"
            :options="SOURCE_TYPE_OPTIONS"
            :accent="accent"
            @update:model-value="form.sourceId = ''" />
          <Select
            v-model="form.sourceId"
            :label="form.sourceType === 'SEGMENT' ? 'Segment' : 'Attribute type'"
            :options="sourceOptions"
            :accent="accent"
            searchable
            :placeholder="form.sourceType === 'SEGMENT' ? 'Select a segment' : 'Select an attribute type'" />
        </div>
        <PipelineJsonataField
          v-model="form.expression"
          label="Expression (JSONata)"
          placeholder="attributes.band" />
        <div class="form-row">
          <Select
            v-model="form.valueType"
            label="Value type"
            :options="VALUE_TYPE_OPTIONS"
            :accent="accent" />
        </div>
        <div class="switch-row">
          <label class="switch-field"><Switch v-model="form.useAsFeature" /><span>Use as model feature</span></label>
          <label class="switch-field"><Switch v-model="form.useAsCohort" /><span>Use as "people like you" cohort</span></label>
          <label class="switch-field"><Switch v-model="form.enabled" /><span>Enabled</span></label>
        </div>
        <p v-if="cohortTypeInvalid" class="form-warn">
          A cohort signal must be Categorical or Boolean so each generated value is a finite cohort label.
        </p>
        <p v-else-if="form.useAsCohort" class="form-help">
          Every distinct value can become its own “people like you” membership. Cohort materialization uses at
          most 64 values for this key and excludes values longer than 256 characters.
        </p>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <div class="save-row">
          <Button @click="formOpen = false">Cancel</Button>
          <Button
            primary
            :accent="accent"
            :disabled="saving || cohortTypeInvalid"
            @click="handleSave">
            {{ saving ? 'Saving…' : (editing ? 'Save changes' : 'Create signal') }}
          </Button>
        </div>
      </div>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete signal"
      subtitle="The recommender stops using this signal on its next materialization + training run."
      confirm-label="Delete"
      :loading="deleting"
      @close="deleteTarget = null"
      @confirm="handleDelete">
      <p>Delete <strong>{{ deleteTarget?.key }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--fg-2);
  border-radius: 4px;
  margin-bottom: 12px;
}
.state { padding: 32px; text-align: center; color: var(--fg-3); font-size: 13px; line-height: 1.6; }
.sig-table { display: flex; flex-direction: column; }
.sig-head, .sig-row {
  display: grid;
  grid-template-columns: minmax(120px, 1.4fr) minmax(160px, 1.6fr) 1.4fr 1fr 90px 110px 40px;
  gap: 10px;
  align-items: center;
}
.sig-head {
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; color: var(--fg-4);
  padding: 0 8px 8px; border-bottom: 1px solid var(--bg-3);
}
.sig-row {
  padding: 10px 8px; border-bottom: 1px solid var(--bg-3); cursor: pointer; font-size: 12.5px;
  color: var(--fg-2); transition: background 0.1s;
}
.sig-row:hover { background: var(--bg-3); }
.sig-row.disabled { opacity: 0.55; }
.sig-key { font-family: var(--font-mono, monospace); font-size: 12.5px; color: var(--fg-1); }
.sig-src { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.sig-src code { font-size: 11.5px; color: var(--fg-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sig-src em { font-size: 10.5px; color: var(--fg-4); font-style: normal; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.sig-actions { display: flex; justify-content: flex-end; }
.icon-btn { background: none; border: none; color: var(--fg-4); cursor: pointer; padding: 4px; border-radius: var(--r-xs); }
.icon-btn:hover { color: var(--err, #f87171); background: var(--bg-2); }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.switch-row { display: flex; flex-wrap: wrap; gap: 18px; }
.switch-field { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.form-warn { color: var(--warn, #f59e0b); font-size: 12px; margin: 0; }
.form-help { color: var(--fg-3); font-size: 12px; margin: 0; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 0; }
.save-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
</style>
