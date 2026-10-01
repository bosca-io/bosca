<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// Bidirectional git sync (when editing a query bound to a git file) needs commit
// metadata. Resolve it from the authenticated profile so a Studio edit becomes
// a commit attributed to the editor — shared resolver in useGitCommitAuthor.
const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

const queryId = computed(() => route.params.id as string)
const isNew = computed(() => queryId.value === 'new')

const queryGql = gql`
  query GetAnalyticsQuery($id: UUID!) {
    analytics { queries { queryById(id: $id) { id key name description query configuration refreshIntervalSeconds parameters { parameter name description type arrayType defaultValue required } permissions { action groupId group { id name } } } } }
  }
`
const addGql = gql`
  mutation AddQuery($query: AnalyticsQueryInput!) { analytics { queries { add(query: $query) { id } } } }
`
const editGql = gql`
  mutation EditQuery($query: AnalyticsQueryInput!, $authorName: String, $authorEmail: String) {
    analytics { queries { edit(query: $query, authorName: $authorName, authorEmail: $authorEmail) { id } } }
  }
`
const executeGql = gql`
  query ExecuteQuery($queryId: UUID!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
    analytics { queries { execute(queryId: $queryId, parameters: $parameters) { records cached stale refreshedAt } } }
  }
`
const refreshCacheGql = gql`
  mutation RefreshAnalyticsQueryCache($queryId: UUID!) {
    analytics { queries { refresh(queryId: $queryId) } }
  }
`
const groupsGql = gql`
  query AnalyticsQueryGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
`
const addPermissionGql = gql`
  mutation AddAnalyticsQueryPermission($permission: PermissionInput!) {
    analytics { queries { addPermission(permission: $permission) { action groupId } } }
  }
`
const removePermissionGql = gql`
  mutation RemoveAnalyticsQueryPermission($permission: PermissionInput!) {
    analytics { queries { deletePermission(permission: $permission) { action groupId } } }
  }
`

interface QueryPermission {
  action: string
  groupId: string
  group: { id: string; name: string } | null
}

interface AnalyticsQuery {
  id: string; key: string; name: string; description: string | null; query: string | null; configuration: Record<string, unknown>
  refreshIntervalSeconds: number | null
  parameters: QueryParam[]
  permissions: QueryPermission[]
}

// Use a computed so the query re-fires when queryId changes (e.g. after
// router.replace following a successful create). Returning undefined when
// isNew is true causes useGraphQL to skip the fetch entirely.
const queryDetailId = computed<string | undefined>(() => isNew.value ? undefined : queryId.value)
const { data, refresh } = useAsyncQuery<{ analytics: { queries: { queryById: AnalyticsQuery | null } } }>(
  'query-detail', queryGql, { id: queryDetailId },
)

const queryData = computed(() => isNew.value ? null : data.value?.analytics?.queries?.queryById)

const key = ref('')
const name = ref('')
const description = ref('')
const sqlQuery = ref('')
const configuration = ref<Record<string, unknown>>({})
// Cleared input = caching disabled; validated and converted to an Int (or null)
// on save. The raw ref is string | number because Vue auto-applies `.number`
// coercion to `type="number"` inputs (edits arrive as numbers, a cleared field
// as ''); the computed normalizes to the string the TextInput model expects.
const refreshIntervalRaw = ref<string | number>('')
const refreshInterval = computed<string>({
  get: () => String(refreshIntervalRaw.value ?? ''),
  set: (v) => { refreshIntervalRaw.value = v },
})
const refreshingCache = ref(false)
const showConfig = ref(false)
const paramsCollapsed = ref(true)
const saving = ref(false)

interface GitSource { repositoryId: string; ref: string; path: string; resolvedCommit?: string | null }
const gitSource = ref<GitSource | null>(null)
const gitSyncLoading = ref(false)

const querySourceRefGql = gql`
  query GetQuerySourceRef($queryId: UUID!) {
    git { querySourceRef(queryId: $queryId) { repositoryId ref path resolvedCommit } }
  }
`
const setQuerySourceRefGql = gql`
  mutation SetQuerySourceRef($queryId: UUID!, $repositoryId: UUID!, $ref: String!, $path: String!) {
    git { setQuerySourceRef(queryId: $queryId, repositoryId: $repositoryId, ref: $ref, path: $path) { repositoryId ref path resolvedCommit } }
  }
`
const removeQuerySourceRefGql = gql`
  mutation RemoveQuerySourceRef($queryId: UUID!) {
    git { removeQuerySourceRef(queryId: $queryId) }
  }
`

interface QueryParam {
  parameter: string
  name: string
  description: string
  type: string
  arrayType: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- dynamic value driven by param type
  defaultValue: any
  required: boolean
}
const queryParams = ref<QueryParam[]>([])

const PARAM_TYPES = ['STRING', 'INTEGER', 'FLOAT', 'BOOLEAN', 'DATE', 'DATETIME', 'TIME', 'ARRAY', 'OBJECT', 'NONE'].map(t => ({ value: t, label: t }))

// Execute runs the *persisted* query server-side, so local edits would be
// silently ignored — a stale result that looks current. Snapshot the form
// state whenever the record loads (or reloads after save) and disable
// Execute while the live state differs. Null means "not loaded yet", which
// also reads as dirty so Execute stays disabled until the record arrives.
const savedSnapshot = ref<string | null>(null)
function serializeFormState(): string {
  return JSON.stringify({
    key: key.value,
    name: name.value,
    description: description.value,
    query: sqlQuery.value,
    configuration: configuration.value,
    refreshInterval: refreshInterval.value.trim(),
    parameters: queryParams.value,
  })
}
const hasUnsavedChanges = computed(() => savedSnapshot.value !== serializeFormState())

const executing = ref(false)
const results = ref<Record<string, unknown>[] | null>(null)
const resultsMeta = ref<{ cached: boolean; stale: boolean; refreshedAt: string | null } | null>(null)
const executeError = ref('')

const ANALYTICS_QUERY_PERMISSION_ACTIONS: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'EXECUTE', label: 'EXECUTE' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'DELETE', label: 'DELETE' },
]

const PERMISSION_ACTION_COLORS: Record<string, string> = {
  VIEW: '#5ec5ff',
  EDIT: '#4ade80',
  EXECUTE: '#c084fc',
  MANAGE: '#ff5d6c',
  DELETE: '#ffb547',
}

interface SecurityGroup { id: string; name: string; description: string | null }
const allGroups = ref<SecurityGroup[]>([])
const permAddOpen = ref(false)
const permAddGroupId = ref<string | undefined>(undefined)
const permAddAction = ref<string | undefined>('VIEW')
const permAdding = ref(false)
const permDeleteTarget = ref<QueryPermission | null>(null)
const permDeleteLoading = ref(false)
const showPermissionsModal = ref(false)

const permissions = computed<QueryPermission[]>(() => queryData.value?.permissions ?? [])
const groupOptions = computed<SelectOption[]>(() =>
  allGroups.value.map(g => ({ value: g.id, label: g.name })),
)
const permissionColumns: GlassTableColumn[] = [
  { key: 'group', label: 'Group', width: 'minmax(140px, 2fr)' },
  { key: 'action', label: 'Action', width: '140px' },
]

async function loadGroups() {
  if (allGroups.value.length > 0) return
  try {
    const result = await gqlQuery<{ security: { groups: { all: SecurityGroup[] } } }>(
      groupsGql, {},
    )
    allGroups.value = result.security?.groups?.all ?? []
  } catch { /* leave empty */ }
}

function openAddPermission() {
  permAddGroupId.value = undefined
  permAddAction.value = 'VIEW'
  permAddOpen.value = true
  void loadGroups()
}

async function confirmAddPermission() {
  if (!queryId.value || !permAddGroupId.value || !permAddAction.value) return
  permAdding.value = true
  try {
    await gqlMutation(addPermissionGql, {
      permission: {
        action: permAddAction.value,
        entityId: queryId.value,
        groupId: permAddGroupId.value,
      },
    })
    toast.success('Permission added')
    permAddOpen.value = false
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add permission')
  } finally {
    permAdding.value = false
  }
}

async function confirmRemovePermission() {
  const p = permDeleteTarget.value
  if (!p || !queryId.value) return
  permDeleteLoading.value = true
  try {
    await gqlMutation(removePermissionGql, {
      permission: { action: p.action, entityId: queryId.value, groupId: p.groupId },
    })
    toast.success('Permission removed')
    permDeleteTarget.value = null
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove permission')
  } finally {
    permDeleteLoading.value = false
  }
}

watch(queryData, (q) => {
  if (q) {
    key.value = q.key; name.value = q.name; description.value = q.description ?? ''
    sqlQuery.value = q.query ?? ''
    configuration.value = q.configuration ?? {}
    refreshInterval.value = q.refreshIntervalSeconds != null ? String(q.refreshIntervalSeconds) : ''
    loadSourceRef()
    queryParams.value = (q.parameters ?? []).map((p: QueryParam) => ({
      parameter: p.parameter ?? '',
      name: p.name ?? '',
      description: p.description ?? '',
      type: p.type ?? 'STRING',
      arrayType: p.arrayType ?? 'NONE',
      defaultValue: p.defaultValue,
      required: p.required ?? false,
    }))
    savedSnapshot.value = serializeFormState()
  }
}, { immediate: true })

async function loadSourceRef() {
  if (isNew.value) return
  try {
    const result = await gqlQuery<{ git: { querySourceRef: GitSource | null } }>(querySourceRefGql, { queryId: queryId.value })
    gitSource.value = result.git?.querySourceRef ?? null
    if (gitSource.value) syncGitContent()
  } catch { /* no source ref */ }
}

async function syncGitContent() {
  const src = gitSource.value
  if (!src) return
  gitSyncLoading.value = true
  try {
    const result = await gqlQuery<{ git: { blob: { content: string | null } | null } }>(gql`
      query Blob($repositoryId: UUID!, $ref: String!, $path: String!) {
        git { blob(repositoryId: $repositoryId, ref: $ref, path: $path) { content } }
      }
    `, { repositoryId: src.repositoryId, ref: src.ref, path: src.path })
    const content = result.git?.blob?.content
    if (content != null) sqlQuery.value = content
  } catch { /* keep existing query */ }
  finally { gitSyncLoading.value = false }
}

async function attachGitSource(src: GitSource) {
  if (isNew.value) { toast.error('Save the query first'); return }
  try {
    const result = await gqlMutation<{ git: { setQuerySourceRef: GitSource } }>(setQuerySourceRefGql, {
      queryId: queryId.value, repositoryId: src.repositoryId, ref: src.ref, path: src.path,
    })
    gitSource.value = result.git.setQuerySourceRef
    syncGitContent()
    toast.success('Git source attached')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to attach')
  }
}

async function detachGitSource() {
  if (isNew.value) return
  try {
    await gqlMutation(removeQuerySourceRefGql, { queryId: queryId.value })
    gitSource.value = null
    toast.success('Git source detached')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to detach')
  }
}

const resultColumns = computed(() => {
  if (!results.value?.length) return []
  return Object.keys(results.value[0]!).map(k => ({ key: k, label: k, width: '1fr' }))
})

async function onSave() {
  let refreshIntervalSeconds: number | null = null
  const intervalText = refreshInterval.value.trim()
  if (intervalText) {
    const parsed = Number(intervalText)
    if (!Number.isInteger(parsed) || parsed < 60) {
      toast.error('Cache refresh interval must be at least 60 seconds')
      return
    }
    refreshIntervalSeconds = parsed
  }
  saving.value = true
  try {
    const params = queryParams.value.map(p => ({
      parameter: p.parameter,
      name: p.name,
      description: p.description,
      type: p.type,
      ...(p.type === 'ARRAY' ? { arrayType: p.arrayType } : {}),
      ...(p.defaultValue != null ? { defaultValue: p.defaultValue } : {}),
      required: p.required,
    }))
    const input = { key: key.value, name: name.value, description: description.value, query: sqlQuery.value, configuration: configuration.value, refreshIntervalSeconds, parameters: params }
    if (isNew.value) {
      const result = await gqlMutation<{ analytics: { queries: { add: { id: string } } } }>(addGql, { query: input })
      toast.success('Query created'); router.replace(`/analytics/queries/${result.analytics.queries.add.id}`)
    } else {
      // Pass author info only when the query has a git binding — without one,
      // the backend would no-op anyway, and skipping the args keeps intent clear
      // in network traces.
      const editVars: { query: typeof input & { id: string }; authorName?: string; authorEmail?: string } = {
        query: { ...input, id: queryId.value },
      }
      if (gitSource.value) {
        editVars.authorName = commitAuthor.value.authorName
        editVars.authorEmail = commitAuthor.value.authorEmail
      }
      await gqlMutation(editGql, editVars); toast.success('Saved'); refresh()
    }
  } catch { toast.error('Failed to save') } finally { saving.value = false }
}

function resolveExecutionParameters(overrides?: { parameter: string; value: unknown }[]): { parameter: string; value: unknown }[] {
  if (overrides) return overrides
  return queryParams.value
    .filter(p => p.defaultValue != null)
    .map(p => ({ parameter: p.parameter, value: p.defaultValue }))
}

const showExecuteModal = ref(false)
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- dynamic value driven by param type
const executeParams = ref<{ parameter: string; name: string; type: string; value: any }[]>([])

function onExecute() {
  if (queryParams.value.length > 0) {
    executeParams.value = queryParams.value.map(p => ({
      parameter: p.parameter,
      name: p.name || p.parameter,
      type: p.type,
      value: p.defaultValue != null ? JSON.parse(JSON.stringify(p.defaultValue)) : null,
    }))
    showExecuteModal.value = true
  } else {
    runExecution()
  }
}

async function runExecution(overrides?: { parameter: string; value: unknown }[]) {
  showExecuteModal.value = false
  executing.value = true; executeError.value = ''; results.value = null; resultsMeta.value = null
  try {
    const result = await gqlQuery<{ analytics: { queries: { execute: { records: Record<string, unknown>[]; cached: boolean; stale: boolean; refreshedAt: string | null } } } }>(executeGql, {
      queryId: queryId.value, parameters: resolveExecutionParameters(overrides),
    })
    results.value = result.analytics.queries.execute.records
    resultsMeta.value = {
      cached: result.analytics.queries.execute.cached,
      stale: result.analytics.queries.execute.stale,
      refreshedAt: result.analytics.queries.execute.refreshedAt,
    }
    if (!results.value.length) executeError.value = 'No results returned.'
  } catch (e: unknown) {
    executeError.value = e instanceof Error ? e.message : 'Execution failed'
  } finally { executing.value = false }
}

async function onRefreshCache() {
  refreshingCache.value = true
  try {
    const result = await gqlMutation<{ analytics: { queries: { refresh: boolean } } }>(refreshCacheGql, { queryId: queryId.value })
    if (result.analytics.queries.refresh) {
      toast.success('Cache refresh started — cached results will update in the background')
    } else {
      toast.warn('Result caching is not enabled for this query')
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to refresh the cache')
  } finally { refreshingCache.value = false }
}

function executeFromModal() {
  const params = executeParams.value
    .filter(p => p.value != null)
    .map(p => ({ parameter: p.parameter, value: p.value }))
  runExecution(params)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('Analytics', 'Queries', isNew ? 'New' : name || '…')" :title="isNew ? 'New Query' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew"
            size="sm"
            icon="pulse"
            :disabled="executing || hasUnsavedChanges"
            :title="hasUnsavedChanges ? 'Save your changes first — Execute runs the last saved query' : undefined"
            @click="onExecute">{{ executing ? 'Running…' : 'Execute' }}</Button>
          <Button
            v-if="!isNew && queryData?.refreshIntervalSeconds != null"
            size="sm"
            icon="refresh"
            :disabled="refreshingCache"
            @click="onRefreshCache">{{ refreshingCache ? 'Refreshing…' : 'Refresh Cache' }}</Button>
          <Button
            v-if="!isNew"
            size="sm"
            icon="lock"
            @click="showPermissionsModal = true">Permissions</Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || !key.trim()"
            @click="onSave">Save</Button>
        </template>
      </PageHeader>
    </template>

    <div class="form-layout">
      <SectionCard title="Configuration">
        <div class="card-body">
          <div class="form-grid">
            <TextInput
              v-model="key"
              label="Key"
              mono
              :disabled="!isNew" />
            <TextInput v-model="name" label="Name" />
          </div>
          <Textarea
            v-model="description"
            label="Description"
            :rows="2"
            style="margin-top: 14px" />
          <div class="form-grid" style="margin-top: 14px">
            <TextInput
              v-model="refreshInterval"
              label="Cache Refresh Interval (seconds)"
              :min="60"
              type="number"
              placeholder="Disabled" />
          </div>
          <div class="cache-hint">When set (minimum 60 seconds), results are cached per parameter combination and become eligible for background refresh after this interval. Actual timing follows the configured sweep cadence. Leave empty to always execute against the analytics store.</div>
        </div>
      </SectionCard>

      <SectionCard title="Source">
        <div class="card-body">
          <div v-if="gitSource" class="git-attached">
            <Icon name="git-commit" :size="14" color="var(--brand-2)" />
            <span class="git-attached__path mono">{{ gitSource.path }}</span>
            <span class="git-attached__ref mono">@ {{ gitSource.ref }}</span>
            <Button size="sm" icon="x" @click="detachGitSource">Detach</Button>
          </div>
          <GitFilePicker
            v-else
            content-type="ANALYTIC_QUERY_PROJECT"
            allow-create
            new-file-placeholder="filename.sql"
            :initial-content="sqlQuery"
            @select="attachGitSource" />
          <div v-if="gitSyncLoading" class="sync-status">Syncing file content…</div>
        </div>
      </SectionCard>

      <SectionCard title="SQL Query">
        <template v-if="gitSource" #right>
          <Button
            size="sm"
            icon="refresh"
            :disabled="gitSyncLoading"
            @click="syncGitContent">{{ gitSyncLoading ? 'Syncing…' : 'Sync' }}</Button>
        </template>
        <div class="card-body">
          <CodeEditor
            v-model="sqlQuery"
            language="sql"
            :rows="14"
            placeholder="SELECT ..."
            :readonly="!!gitSource" />
          <div v-if="gitSource" class="source-hint">Query content managed by git source. Detach to edit manually.</div>
        </div>
      </SectionCard>

      <div class="collapsible-section">
        <div class="collapsible-header-row">
          <button
            type="button"
            class="collapsible-header"
            @click="paramsCollapsed = !paramsCollapsed">
            <Icon :name="paramsCollapsed ? 'chevron' : 'chevronDown'" :size="12" color="var(--fg-3)" />
            <span>Parameters</span>
            <span v-if="queryParams.length" class="collapsible-count">({{ queryParams.length }})</span>
          </button>
          <div class="collapsible-actions">
            <Button size="sm" icon="plus" @click="queryParams.push({ parameter: '', name: '', description: '', type: 'STRING', arrayType: 'NONE', defaultValue: null, required: false }); paramsCollapsed = false">Add</Button>
          </div>
        </div>
        <div v-if="!paramsCollapsed && queryParams.length" class="collapsible-body card-body">
          <div v-for="(param, index) in queryParams" :key="index" class="param-card">
            <div class="param-card-header">
              <span class="param-card-title">{{ param.name || 'New Parameter' }}</span>
              <button class="param-remove" @click="queryParams.splice(index, 1)">×</button>
            </div>
            <div class="form-grid">
              <TextInput v-model="param.name" label="Name" size="sm" />
              <TextInput
                v-model="param.parameter"
                label="Key"
                mono
                size="sm"
                :placeholder="param.name" />
            </div>
            <TextInput
              v-model="param.description"
              label="Description"
              size="sm"
              style="margin-top: 10px" />
            <div class="form-grid" style="margin-top: 10px">
              <Select
                v-model="param.type"
                :options="PARAM_TYPES"
                label="Type"
                size="sm" />
              <Select
                v-if="param.type === 'ARRAY'"
                v-model="param.arrayType"
                :options="PARAM_TYPES"
                label="Array Type"
                size="sm" />
            </div>
            <div style="margin-top: 10px">
              <div class="field-label">Default Value</div>
              <DateParameterInput
                v-if="param.type === 'DATE' || param.type === 'DATETIME'"
                v-model="param.defaultValue"
                :type="param.type"
              />
              <div v-else-if="param.type === 'BOOLEAN'" style="display: flex; align-items: center; gap: 8px; margin-top: 4px">
                <Checkbox v-model="param.defaultValue" />
                <span style="font-size: 12px; color: var(--fg-2)">{{ param.defaultValue ? 'true' : 'false' }}</span>
              </div>
              <TextInput
                v-else-if="param.type === 'INTEGER' || param.type === 'FLOAT'"
                v-model="param.defaultValue"
                size="sm"
                type="number" />
              <TextInput
                v-else
                v-model="param.defaultValue"
                size="sm"
                placeholder="Optional" />
            </div>
            <div style="margin-top: 10px; display: flex; align-items: center; gap: 8px">
              <Checkbox v-model="param.required" />
              <span style="font-size: 12px; color: var(--fg-2)">Required</span>
            </div>
          </div>
        </div>
        <div v-else-if="!paramsCollapsed" class="collapsible-body card-body" style="color: var(--fg-3); font-size: 13px">No parameters defined.</div>
      </div>

      <!-- A failed execution leaves `results` null and reports through
           `executeError`, so the card must render for either state — gating
           on results alone silently swallows execution failures. -->
      <SectionCard v-if="results !== null || executeError" title="Results">
        <template v-if="resultsMeta?.cached" #right>
          <div class="results-meta">
            <Badge :color="resultsMeta.stale ? '#ffb547' : '#5ec5ff'">
              {{ resultsMeta.stale ? 'Stale' : 'Cached' }}
            </Badge>
            <span
              v-if="resultsMeta.refreshedAt"
              class="results-meta__time"
              :class="{ 'results-meta__time--stale': resultsMeta.stale }">
              {{ resultsMeta.stale ? 'last refreshed' : 'fresh as of' }} {{ new Date(resultsMeta.refreshedAt).toLocaleString() }}
            </span>
          </div>
        </template>
        <div class="card-body">
          <div v-if="executeError" class="error-msg">{{ executeError }}</div>
          <div v-else class="results-table">
            <div class="results-header" :style="{ gridTemplateColumns: resultColumns.map(() => '1fr').join(' ') }">
              <span v-for="col in resultColumns" :key="col.key">{{ col.label }}</span>
            </div>
            <div
              v-for="(row, i) in results"
              :key="i"
              class="results-row"
              :style="{ gridTemplateColumns: resultColumns.map(() => '1fr').join(' ') }">
              <span v-for="col in resultColumns" :key="col.key" class="mono">{{ row[col.key] }}</span>
            </div>
          </div>
          <Button
            v-if="results?.length"
            size="sm"
            style="margin-top: 10px"
            @click="results = null">Clear</Button>
        </div>
      </SectionCard>

      <div class="collapsible-section">
        <button class="collapsible-header" @click="showConfig = !showConfig">
          <Icon :name="showConfig ? 'chevronDown' : 'chevron'" :size="12" color="var(--fg-3)" />
          <span>Configuration (JSON)</span>
        </button>
        <div v-if="showConfig" class="collapsible-body">
          <ClientOnly>
            <JsonEditorVue
              v-model="configuration"
              :main-menu-bar="false"
              :navigation-bar="false"
              class="jse-theme-dark json-editor"
            />
          </ClientOnly>
        </div>
      </div>

    </div>

    <Modal
      v-if="showPermissionsModal"
      title="Permissions"
      icon="lock"
      :accent="accent"
      width="680px"
      @close="showPermissionsModal = false">
      <div class="perm-modal-toolbar">
        <span class="perm-modal-count">{{ permissions.length }} permission{{ permissions.length === 1 ? '' : 's' }}</span>
        <Button
          size="sm"
          icon="plus"
          :accent="accent"
          @click="openAddPermission">Add</Button>
      </div>
      <GlassTable
        :columns="permissionColumns"
        :rows="permissions"
        empty-text="No group permissions. Admins and SA retain implicit access."
        :row-actions="() => [
          { id: 'delete', label: 'Remove', icon: 'x', danger: true },
        ]"
        @row-action="({ action, row }: { action: string; row: QueryPermission }) => action === 'delete' ? permDeleteTarget = row : null">
        <template #col-group="{ row }: { row: QueryPermission }">
          <span class="perm-group">{{ row.group?.name ?? row.groupId }}</span>
        </template>
        <template #col-action="{ row }: { row: QueryPermission }">
          <Badge :color="PERMISSION_ACTION_COLORS[row.action] ?? 'var(--fg-3)'">
            {{ row.action }}
          </Badge>
        </template>
      </GlassTable>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showPermissionsModal = false">Close</Button>
      </template>
    </Modal>

    <Modal
      v-if="permAddOpen"
      title="Add Permission"
      icon="plus"
      :accent="accent"
      @close="permAddOpen = false">
      <div class="perm-add-stack">
        <Select
          v-model="permAddGroupId"
          label="Group"
          :options="groupOptions"
          searchable
          placeholder="Select a group…"
          :accent="accent" />
        <Select
          v-model="permAddAction"
          label="Action"
          :options="ANALYTICS_QUERY_PERMISSION_ACTIONS"
          :accent="accent" />
      </div>
      <template #footer>
        <span class="perm-add-spacer" />
        <Button size="sm" @click="permAddOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!permAddGroupId || !permAddAction || permAdding"
          @click="confirmAddPermission">{{ permAdding ? 'Adding…' : 'Add' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="permDeleteTarget"
      :title="`Remove ${permDeleteTarget.action} from ${permDeleteTarget.group?.name ?? permDeleteTarget.groupId}?`"
      :loading="permDeleteLoading"
      @close="permDeleteTarget = null"
      @confirm="confirmRemovePermission" />

    <Modal
      v-if="showExecuteModal"
      title="Execute Query"
      icon="pulse"
      :accent="accent"
      width="500px"
      @close="showExecuteModal = false">
      <div class="execute-params">
        <div v-for="(param, index) in executeParams" :key="index" class="execute-param-card">
          <div class="execute-param-label">{{ param.name }} <span class="execute-param-type">{{ param.type }}</span></div>
          <DateParameterInput
            v-if="param.type === 'DATE' || param.type === 'DATETIME'"
            v-model="param.value"
            :type="param.type"
          />
          <div v-else-if="param.type === 'BOOLEAN'" style="display: flex; align-items: center; gap: 8px; margin-top: 4px">
            <Checkbox v-model="param.value" />
            <span style="font-size: 12px; color: var(--fg-2)">{{ param.value ? 'true' : 'false' }}</span>
          </div>
          <TextInput
            v-else-if="param.type === 'INTEGER' || param.type === 'FLOAT'"
            v-model="param.value"
            size="sm"
            type="number" />
          <TextInput v-else v-model="param.value" size="sm" />
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showExecuteModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          icon="pulse"
          @click="executeFromModal">Execute</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 1000px; }
.card-body { padding: 14px 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.error-msg { font-size: 13px; color: var(--err); }
.results-table { display: flex; flex-direction: column; max-height: 400px; overflow: auto; }
.results-header, .results-row { display: grid; gap: 4px; padding: 8px 0; }
.results-header { font-size: 10.5px; color: var(--fg-3); font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent); position: sticky; top: 0; background: var(--bg-1); }
.results-row { font-size: 12.5px; border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent); }
.results-row:last-child { border-bottom: none; }

.git-attached {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: color-mix(in oklch, var(--brand-2) 8%, var(--bg-3));
  border: 1px solid color-mix(in oklch, var(--brand-2) 20%, var(--line));
  border-radius: var(--r-sm);
}

.git-attached__path {
  flex: 1;
  font-size: 12px;
  color: var(--fg-0);
}

.git-attached__ref {
  font-size: 11px;
  color: var(--fg-3);
}

.sync-status {
  margin-top: 8px;
  font-size: 12px;
  color: var(--fg-3);
}

.cache-hint {
  margin-top: 6px;
  font-size: 11px;
  color: var(--fg-3);
}

.results-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.results-meta__time {
  font-size: 11px;
  color: var(--fg-3);
}

.results-meta__time--stale {
  color: #ffb547;
}

.source-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--fg-3);
  font-style: italic;
}

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
  margin-bottom: 6px;
}

.param-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
}

.param-card + .param-card {
  margin-top: 10px;
}

.collapsible-header-row {
  display: flex;
  align-items: stretch;
}

.collapsible-header-row .collapsible-header {
  flex: 1;
  padding-right: 8px;
}

.collapsible-actions {
  display: flex;
  align-items: center;
  padding: 0 12px 0 4px;
}

.collapsible-count {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  margin-left: 4px;
}

.param-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.param-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.param-remove {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 16px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
}

.param-remove:hover {
  color: var(--err);
}

.collapsible-section {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.collapsible-header {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 12px 16px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.collapsible-header:hover {
  background: var(--bg-2);
}

.collapsible-body {
  border-top: 1px solid var(--line);
}

.json-editor {
  min-height: 200px;
  border: none;
  border-radius: 0;
}

.execute-params {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.execute-param-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
}

.execute-param-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
  margin-bottom: 10px;
}

.execute-param-type {
  font-size: 11px;
  font-weight: 400;
  color: var(--fg-3);
  margin-left: 6px;
}

.perm-group {
  font-weight: 500;
  color: var(--fg-0);
}

.perm-modal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.perm-modal-count {
  font-size: 12px;
  color: var(--fg-3);
}

.perm-add-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.perm-add-spacer {
  flex: 1;
}
</style>
