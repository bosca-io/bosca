<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const props = defineProps<{ repositoryId: string; enabled: boolean }>()
const { query, mutation } = useGraphQL()
const { accent } = useCurrentSubsystem()
type View = 'refStates' | 'pullRequestStates' | 'deliveries'
interface Snapshot { title: string; description: string | null; sourceBranch: string; targetBranch: string; status: string; mergeSha: string | null }
interface RefState { ref: string; sha: string | null; synchronized: boolean; boscaSha: string | null; githubSha: string | null; conflict: boolean; modified: string }
interface PrState { pullRequestId: string | null; githubNumber: number | null; snapshot: Snapshot | null; bosca: Snapshot | null; github: Snapshot | null; problem: string | null; modified: string }
interface Delivery { deliveryId: string; event: string; principalId: string | null; githubUserId: number | null; ignored: boolean; created: string }
interface History { refStates: RefState[]; pullRequestStates: PrState[]; deliveries: Delivery[] }

const snapshotFields = gql`fragment SyncSnapshotFields on GitHubPullRequestSnapshot {
  title description sourceBranch targetBranch status mergeSha
}`
const documents = {
  refStates: gql`query GitHubSyncRefs($repositoryId: UUID!, $offset: Long!, $limit: Int!) {
    github { refStates(repositoryId: $repositoryId, offset: $offset, limit: $limit) {
      ref sha synchronized boscaSha githubSha conflict modified
    } }
  }`,
  pullRequestStates: gql`query GitHubSyncPullRequests($repositoryId: UUID!, $offset: Long!, $limit: Int!) {
    github { pullRequestStates(repositoryId: $repositoryId, offset: $offset, limit: $limit) {
      pullRequestId githubNumber problem modified
      snapshot { ...SyncSnapshotFields } bosca { ...SyncSnapshotFields } github { ...SyncSnapshotFields }
    } }
  } ${snapshotFields}`,
  deliveries: gql`query GitHubSyncDeliveries($repositoryId: UUID!, $offset: Long!, $limit: Int!) {
    github { deliveries(repositoryId: $repositoryId, offset: $offset, limit: $limit) {
      deliveryId event principalId githubUserId ignored created
    } }
  }`,
}
const reconcileDocuments = {
  refStates: gql`mutation ReconcileGitHubSyncRefs($repositoryId: UUID!) {
    github { reconcileRefs(repositoryId: $repositoryId) { ref } }
  }`,
  pullRequestStates: gql`mutation ReconcileGitHubSyncPullRequests($repositoryId: UUID!) {
    github { reconcilePullRequests(repositoryId: $repositoryId) { pullRequestId } }
  }`,
}
const transferDocuments = {
  pullRefs: gql`mutation PullGitHubSyncRefs($repositoryId: UUID!) {
    github { pullRefs(repositoryId: $repositoryId) { ref } }
  }`,
  pushRefs: gql`mutation PushGitHubSyncRefs($repositoryId: UUID!) {
    github { pushRefs(repositoryId: $repositoryId) { ref } }
  }`,
}
type Operation = 'reconcile' | keyof typeof transferDocuments
const resolveDocument = gql`mutation ResolveGitHubSyncRef($input: GitHubRefResolutionInput!) {
  github { resolveRef(input: $input) { ref conflict boscaSha githubSha } }
}`
const view = ref<View>('refStates')
const views: Array<{ id: View; label: string }> = [
  { id: 'refStates', label: 'Branches and tags' },
  { id: 'pullRequestStates', label: 'Pull requests' },
  { id: 'deliveries', label: 'Deliveries' },
]
const viewLabel = computed(() => views.find(option => option.id === view.value)?.label ?? '')
const offset = ref(0)
const limit = 25
const rows = ref<Array<RefState | PrState | Delivery>>([])
const loading = ref(false)
const running = ref<Operation | null>(null)
const resolving = ref(false)
const resolution = ref<RefState | null>(null)
const choice = ref<'GITHUB' | 'BOSCA' | ''>('')
const resolutionError = ref('')
const busy = computed(() => running.value !== null || resolving.value)
const blocked = computed(() => busy.value || resolution.value !== null)
const resolutionHost = computed(() => choice.value === 'GITHUB' ? 'GitHub' : 'Bosca')
const resolutionTarget = computed(() => choice.value === 'GITHUB' ? 'Bosca' : 'GitHub')
const chosenSha = computed(() => choice.value === 'GITHUB' ? resolution.value?.githubSha : resolution.value?.boscaSha)
const error = ref('')
const notice = ref('')
const canNext = ref(false)
let request = 0
const columns = computed<GlassTableColumn[]>(() => view.value === 'refStates' ? [
  { key: 'ref', label: 'Ref', width: '1fr' }, { key: 'state', label: 'State', width: '1fr' },
  { key: 'sha', label: 'Last common SHA', width: '1fr' }, { key: 'boscaSha', label: 'Bosca SHA', width: '1fr' },
  { key: 'githubSha', label: 'GitHub SHA', width: '1fr' }, { key: 'modified', label: 'Observed', width: '1fr' },
] : view.value === 'pullRequestStates' ? [
  { key: 'counterparts', label: 'Pull requests', width: '1fr' }, { key: 'problem', label: 'Problem', width: '1fr' },
  { key: 'snapshots', label: 'Compared state', width: '1fr' }, { key: 'modified', label: 'Observed', width: '1fr' },
] : [
  { key: 'deliveryId', label: 'Delivery', width: '1fr' }, { key: 'event', label: 'Event', width: '1fr' },
  { key: 'attribution', label: 'Originating user', width: '1fr' }, { key: 'state', label: 'Intake', width: '1fr' },
  { key: 'created', label: 'Received', width: '1fr' },
])

async function load() {
  const current = ++request
  const selected = view.value
  loading.value = true
  error.value = ''
  rows.value = []
  canNext.value = false
  try {
    const result = await query<{ github: History }>(documents[selected], {
      repositoryId: props.repositoryId, offset: offset.value, limit: limit + 1,
    })
    if (current !== request) return
    rows.value = result.github[selected].slice(0, limit)
    canNext.value = result.github[selected].length > limit
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : 'Could not load synchronization history.'
  } finally {
    if (current === request) loading.value = false
  }
}

function selectView(label: string) {
  const option = views.find(option => option.label === label)
  if (blocked.value || !option || option.id === view.value) return
  view.value = option.id
  offset.value = 0
  notice.value = ''
  void load()
}

async function synchronize(operation: Operation) {
  if (!props.enabled || loading.value || view.value === 'deliveries' || blocked.value) return
  if (operation !== 'reconcile' && view.value !== 'refStates') return
  const selected = view.value
  running.value = operation
  notice.value = ''
  let failure = ''
  try {
    await mutation(operation === 'reconcile' ? reconcileDocuments[selected] : transferDocuments[operation], { repositoryId: props.repositoryId })
    const action = operation === 'reconcile' ? 'Reconciliation' : operation === 'pullRefs' ? 'Pull' : 'Push'
    notice.value = `${action} finished. Review the observed state and any remaining problems.`
  } catch (e) {
    failure = e instanceof Error ? e.message : 'Synchronization failed.'
  } finally {
    // Synchronization can commit some refs before another operation fails.
    await load()
    if (failure) error.value = failure
    running.value = null
  }
}

function showResolution(state: RefState) {
  if (!props.enabled || loading.value || blocked.value || !state.conflict) return
  resolution.value = { ...state }
  choice.value = ''
  resolutionError.value = ''
}

function closeResolution() {
  if (busy.value) return
  resolution.value = null
  choice.value = ''
  resolutionError.value = ''
}

async function resolveConflict() {
  if (!props.enabled || !resolution.value || !choice.value || busy.value) return
  const reviewed = resolution.value
  const host = resolutionHost.value
  resolving.value = true
  resolutionError.value = ''
  notice.value = ''
  try {
    await mutation(resolveDocument, { input: {
      repositoryId: props.repositoryId, ref: reviewed.ref, resolution: choice.value,
      expectedBoscaSha: reviewed.boscaSha, expectedGitHubSha: reviewed.githubSha,
    } })
    resolution.value = null
    choice.value = ''
    notice.value = `Conflict resolved using the ${host} value.`
  } catch (e) {
    resolutionError.value = e instanceof Error ? e.message : 'Could not resolve the conflict.'
  } finally {
    // A rejected resolution may have committed newer observations for review.
    await load()
    resolving.value = false
  }
}

function page(direction: number) {
  offset.value += direction * limit
  void load()
}

onMounted(load)
</script>

<template>
  <SectionCard title="Synchronization history">
    <template #right>
      <Button size="sm" :disabled="loading || blocked" @click="load">Refresh</Button>
    </template>
    <fieldset :disabled="blocked" aria-label="Synchronization history views" class="history-tabs">
      <Tabs
        :tabs="views.map(option => option.label)"
        :model-value="viewLabel"
        :accent="accent"
        @update:model-value="selectView" />
    </fieldset>
    <div v-if="view !== 'deliveries'" class="toolbar">
      <template v-if="view === 'refStates'">
        <Button :disabled="!enabled || loading || blocked" @click="synchronize('pullRefs')">{{ running === 'pullRefs' ? 'Pulling…' : 'Pull from GitHub' }}</Button>
        <Button :disabled="!enabled || loading || blocked" @click="synchronize('pushRefs')">{{ running === 'pushRefs' ? 'Pushing…' : 'Push to GitHub' }}</Button>
      </template>
      <Button :disabled="!enabled || loading || blocked" @click="synchronize('reconcile')">{{ running === 'reconcile' ? 'Reconciling…' : 'Reconcile' }}</Button>
    </div>
    <p v-if="view === 'refStates'" class="help">Pull imports GitHub branches and tags into Bosca; Push sends Bosca branches and tags to GitHub. Reconcile transfers changes in either direction. These actions use your repository Edit permission and preserve conflicts. Imports work without a webhook delivery and use your identity for build permission checks. Tracked deletions follow destination protections; one-direction transfers preserve untracked destination-only refs.</p>
    <p class="help">Use Resolve on a conflicting branch or tag to keep one host's value. To preserve changes from both hosts, merge their histories in Git, then pull or push. Pull request edits are resolved on their owning host before reconciliation.</p>
    <p v-if="!enabled" class="help">Enable the repository pairing to reconcile changes.</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="help">{{ notice }}</p>
    <div class="history-table">
      <GlassTable
        :columns="columns"
        :rows="rows"
        :loading="loading"
        :arrow="false"
        empty-text="No recorded state on this page.">
        <template #col-state="{ row }">
          <span v-if="view === 'deliveries'">{{ (row as Delivery).ignored ? 'Ignored' : 'Accepted' }}</span>
          <div v-else class="ref-state">
            <span :class="{ error: (row as RefState).conflict }">{{ (row as RefState).conflict ? 'Conflict' : ((row as RefState).synchronized && (row as RefState).boscaSha === (row as RefState).githubSha ? 'In sync' : 'Awaiting synchronization') }}</span>
            <Button
              v-if="(row as RefState).conflict"
              size="sm"
              :disabled="!enabled || loading || blocked"
              @click="showResolution(row as RefState)">Resolve</Button>
          </div>
        </template>
        <template #col-counterparts="{ row }">
          <NuxtLink v-if="(row as PrState).pullRequestId" :to="`/git/pulls/${(row as PrState).pullRequestId}`">Bosca pull request</NuxtLink>
          <span v-else>No Bosca counterpart</span>
          <div>{{ (row as PrState).githubNumber ? `GitHub #${(row as PrState).githubNumber}` : 'No GitHub counterpart' }}</div>
        </template>
        <template #col-problem="{ row }"><span :class="{ error: (row as PrState).problem }">{{ (row as PrState).problem ?? '—' }}</span></template>
        <template #col-snapshots="{ row }">
          <details v-if="(row as PrState).snapshot || (row as PrState).bosca || (row as PrState).github">
            <summary>Compare snapshots</summary>
            <div v-for="side in (['snapshot', 'bosca', 'github'] as const)" :key="side" class="snapshot">
              <strong>{{ side === 'snapshot' ? 'Last common state' : side === 'bosca' ? 'Bosca' : 'GitHub' }}</strong>
              <template v-if="(row as PrState)[side]">
                <div>{{ (row as PrState)[side]?.title }} · {{ (row as PrState)[side]?.status }}</div>
                <div>{{ (row as PrState)[side]?.sourceBranch }} → {{ (row as PrState)[side]?.targetBranch }}</div>
                <pre>{{ (row as PrState)[side]?.description ?? '(no description)' }}</pre>
                <code v-if="(row as PrState)[side]?.mergeSha">{{ (row as PrState)[side]?.mergeSha }}</code>
              </template>
              <div v-else>Not observed</div>
            </div>
          </details>
          <span v-else>—</span>
        </template>
        <template #col-attribution="{ row }">
          <NuxtLink v-if="(row as Delivery).principalId" :to="`/system/security/principals/${(row as Delivery).principalId}`">{{ (row as Delivery).principalId }}</NuxtLink>
          <span v-else>Unattributed</span>
          <div v-if="(row as Delivery).githubUserId">GitHub user {{ (row as Delivery).githubUserId }}</div>
        </template>
        <template #col-created="{ row }">{{ new Date((row as Delivery).created).toLocaleString() }}</template>
        <template #col-modified="{ row }">{{ new Date((row as RefState | PrState).modified).toLocaleString() }}</template>
        <template #col-sha="{ row }"><code>{{ (row as RefState).sha ?? 'Absent' }}</code></template>
        <template #col-boscaSha="{ row }"><code>{{ (row as RefState).boscaSha ?? 'Absent' }}</code></template>
        <template #col-githubSha="{ row }"><code>{{ (row as RefState).githubSha ?? 'Absent' }}</code></template>
      </GlassTable>
    </div>
    <div class="pager">
      <Button :disabled="offset === 0 || loading || blocked" @click="page(-1)">Previous</Button>
      <span>Page {{ offset / limit + 1 }}</span>
      <Button :disabled="!canNext || loading || blocked" @click="page(1)">Next</Button>
    </div>
  </SectionCard>
  <Modal
    v-if="resolution"
    title="Resolve ref conflict"
    :accent="accent"
    @close="closeResolution">
    <form class="resolution-form" @submit.stop.prevent="resolveConflict">
      <p>Resolve <code>{{ resolution.ref }}</code></p>
      <dl class="resolution-values">
        <dt>Bosca</dt><dd><code>{{ resolution.boscaSha ?? 'Absent' }}</code></dd>
        <dt>GitHub</dt><dd><code>{{ resolution.githubSha ?? 'Absent' }}</code></dd>
      </dl>
      <Select
        v-model="choice"
        label="Keep value from"
        :disabled="busy"
        :options="[{ value: 'GITHUB', label: 'GitHub' }, { value: 'BOSCA', label: 'Bosca' }]" />
      <p v-if="choice && chosenSha" class="resolution-effect">This replaces the {{ resolutionTarget }} value with the {{ resolutionHost }} value. Changes available only in {{ resolutionTarget }} will no longer be on this branch or tag.</p>
      <p v-else-if="choice" class="resolution-effect">This deletes the ref from {{ resolutionTarget }} to match its absence in {{ resolutionHost }}.</p>
      <p class="resolution-help">Only this ref is affected. Both reviewed values are checked before the transfer, and destination protections still apply.</p>
      <p v-if="resolutionError" role="alert" class="error">{{ resolutionError }} Close this dialog to review the refreshed conflict.</p>
      <div class="resolution-actions">
        <Button type="button" :disabled="busy" @click="closeResolution">Cancel</Button>
        <Button
          type="submit"
          primary
          :accent="accent"
          :disabled="busy || !choice">{{ resolving ? 'Resolving…' : choice ? `Keep ${resolutionHost}` : 'Resolve conflict' }}</Button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.toolbar, .pager { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 16px; }
.history-tabs { border: 0; margin: 0; padding: 0 16px; min-width: 0; }
.history-tabs :deep(.tab:disabled) { cursor: default; opacity: 0.5; }
.pager { justify-content: center; }
.help { margin: 0; padding: 0 16px 16px; font-size: 13px; color: var(--fg-2); line-height: 1.6; }
.history-tabs + .help { padding-top: 16px; }
.error { color: var(--err); }
p.error { padding: 0 16px; }
.snapshot { padding: 12px 0; min-width: 240px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 240px; overflow: auto; }
code { font-size: 11px; overflow-wrap: anywhere; }
.history-table { overflow-x: auto; }
.history-table :deep(.glass-table) { min-width: 850px; }
a { text-decoration: underline; }
.ref-state { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.resolution-form { display: flex; flex-direction: column; gap: 16px; }
.resolution-form p { margin: 0; }
.resolution-values { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px 16px; margin: 0; }
.resolution-values dd { margin: 0; overflow-wrap: anywhere; }
.resolution-effect { font-weight: 600; }
.resolution-help { color: var(--fg-2); font-size: 13px; }
.resolution-actions { display: flex; justify-content: flex-end; gap: 12px; }
</style>
