<script setup lang="ts">
import gql from 'graphql-tag'
import { defineAsyncComponent } from 'vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'

const JsonEditorVue = defineAsyncComponent(async () => (await import('json-editor-vue')).default)

interface Totals { total: number; pending: number; running: number; completed: number; failed: number; totalBytes: number; verifiedBytes: number }
interface Run {
  key: string; status: string; version: number; attempts: number; path: string; error: string | null
  created: string; modified: string; summary: Totals; result: Totals | null
}
interface Item { key: string; status: string; error: string | null; payload: { key: string; store: string; length: number }; verifiedBytes: number }
interface NativeRun { key: string; status: string; error: string | null; modified: string; result: { path: string; manifest: { completed: string } } | null }
const { query, mutation } = useGraphQL()
const operation = ref('RESTORE')
const sourceId = ref('')
const destinationId = ref('')
const scope = ref('')
const runs = ref<Run[]>([])
const lastSuccessful = ref<Run | null>(null)
const offset = ref(0)
const loadedScope = ref<Record<string, unknown> | null>(null)
const selected = ref<Run | null>(null)
const items = ref<Item[]>([])
const itemOffset = ref(0)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const requestKey = ref('')
const restoreDestination = ref('')
const backupKey = ref('')
const manifestPath = ref('')
const targetMapping = ref<unknown>({ content: 'recovered-content' })
const restoreOpen = ref(false)
const nativeSource = ref('')
const nativeStorage = ref('')
const nativeRuns = ref<NativeRun[]>([])
const nativeOffset = ref(0)
const nativeSelection = ref<Record<string, unknown> | null>(null)

const runFields = gql`fragment RecoveryRun on ObjectBackupRun {
  key status version attempts path error created modified
  summary { total pending running completed failed totalBytes verifiedBytes }
  result { total pending running completed failed totalBytes verifiedBytes }
}`
const listGql = gql`${runFields}
  query RecoveryRuns($operation: ObjectBackupOperation!, $sourceId: String!, $destinationId: String!, $scope: String!, $offset: Int!, $limit: Int!) {
    backups { objectRuns {
      list(operation: $operation, sourceId: $sourceId, destinationId: $destinationId, scope: $scope, offset: $offset, limit: $limit) { ...RecoveryRun }
      lastSuccessful(operation: $operation, sourceId: $sourceId, destinationId: $destinationId, scope: $scope) { ...RecoveryRun }
    } }
  }`
const itemsGql = gql`query RecoveryItems($key: String!, $offset: Int!, $limit: Int!) {
  backups { objectRuns { run(key: $key) { items(offset: $offset, limit: $limit) { key status error verifiedBytes payload { store key length } } } } }
}`
const getGql = gql`${runFields} query RecoveryRunStatus($key: String!) {
  backups { objectRuns { run(key: $key) { ...RecoveryRun } } }
}`
const prepareGql = gql`${runFields}
  mutation PrepareObjectRecovery($requestKey: String!, $destinationId: String!, $backupKey: String!, $path: String!, $stores: [ObjectBackupStoreMappingInput!]!) {
    backups { objectRuns { prepareRestoreConfigured(requestKey: $requestKey, destinationId: $destinationId, backupKey: $backupKey, path: $path, stores: $stores) { ...RecoveryRun } } }
  }`
const retryGql = gql`${runFields} mutation RetryObjectRecovery($key: String!, $expectedVersion: Long!) {
  backups { objectRuns { retry(key: $key, expectedVersion: $expectedVersion) { ...RecoveryRun } } }
}`
const dispatchGql = gql`mutation DispatchObjectRecovery($key: String!, $expectedVersion: Long!) {
  backups { objectRuns { dispatch(key: $key, expectedVersion: $expectedVersion) { key } } }
}`
const cancelGql = gql`${runFields} mutation CancelObjectRecovery($key: String!, $expectedVersion: Long!, $reason: String!) {
  backups { objectRuns { cancelPending(key: $key, expectedVersion: $expectedVersion, reason: $reason) { ...RecoveryRun } } }
}`
const nativeGql = gql`query RecoveryDatabaseRuns($sourceId: String!, $storageKey: String!, $offset: Int!, $limit: Int!) {
  backups { postgresRuns(sourceId: $sourceId, storageKey: $storageKey, offset: $offset, limit: $limit) {
    key status error modified result { path manifest { completed } }
  } }
}`

async function perform(block: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await block() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Backup operation failed' }
  finally { busy.value = false }
}
async function loadRuns(page = 0) {
  await perform(async () => {
    if (page === 0) {
      if (![sourceId.value, destinationId.value, scope.value].every(v => v.trim())) throw new Error('Enter a source, destination and scope')
      loadedScope.value = { operation: operation.value, sourceId: sourceId.value, destinationId: destinationId.value, scope: scope.value }
    }
    const result = await query<{ backups: { objectRuns: { list: Run[]; lastSuccessful: Run | null } } }>(listGql, { ...loadedScope.value, offset: page, limit: 20 })
    runs.value = result.backups.objectRuns.list
    lastSuccessful.value = result.backups.objectRuns.lastSuccessful
    offset.value = page
  })
}
async function loadItems(run: Run, page = 0) {
  await perform(async () => {
    const result = await query<{ backups: { objectRuns: { run: { items: Item[] } | null } } }>(itemsGql, { key: run.key, offset: page, limit: 20 })
    if (!result.backups.objectRuns.run) throw new Error('Object run no longer exists')
    selected.value = run
    items.value = result.backups.objectRuns.run.items
    itemOffset.value = page
  })
}
async function prepare() {
  await perform(async () => {
    if (![requestKey.value, restoreDestination.value, backupKey.value, manifestPath.value].every(v => v.trim())) throw new Error('Complete the restore settings')
    const mapping = targetMapping.value
    if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping) || Object.entries(mapping).some(([store, value]) => !store.trim() || typeof value !== 'string' || !value.trim())) {
      throw new Error('Map each logical store to a storage ID')
    }
    const stores = Object.entries(mapping).map(([store, storageKey]) => ({ store, storageKey }))
    const result = await mutation<{ backups: { objectRuns: { prepareRestoreConfigured: Run } } }>(prepareGql, {
      requestKey: requestKey.value, destinationId: restoreDestination.value, backupKey: backupKey.value, path: manifestPath.value, stores,
    })
    runs.value = [result.backups.objectRuns.prepareRestoreConfigured]
    selected.value = null
    items.value = []
    lastSuccessful.value = null
    offset.value = 0
  })
}
async function action(run: Run, kind: 'retry' | 'dispatch' | 'cancelPending') {
  await perform(async () => {
    const doc = kind === 'retry' ? retryGql : kind === 'dispatch' ? dispatchGql : cancelGql
    const result = await mutation<{ backups: { objectRuns: Partial<Record<'retry' | 'cancelPending', Run>> } }>(doc, {
      key: run.key, expectedVersion: run.version, ...(kind === 'cancelPending' ? { reason: 'Cancelled from Studio' } : {}),
    })
    if (kind === 'dispatch') {
      notice.value = `Queued ${run.key}. Refresh its status to check progress.`
      return
    }
    const updated = result.backups.objectRuns[kind]
    if (updated) {
      runs.value = runs.value.map(r => r.key === run.key ? updated : r)
      if (selected.value?.key === run.key) selected.value = updated
    }
  })
}
async function refreshRun(run: Run) {
  await perform(async () => {
    const result = await query<{ backups: { objectRuns: { run: Run | null } } }>(getGql, { key: run.key })
    const updated = result.backups.objectRuns.run
    if (!updated) throw new Error('Object run no longer exists')
    runs.value = runs.value.map(r => r.key === run.key ? updated : r)
    if (selected.value?.key === run.key) selected.value = updated
  })
}
async function loadNative(page = 0) {
  await perform(async () => {
    if (page === 0) {
      if (!nativeSource.value.trim() || !nativeStorage.value.trim()) throw new Error('Enter the database source and backup storage')
      nativeSelection.value = { sourceId: nativeSource.value, storageKey: nativeStorage.value }
    }
    const result = await query<{ backups: { postgresRuns: NativeRun[] } }>(nativeGql, { ...nativeSelection.value, offset: page, limit: 20 })
    nativeRuns.value = result.backups.postgresRuns
    nativeOffset.value = page
  })
}
function date(value: string) { return new Date(value).toLocaleString() }
</script>

<template>
  <SectionCard title="Database and object recovery" padded>
    <p>Recover PostgreSQL first using CloudNativePG or your PostgreSQL backup. Then restore object storage below.</p>
    <p><NuxtLink to="/pipelines/all">Configure backup Pipelines and schedules</NuxtLink> · <NuxtLink to="/pipelines/runs">Pipeline history</NuxtLink></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <details>
      <summary>Database backup artifacts</summary>
      <div class="filters">
        <TextInput v-model="nativeSource" label="Database source" />
        <TextInput v-model="nativeStorage" label="Database backup storage" />
        <Button :disabled="busy" @click="loadNative()">Load database backups</Button>
      </div>
      <p v-for="run in nativeRuns" :key="run.key">{{ run.key }} · {{ run.status }} · {{ date(run.result?.manifest.completed ?? run.modified) }}<br>{{ run.result?.path }} {{ run.error }}</p>
      <Button :disabled="busy || nativeOffset === 0" @click="loadNative(nativeOffset - 20)">Previous database backups</Button>
      <Button :disabled="busy || nativeRuns.length < 20" @click="loadNative(nativeOffset + 20)">Next database backups</Button>
    </details>
    <details @toggle="restoreOpen = ($event.target as HTMLDetailsElement).open">
      <summary>Prepare an object restore</summary>
      <div class="filters">
        <TextInput v-model="requestKey" label="Request key" />
        <TextInput v-model="restoreDestination" label="Restore destination" />
        <TextInput v-model="backupKey" label="Backup storage" />
        <TextInput v-model="manifestPath" label="Manifest path" />
      </div>
      <p>Map logical store names to storage IDs configured on this instance.</p>
      <JsonEditorVue
        v-if="restoreOpen"
        v-model="targetMapping"
        mode="tree"
        :stringified="false"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="jse-theme-dark json-editor"
      />
      <p>Matching files are reused. Selected files with different bytes are replaced. Preparation saves the request; dispatch starts copying.</p>
      <Button :disabled="busy" @click="prepare">Prepare object restore</Button>
    </details>
    <h3>Object runs</h3>
    <div class="filters">
      <Select v-model="operation" label="Operation" :options="[{ value: 'RESTORE', label: 'Restore' }, { value: 'BACKUP', label: 'Backup' }]" />
      <TextInput v-model="sourceId" label="Source identity" />
      <TextInput v-model="destinationId" label="Destination identity" />
      <TextInput v-model="scope" label="Scope" />
      <Button :disabled="busy" @click="loadRuns()">Load object runs</Button>
    </div>
    <p v-if="lastSuccessful">Last successful: {{ lastSuccessful.key }} · {{ date(lastSuccessful.modified) }}</p>
    <article v-for="run in runs" :key="run.key" class="run">
      <h4>{{ run.key }} · {{ run.status }}</h4>
      <p>{{ date(run.modified) }} · {{ run.attempts }} {{ run.attempts === 1 ? 'attempt' : 'attempts' }} · {{ run.path }}</p>
      <p>Current checkpoints: {{ run.summary.completed }} completed · {{ run.summary.pending }} pending · {{ run.summary.running }} running · {{ run.summary.failed }} failed</p>
      <p v-if="run.result">Last outcome: {{ run.result.completed }} / {{ run.result.total }} completed · {{ run.result.verifiedBytes }} verified bytes</p>
      <p v-if="run.error" class="error">{{ run.error }}</p>
      <div class="actions">
        <Button :disabled="busy" @click="refreshRun(run)">Refresh run</Button>
        <Button :disabled="busy" @click="loadItems(run)">View objects</Button>
        <Button v-if="['FAILED', 'PARTIAL', 'CANCELLED'].includes(run.status)" :disabled="busy" @click="action(run, 'retry')">Retry</Button>
        <Button v-if="run.status === 'PENDING'" :disabled="busy" @click="action(run, 'dispatch')">Dispatch</Button>
        <Button v-if="run.status === 'PENDING'" :disabled="busy" @click="action(run, 'cancelPending')">Cancel pending</Button>
      </div>
    </article>
    <div class="actions">
      <Button :disabled="busy || offset === 0" @click="loadRuns(offset - 20)">Previous object runs</Button>
      <Button :disabled="busy || runs.length < 20" @click="loadRuns(offset + 20)">Next object runs</Button>
    </div>
    <div v-if="selected">
      <h4>Objects in {{ selected.key }}</h4>
      <p v-for="item in items" :key="item.key">{{ item.payload.store }} / {{ item.payload.key }} · {{ item.status }} · {{ item.verifiedBytes }} / {{ item.payload.length }} bytes <span class="error">{{ item.error }}</span></p>
      <Button :disabled="busy || itemOffset === 0" @click="loadItems(selected, itemOffset - 20)">Previous objects</Button>
      <Button :disabled="busy || items.length < 20" @click="loadItems(selected, itemOffset + 20)">Next objects</Button>
    </div>
  </SectionCard>
</template>

<style scoped>
a { color: var(--fg-1); text-underline-offset: 0.2rem; }
h3, h4 { margin: 0.75rem 0; }
p { margin: 0.75rem 0; overflow-wrap: anywhere; }
summary { cursor: pointer; padding: 0.75rem 0; }
.filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin: 0.75rem 0; }
.actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.run { padding: 1rem 0; border-bottom: 1px solid var(--line); }
.json-editor { min-height: 160px; }
.error, [role='alert'] { color: var(--danger, #ff5d6c); }
</style>
