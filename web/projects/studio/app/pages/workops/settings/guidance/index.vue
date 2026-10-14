<script setup lang="ts">
import gql from 'graphql-tag'
import { onBeforeRouteLeave } from 'vue-router'
import WorkOpsGuideEditor from '~/components/WorkOpsGuideEditor.vue'

import { planGuidanceReconciliation, resolveGuidanceReconciliation, describeGuidanceConflict, type Policy, type Guide, type Guidance, type GuidanceDraft, type GuidanceChoice, type GuidanceReconciliation } from '~/utils/guidanceReconciliation'

const { query, mutation } = useGraphQL()
const toast = useToast()
function failureMessage(failure: unknown): string { return failure instanceof Error ? failure.message : String(failure) }
const { accent } = useCurrentSubsystem()
const scope = ref('__default__')
const projectId = computed(() => scope.value === '__default__' ? null : scope.value)
const projects = ref<Array<{ id: string; key: string; name: string }>>([])
const scopeOptions = computed(() => [{ value: '__default__', label: 'Platform defaults' }, ...projects.value.map(project => ({ value: project.id, label: project.name }))])
const phases = ['INVESTIGATION', 'SPECIFICATION', 'INTERFACES', 'IMPLEMENTATION', 'VERIFICATION']
const phaseOptions = phases.map(value => ({ value, label: value.charAt(0) + value.slice(1).toLowerCase() }))
const phase = ref('INVESTIGATION')
const guidance = ref<Guidance | null>(null)
const guides = ref<Guide[]>([])
const selected = computed(() => guides.value.find(guide => guide.phase === phase.value))
const policy = ref<Policy>({ requireInterfaceReview: true, requireTdd: true, requireTestQuality: true, minimumLineCoverage: 99, minimumBranchCoverage: 97 })
const inheritPolicy = ref(true)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const reloading = ref(false)
const reconciliation = ref<GuidanceReconciliation | null>(null)
const choices = ref<Record<string, GuidanceChoice | '__unresolved__'>>({})
const choicesComplete = computed(() => reconciliation.value?.conflicts.every(conflict => choices.value[conflict.key] === 'local' || choices.value[conflict.key] === 'saved') === true)
let generation = 0
const drafts = ref(new Map<string, { baseline: Guidance; guides: Guide[]; policy: Policy; inheritPolicy: boolean }>())
const bundledPolicy: Policy = { requireInterfaceReview: true, requireTdd: true, requireTestQuality: true, minimumLineCoverage: 99, minimumBranchCoverage: 97 }
function signature(value: { guides: Guide[]; policy: Policy; inheritPolicy: boolean }) {
  return JSON.stringify({ guides: value.guides.map(({ phase, name, description, markdown, inherited }) => ({ phase, name, description, markdown, inherited })), policy: value.inheritPolicy ? null : value.policy })
}
const draftSignature = computed(() => signature({ guides: guides.value, policy: policy.value, inheritPolicy: inheritPolicy.value }))
const dirty = computed(() => guidance.value !== null && draftSignature.value !== signature({ guides: guidance.value.guides, policy: guidance.value.policy, inheritPolicy: guidance.value.policyInherited }))
watch(draftSignature, () => { reconciliation.value = null })
const hasUnsavedChanges = computed(() => dirty.value || drafts.value.size > 0)
function beforeUnload(event: BeforeUnloadEvent) { if (hasUnsavedChanges.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => !hasUnsavedChanges.value || window.confirm('You have unsaved WorkOps guidance. Leave this page?'))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
watch(inheritPolicy, async inherited => {
  if (!inherited || loading.value) return
  const targetScope = scope.value
  const request = generation
  try {
    const value = projectId.value ? (await query<{ workOps: { guidance: { effective: Guidance } } }>(effectiveQuery, { projectId: null })).workOps.guidance.effective.policy : bundledPolicy
    if (scope.value === targetScope && request === generation && inheritPolicy.value) policy.value = { ...value }
  }
  catch (failure) { toast.error(failureMessage(failure)) }
})
const fields = gql`fragment WorkOpsGuidanceEditorFields on WorkOpsGuidance {
  revision policyInherited
  policy { requireInterfaceReview requireTdd requireTestQuality minimumLineCoverage minimumBranchCoverage }
  guides { phase name description markdown inherited }
}`
const effectiveQuery = gql`${fields}
  query StudioWorkOpsGuidance($projectId: UUID) { workOps { guidance { effective(projectId: $projectId) { ...WorkOpsGuidanceEditorFields } } } }`
const saveMutation = gql`${fields}
  mutation SaveStudioWorkOpsGuidance($projectId: UUID, $input: WorkOpsGuidanceInput!, $expectedRevision: String!) { workOps { guidance { save(projectId: $projectId, input: $input, expectedRevision: $expectedRevision) { ...WorkOpsGuidanceEditorFields } } } }`
const resetMutation = gql`${fields}
  mutation ResetStudioWorkOpsGuidance($projectId: UUID, $expectedRevision: String!) { workOps { guidance { reset(projectId: $projectId, expectedRevision: $expectedRevision) { ...WorkOpsGuidanceEditorFields } } } }`

function apply(value: Guidance) {
  guidance.value = value
  guides.value = value.guides.map(guide => ({ ...guide }))
  policy.value = { ...value.policy }
  inheritPolicy.value = value.policyInherited
}
async function load() {
  const request = ++generation
  loading.value = true
  error.value = ''
  try {
    const response = await query<{ workOps: { guidance: { effective: Guidance } } }>(effectiveQuery, { projectId: projectId.value })
    if (request === generation) apply(response.workOps.guidance.effective)
  }
  catch (failure) { if (request === generation) { guidance.value = null; error.value = failureMessage(failure) } }
  finally { if (request === generation) loading.value = false }
}
watch(scope, async (_value, previous) => {
  reconciliation.value = null
  if (!loading.value) {
    if (guidance.value && dirty.value) drafts.value.set(previous, { baseline: guidance.value, guides: guides.value.map(guide => ({ ...guide })), policy: { ...policy.value }, inheritPolicy: inheritPolicy.value })
    else drafts.value.delete(previous)
  }
  const draft = drafts.value.get(scope.value)
  if (draft) {
    drafts.value.delete(scope.value)
    ++generation
    loading.value = true
    guidance.value = draft.baseline
    guides.value = draft.guides.map(guide => ({ ...guide }))
    policy.value = { ...draft.policy }
    inheritPolicy.value = draft.inheritPolicy
    error.value = ''
    loading.value = false
  }
  else await load()
})
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await load()
  try {
    const response = await query<{ workOps: { projects: { all: typeof projects.value } } }>(gql`query WorkOpsGuidanceProjects { workOps { projects { all { id key name } } } }`)
    projects.value = response.workOps.projects.all
  }
  catch (failure) { toast.error(failureMessage(failure)) }
})
function edit(field: 'name' | 'description' | 'markdown', value: string) {
  if (selected.value && selected.value[field] !== value) { selected.value[field] = value; selected.value.inherited = false }
}
async function inheritGuide() {
  const targetScope = scope.value
  const targetPhase = phase.value
  const originalDraft = draftSignature.value
  const request = generation
  try {
    const response = await query<{ workOps: { guidance: { effective: Guidance } } }>(effectiveQuery, { projectId: null })
    const inherited = response.workOps.guidance.effective.guides.find(guide => guide.phase === targetPhase)
    // Platform reset restores all bundled guides; project inheritance can be changed per guide.
    const target = guides.value.find(guide => guide.phase === targetPhase)
    if (target && inherited && scope.value === targetScope && generation === request && draftSignature.value === originalDraft) Object.assign(target, inherited, { inherited: true })
  }
  catch (failure) { toast.error(failureMessage(failure)) }
}
function applyReconciliation(plan: GuidanceReconciliation, selectedChoices: Record<string, GuidanceChoice>) {
  const draft = resolveGuidanceReconciliation(plan, selectedChoices)
  guidance.value = plan.latest
  guides.value = draft.guides
  policy.value = draft.policy
  inheritPolicy.value = draft.inheritPolicy
  reconciliation.value = null
  toast.success('Latest settings loaded. Review your draft, then save it.')
}
async function reloadAndReconcile() {
  if (reloading.value || !guidance.value) return
  const target = projectId.value
  const request = generation
  const signature = draftSignature.value
  const baseline = guidance.value
  const draft: GuidanceDraft = { guides: guides.value.map(guide => ({ ...guide })), policy: { ...policy.value }, inheritPolicy: inheritPolicy.value }
  reloading.value = true
  try {
    const response = await query<{ workOps: { guidance: { effective: Guidance } } }>(effectiveQuery, { projectId: target })
    const latest = response.workOps.guidance.effective
    const inherited = target ? (await query<{ workOps: { guidance: { effective: Guidance } } }>(effectiveQuery, { projectId: null })).workOps.guidance.effective : { ...latest, policy: bundledPolicy }
    if (request !== generation || target !== projectId.value || signature !== draftSignature.value) return
    const plan = planGuidanceReconciliation(baseline, draft, latest, inherited)
    if (plan.conflicts.length === 0) applyReconciliation(plan, {})
    else { reconciliation.value = plan; choices.value = Object.fromEntries(plan.conflicts.map(conflict => [conflict.key, '__unresolved__'])) }
  }
  catch (failure) { toast.error(failureMessage(failure)) }
  finally { reloading.value = false }
}
function reconcileChoices() {
  if (reconciliation.value && choicesComplete.value) applyReconciliation(reconciliation.value, choices.value as Record<string, GuidanceChoice>)
}
async function save() {
  if (saving.value || reloading.value || reconciliation.value) return
  if (!inheritPolicy.value && ![policy.value.minimumLineCoverage, policy.value.minimumBranchCoverage].every(value => Number.isFinite(value) && value >= 0 && value <= 100)) { toast.error('Coverage thresholds must be percentages between 0 and 100.'); return }
  saving.value = true
  const target = projectId.value
  const request = generation
  const submitted = draftSignature.value
  try {
    const input = { policy: inheritPolicy.value ? null : { ...policy.value }, guides: guides.value.filter(guide => !guide.inherited).map(({ phase, name, description, markdown }) => ({ phase, name, description, markdown })) }
    const response = await mutation<{ workOps: { guidance: { save: Guidance } } }>(saveMutation, { projectId: target, input, expectedRevision: guidance.value?.revision })
    if (target === projectId.value && request === generation) {
      if (submitted === draftSignature.value) { apply(response.workOps.guidance.save); drafts.value.delete(scope.value) }
      else guidance.value = response.workOps.guidance.save
    }
    toast.success('Guidance saved')
  }
  catch (failure) { toast.error(failureMessage(failure)) }
  finally { saving.value = false }
}
async function reset() {
  if (saving.value || reloading.value || reconciliation.value) return
  saving.value = true
  const target = projectId.value
  const request = generation
  const submitted = draftSignature.value
  try {
    const response = await mutation<{ workOps: { guidance: { reset: Guidance } } }>(resetMutation, { projectId: target, expectedRevision: guidance.value?.revision })
    if (target === projectId.value && request === generation) {
      if (submitted === draftSignature.value) { apply(response.workOps.guidance.reset); drafts.value.delete(scope.value) }
      else guidance.value = response.workOps.guidance.reset
    }
    toast.success('Default guidance restored')
  }
  catch (failure) { toast.error(failureMessage(failure)) }
  finally { saving.value = false }
}
async function importSkill(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const targetPhase = phase.value
    const targetScope = scope.value
    const originalDraft = draftSignature.value
    const request = generation
    const response = await query<{ workOps: { guidance: { importSkill: Guide } } }>(gql`query ImportWorkOpsSkill($phase: WorkOpsGuidancePhase!, $skill: String!) { workOps { guidance { importSkill(phase: $phase, skill: $skill) { phase name description markdown inherited } } } }`, { phase: targetPhase, skill: await file.text() })
    const guide = guides.value.find(guide => guide.phase === targetPhase)
    if (guide && scope.value === targetScope && request === generation && originalDraft === draftSignature.value) Object.assign(guide, response.workOps.guidance.importSkill, { inherited: false })
    toast.success('Skill imported into the draft. Save to apply it.')
  }
  catch (failure) { toast.error(failureMessage(failure)) }
  finally { input.value = '' }
}
async function exportSkill() {
  try {
    const response = await query<{ workOps: { guidance: { exportSkill: string } } }>(gql`query ExportWorkOpsSkill($projectId: UUID, $phase: WorkOpsGuidancePhase!) { workOps { guidance { exportSkill(projectId: $projectId, phase: $phase) } } }`, { projectId: projectId.value, phase: phase.value })
    const url = URL.createObjectURL(new Blob([response.workOps.guidance.exportSkill], { type: 'text/markdown' }))
    const link = document.createElement('a')
    link.href = url; link.download = 'SKILL.md'; link.click()
    URL.revokeObjectURL(url)
  }
  catch (failure) { toast.error(failureMessage(failure)) }
}
</script>

<template>
  <PageLayout>
    <template #header><PageHeader title="WorkOps guidance"><template #right><Button :disabled="loading || saving || reloading || !!reconciliation || !guidance" @click="save">Save guidance</Button></template></PageHeader></template>
    <SectionCard>
      <Select
        v-model="scope"
        label="Scope"
        :options="scopeOptions"
        :accent="accent"
        :disabled="saving" />
      <p>Configure the guides used for investigation, specifications, interface review, implementation, and verification. Projects inherit platform settings until overridden.</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="hasUnsavedChanges">Unsaved guidance changes</p>
      <Button :disabled="loading || saving || reloading || !guidance" @click="reloadAndReconcile">Reload and reconcile</Button>
      <div v-if="reconciliation" class="guidance-reconciliation">
        <h2>Reconcile simultaneous changes</h2>
        <p>Choose which version to keep for each conflict. Other saved changes are included in your draft.</p>
        <section v-for="conflict in reconciliation.conflicts" :key="conflict.key">
          <h3>{{ conflict.label }}</h3>
          <div class="reconciliation-previews">
            <div><h4>Your draft</h4><pre>{{ describeGuidanceConflict(conflict.local) }}</pre></div>
            <div><h4>Latest saved</h4><pre>{{ describeGuidanceConflict(conflict.saved) }}</pre></div>
          </div>
          <label>{{ conflict.label }} conflict
            <select v-model="choices[conflict.key]" :aria-label="`${conflict.label} conflict`">
              <option value="__unresolved__">Choose a version</option>
              <option value="local">Keep my draft</option>
              <option value="saved">Use latest saved</option>
            </select>
          </label>
        </section>
        <Button :disabled="!choicesComplete" @click="reconcileChoices">Apply reconciliation</Button>
        <Button @click="reconciliation = null">Keep editing</Button>
      </div>
      <p v-if="loading">Loading guidance…</p>
      <div v-else-if="guidance" class="guidance-form">
        <div class="guidance-policy">
          <h2>Verification policy</h2>
          <Switch v-model="inheritPolicy" :label="projectId ? 'Inherit platform policy' : 'Use bundled policy'" :accent="accent" />
          <fieldset :disabled="inheritPolicy || saving">
            <Switch v-model="policy.requireInterfaceReview" label="Require current interface review" :accent="accent" />
            <Switch v-model="policy.requireTdd" label="Require RED, GREEN, REFACTOR evidence" :accent="accent" />
            <Switch v-model="policy.requireTestQuality" label="Require test quality review and measured coverage" :accent="accent" />
            <TextInput
              :model-value="String(policy.minimumLineCoverage)"
              type="number"
              label="Minimum line coverage (%)"
              @update:model-value="policy.minimumLineCoverage = $event.trim() ? Number($event) : NaN" />
            <TextInput
              :model-value="String(policy.minimumBranchCoverage)"
              type="number"
              label="Minimum branch coverage (%)"
              @update:model-value="policy.minimumBranchCoverage = $event.trim() ? Number($event) : NaN" />
          </fieldset>
          <p>Guide text and imported skills cannot disable these checks. Existing repository coverage gates still apply. Reviews and validation evidence record the effective revision.</p>
        </div>
        <Select
          v-model="phase"
          label="Guide"
          :options="phaseOptions"
          :accent="accent" />
        <div v-if="selected" class="guidance-document">
          <p>{{ selected.inherited ? 'Inherited guide' : 'Scope override' }}</p>
          <TextInput :model-value="selected.name" label="Skill name" @update:model-value="edit('name', $event)" />
          <TextInput :model-value="selected.description" label="When to use this guide" @update:model-value="edit('description', $event)" />
          <WorkOpsGuideEditor :key="`${scope}:${phase}`" :model-value="selected.markdown" @update:model-value="edit('markdown', $event)" />
          <div class="guidance-actions">
            <Button v-if="projectId" :disabled="saving" @click="inheritGuide">Inherit platform guide</Button>
            <label>Import SKILL.md<input
              type="file"
              accept=".md,text/markdown"
              :disabled="saving"
              @change="importSkill" ></label>
            <Button :disabled="saving" @click="exportSkill">Export saved SKILL.md</Button>
          </div>
        </div>
        <Button :disabled="saving" @click="reset">{{ projectId ? 'Restore platform defaults' : 'Restore bundled defaults' }}</Button>
      </div>
    </SectionCard>
  </PageLayout>
</template>

<style scoped>
.guidance-form, .guidance-document, .guidance-policy, fieldset { display: grid; gap: 16px; }
.guidance-form { margin-top: 24px; }
fieldset { border: 0; padding: 0; }
.guidance-reconciliation { display: grid; gap: 16px; margin-top: 16px; }
.reconciliation-previews { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; }
.reconciliation-previews pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; border: 1px solid var(--border); border-radius: 8px; }
.guidance-actions { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; }
p { color: var(--fg-3); }
h2 { font-size: 16px; }
input[type='file'] { display: block; margin-top: 8px; }
</style>
