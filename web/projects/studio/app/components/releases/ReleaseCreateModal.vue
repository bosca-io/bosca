<script setup lang="ts">
import gql from 'graphql-tag'
import ReleaseComponentPicker from '~/components/releases/ReleaseComponentPicker.vue'

/**
 * The build-a-release wizard: a guided flow to assemble a release before it's
 * started. Three steps — Details (name, date), Projects (the versions it ships — a release can span
 * several projects at different versions, e.g. Android @ X + iOS @ Y), then Review. On finish it
 * `createRelease` then `bundleVersion`s each staged project (best-effort, keyed by
 * releaseId/projectId/versionId). Starting native release runs and all downstream status are handled
 * on the dashboard afterwards — this wizard only builds the bundle.
 */

const props = defineProps<{ programId: string }>()
const emit = defineEmits<{ close: []; created: [id: string] }>()

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const toast = useToast()

interface StagedComponent { projectId: string; projectKey: string; versionId: string; versionName: string }

const STEPS = [
  { n: 1, label: 'Details' },
  { n: 2, label: 'Projects' },
  { n: 3, label: 'Review' },
]
const step = ref(1)

const name = ref('')
const description = ref('')
const releaseDate = ref('')
const saving = ref(false)
const error = ref('')

const staged = ref<StagedComponent[]>([])
const stagedVersionIds = computed(() => staged.value.map(s => s.versionId))

function onAdd(c: StagedComponent) {
  if (staged.value.some(s => s.versionId === c.versionId)) return
  staged.value = [...staged.value, c]
}
function removeStaged(i: number) {
  staged.value = staged.value.filter((_, idx) => idx !== i)
}

const canProceed = computed(() => {
  if (step.value === 1) return !!name.value.trim()
  return true
})
function next() {
  error.value = ''
  if (step.value === 1 && !name.value.trim()) { error.value = 'A release name is required.'; return }
  if (step.value < 3) step.value += 1
}
function back() {
  error.value = ''
  if (step.value > 1) step.value -= 1
}

function formatDate(d: string): string {
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

async function create() {
  if (!name.value.trim()) { step.value = 1; error.value = 'A release name is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: Record<string, unknown> = { programId: props.programId, name: name.value.trim() }
    if (description.value.trim()) input.description = description.value.trim()
    if (releaseDate.value) input.releaseDate = new Date(releaseDate.value).toISOString()

    const res = await mutation<{ workOps: { crossProject: { createRelease: { id: string } } } }>(gql`
      mutation CreateRelease($input: CreateWorkOpsReleaseInput!) {
        workOps { crossProject { createRelease(input: $input) { id } } }
      }
    `, { input })
    const releaseId = res?.workOps?.crossProject?.createRelease?.id
    if (!releaseId) throw new Error('The release could not be created.')

    // Bundle each staged project; a failure on one doesn't lose the created release.
    let failed = 0
    for (const c of staged.value) {
      try {
        await mutation(gql`
          mutation BundleVersion($releaseId: UUID!, $projectId: UUID!, $versionId: UUID!) {
            workOps { crossProject { bundleVersion(releaseId: $releaseId, projectId: $projectId, versionId: $versionId) } }
          }
        `, { releaseId, projectId: c.projectId, versionId: c.versionId })
      } catch { failed++ }
    }

    if (failed) toast.warn(`Release created; bundled ${staged.value.length - failed} of ${staged.value.length} projects.`)
    else toast.success(staged.value.length ? 'Release built' : 'Release created')
    emit('created', releaseId)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create the release.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal
    title="Build a release"
    subtitle="Assemble the release, then start it from the dashboard"
    icon="package"
    :accent="accent"
    width="580px"
    @close="emit('close')"
  >
    <!-- Stepper -->
    <ol class="stepper">
      <li
        v-for="s in STEPS"
        :key="s.n"
        class="step"
        :class="{ active: s.n === step, done: s.n < step }">
        <span class="step-dot">
          <Icon v-if="s.n < step" name="check" :size="12" />
          <template v-else>{{ s.n }}</template>
        </span>
        <span class="step-label">{{ s.label }}</span>
      </li>
    </ol>

    <!-- Step 1 — Details -->
    <div v-if="step === 1" class="form-stack">
      <TextInput
        v-model="name"
        label="Name"
        placeholder="e.g. 2026.07 platform release"
        autofocus />
      <Textarea
        v-model="description"
        label="Description"
        placeholder="What does this release ship?"
        :rows="3" />
      <DateInput v-model="releaseDate" label="Planned release date" type="date" />
    </div>

    <!-- Step 2 — Projects -->
    <div v-else-if="step === 2" class="form-stack">
      <p class="step-hint">
        Add each project and the version this release ships. A release can span several projects at
        different versions — you can also bundle more later.
      </p>
      <ReleaseComponentPicker
        :program-id="programId"
        :disabled-version-ids="stagedVersionIds"
        @add="onAdd" />
      <ul v-if="staged.length" class="staged">
        <li v-for="(c, i) in staged" :key="c.versionId" class="staged-row">
          <span class="mono staged-project">{{ c.projectKey }}</span>
          <span class="staged-version">{{ c.versionName }}</span>
          <button
            type="button"
            class="staged-remove"
            title="Remove"
            @click="removeStaged(i)">
            <Icon name="x" :size="13" />
          </button>
        </li>
      </ul>
      <p v-else class="components-empty">No projects yet — add them here, or bundle them later.</p>
    </div>

    <!-- Step 3 — Review -->
    <div v-else class="form-stack">
      <div class="review">
        <div class="review-row">
          <span class="review-n">Name</span>
          <span class="review-v">{{ name.trim() || '—' }}</span>
        </div>
        <div v-if="description.trim()" class="review-row">
          <span class="review-n">Description</span>
          <span class="review-v">{{ description.trim() }}</span>
        </div>
        <div class="review-row">
          <span class="review-n">Planned</span>
          <span class="review-v">{{ releaseDate ? formatDate(releaseDate) : 'No date set' }}</span>
        </div>
        <div class="review-row">
          <span class="review-n">Projects</span>
          <span class="review-v">
            <template v-if="staged.length">
              <span v-for="c in staged" :key="c.versionId" class="review-comp">
                <span class="mono">{{ c.projectKey }}</span> · {{ c.versionName }}
              </span>
            </template>
            <span v-else class="review-none">None yet — bundle on the dashboard</span>
          </span>
        </div>
      </div>
      <p class="step-hint">
        This builds the release as a <strong>Draft</strong>. Start it from the dashboard to run the native
        release pipelines — status then tracks them automatically.
      </p>
    </div>

    <p v-if="error" class="form-error">{{ error }}</p>

    <template #footer>
      <Button v-if="step > 1" @click="back">Back</Button>
      <Button v-else @click="emit('close')">Cancel</Button>
      <Button
        v-if="step < 3"
        primary
        :accent="accent"
        :disabled="!canProceed"
        @click="next">Next</Button>
      <Button
        v-else
        primary
        :accent="accent"
        :disabled="saving"
        @click="create">Build release</Button>
    </template>
  </Modal>
</template>

<style scoped>
.stepper { list-style: none; margin: 0 0 18px; padding: 0 0 16px; display: flex; gap: 8px; border-bottom: 1px solid var(--line); }
.step { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; opacity: 0.55; }
.step.active, .step.done { opacity: 1; }
.step-dot {
  width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; flex: 0 0 auto;
  font-size: 11px; font-weight: 700; border: 1px solid var(--line); color: var(--fg-2); background: var(--bg-1);
}
.step.active .step-dot { border-color: var(--brand-2, #5ec5ff); color: var(--fg-0); background: color-mix(in oklch, var(--brand-2, #5ec5ff) 12%, transparent); }
.step.done .step-dot { border-color: var(--ok, #34d399); color: var(--ok, #34d399); }
.step-label { font-size: 12.5px; color: var(--fg-1); white-space: nowrap; }

.form-stack { display: flex; flex-direction: column; gap: 14px; }
.step-hint { margin: 0; font-size: 12.5px; color: var(--fg-3); line-height: 1.55; }
.step-hint strong { color: var(--fg-1); font-weight: 600; }
.form-error { margin: 12px 0 0; color: var(--err, #f87171); font-size: 12px; }
.components-empty { margin: 0; font-size: 12px; color: var(--fg-3); }
.staged { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
/* Columns line up across rows: key | version | remove. */
.staged-row {
  display: grid; grid-template-columns: 72px minmax(0, 1fr) auto; align-items: center; gap: 10px;
  padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg-2);
}
.staged-project { font-size: 11px; color: var(--fg-2); }
.staged-version { font-size: 12.5px; color: var(--fg-0); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.staged-remove {
  display: inline-flex; padding: 2px; border: none; background: transparent;
  color: var(--fg-3); cursor: pointer; border-radius: 4px;
}
.staged-remove:hover { color: var(--err, #f87171); background: var(--bg-3); }

.review { display: flex; flex-direction: column; gap: 2px; border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; background: var(--bg-1); }
.review-row { display: flex; gap: 12px; padding: 6px 0; border-top: 1px solid var(--line-soft, var(--line)); }
.review-row:first-child { border-top: 0; }
.review-n { flex: 0 0 90px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--fg-3); padding-top: 2px; }
.review-v { flex: 1; min-width: 0; font-size: 13px; color: var(--fg-0); display: flex; flex-wrap: wrap; gap: 4px 10px; }
.review-comp { font-size: 12.5px; }
.review-none { color: var(--fg-3); font-size: 12.5px; }
</style>
