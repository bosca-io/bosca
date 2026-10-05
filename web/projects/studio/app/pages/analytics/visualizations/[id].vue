<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { BarLineScatterConfiguration, PieDoughnutConfiguration, NumberConfiguration, TableConfiguration, DatePickerConfiguration, TopoJsonMapConfiguration, GeoPointMapConfiguration, LiveSessionsMapConfiguration, AnalyticsVisualization, VisualizationType, VisualizationConfiguration } from '@bosca/ui-analytics'
import { createVisualization } from '@bosca/ui-analytics'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const vizId = computed(() => route.params.id as string)
const isNew = computed(() => vizId.value === 'new')

const vizGql = gql`
  query GetVisualization($id: UUID!) {
    analytics { visualizations { byId(id: $id) { id key name description type queryId configuration permissions { action groupId group { id name } } } } }
  }
`
const queriesGql = gql`
  query GetQueriesForDropdown {
    analytics { queries { all { id name } } }
  }
`
const executeGql = gql`
  query ExecuteQuery($queryId: UUID!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
    analytics { queries { execute(queryId: $queryId, parameters: $parameters) { records } } }
  }
`
const addGql = gql`
  mutation AddVisualization($visualization: AnalyticsVisualizationInput!) {
    analytics { visualizations { add(visualization: $visualization) { id } } }
  }
`
const editGql = gql`
  mutation EditVisualization($visualization: AnalyticsVisualizationInput!) {
    analytics { visualizations { edit(visualization: $visualization) { id } } }
  }
`
const groupsGql = gql`
  query AnalyticsVisualizationGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
`
const addPermissionGql = gql`
  mutation AddAnalyticsVisualizationPermission($permission: PermissionInput!) {
    analytics { visualizations { addPermission(permission: $permission) { action groupId } } }
  }
`
const removePermissionGql = gql`
  mutation RemoveAnalyticsVisualizationPermission($permission: PermissionInput!) {
    analytics { visualizations { deletePermission(permission: $permission) { action groupId } } }
  }
`

interface VizPermission {
  action: string
  groupId: string
  group: { id: string; name: string } | null
}

interface VizData {
  id: string; key: string; name: string; description: string | null; type: string; queryId: string | null; configuration: Record<string, unknown>
  permissions: VizPermission[]
}

// Use a computed so the query re-fires when vizId changes (e.g. after
// router.replace following a successful create). Returning undefined when
// isNew is true causes useGraphQL to skip the fetch entirely.
const vizDetailId = computed<string | undefined>(() => isNew.value ? undefined : vizId.value)
const { data, refresh } = useAsyncQuery<{ analytics: { visualizations: { byId: VizData | null } } }>(
  'viz-detail', vizGql, { id: vizDetailId },
)
const { data: queriesData } = useAsyncQuery<{ analytics: { queries: { all: Array<{ id: string; name: string }> } } }>('viz-queries', queriesGql, {})

const viz = computed(() => isNew.value ? null : data.value?.analytics?.visualizations?.byId)
const availableQueries = computed(() =>
  (queriesData.value?.analytics?.queries?.all ?? []).map((q) => ({ value: q.id, label: q.name }))
)

// A LIVE_SESSIONS_MAP streams by application id — chosen from the registered analytics applications
// (project_analytics_application), never free text. Deduped across projects, each labelled with its owner(s).
const appsGql = gql`
  query AnalyticsApplicationOptions {
    workOps { projects { all { key analyticsApplications { applicationId } } } }
  }
`
const { data: appsData } = useAsyncQuery<{ workOps: { projects: { all: Array<{ key: string; analyticsApplications: { applicationId: string }[] }> } } }>('viz-analytics-apps', appsGql, {})
// Sentinel for the unfiltered stream — the Select renders an empty-string value as "unset", so the
// all-applications choice needs a real value; it maps to an empty appId in the configuration.
const ALL_APPLICATIONS = '*'
const appIdOptions = computed(() => {
  const owners = new Map<string, string[]>()
  for (const p of appsData.value?.workOps?.projects?.all ?? []) {
    for (const a of p.analyticsApplications ?? []) {
      owners.set(a.applicationId, [...(owners.get(a.applicationId) ?? []), p.key])
    }
  }
  return [
    { value: ALL_APPLICATIONS, label: 'All applications' },
    ...Array.from(owners, ([appId, keys]) => ({ value: appId, label: `${appId} · ${keys.join(', ')}` })),
  ]
})

// Form state
const key = ref('')
const name = ref('')
const description = ref('')
const type = ref<VisualizationType>('BAR')
const queryId = ref('')
const saving = ref(false)

// Configuration profile (the typed config object)
const profile = ref<VisualizationConfiguration>(createVisualization('BAR'))
const configJson = ref<Record<string, unknown>>({})
const showJsonEditor = ref(true)

// Preview state
const previewData = ref<Record<string, unknown>[]>([])
const previewLoading = ref(false)
const previewError = ref('')
const previewRan = ref(false)

const TYPE_OPTIONS = [
  { value: 'BAR', label: 'Bar' },
  { value: 'LINE', label: 'Line' },
  { value: 'PIE', label: 'Pie' },
  { value: 'DOUGHNUT', label: 'Doughnut' },
  { value: 'SCATTER', label: 'Scatter' },
  { value: 'NUMBER', label: 'Number' },
  { value: 'TABLE', label: 'Table' },
  { value: 'LABEL', label: 'Label' },
  { value: 'DATEPICKER', label: 'Date Picker' },
  { value: 'TOPO_JSON_MAP', label: 'Map (Regions)' },
  { value: 'GEO_POINT_MAP', label: 'Map (Points)' },
  { value: 'LIVE_SESSIONS_MAP', label: 'Live Sessions Map' },
]

const FIELD_TYPE_OPTIONS = [
  { value: 'auto', label: 'Auto' },
  { value: 'string', label: 'String' },
  { value: 'number', label: 'Number' },
  { value: 'date', label: 'Date' },
]

// LIVE_SESSIONS_MAP is fed by a subscription, not a query — no query field, no preview-run flow.
const showQueryField = computed(() => !['LABEL', 'DATEPICKER', 'LIVE_SESSIONS_MAP'].includes(type.value))

const ANALYTICS_VISUALIZATION_PERMISSION_ACTIONS: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'DELETE', label: 'DELETE' },
]

const PERMISSION_ACTION_COLORS: Record<string, string> = {
  VIEW: '#5ec5ff',
  EDIT: '#4ade80',
  MANAGE: '#ff5d6c',
  DELETE: '#ffb547',
}

interface SecurityGroup { id: string; name: string; description: string | null }
const allGroups = ref<SecurityGroup[]>([])
const permAddOpen = ref(false)
const permAddGroupId = ref<string | undefined>(undefined)
const permAddAction = ref<string | undefined>('VIEW')
const permAdding = ref(false)
const permDeleteTarget = ref<VizPermission | null>(null)
const permDeleteLoading = ref(false)
const showPermissionsModal = ref(false)

const permissions = computed<VizPermission[]>(() => viz.value?.permissions ?? [])
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
  if (!vizId.value || !permAddGroupId.value || !permAddAction.value) return
  permAdding.value = true
  try {
    await gqlMutation(addPermissionGql, {
      permission: {
        action: permAddAction.value,
        entityId: vizId.value,
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
  if (!p || !vizId.value) return
  permDeleteLoading.value = true
  try {
    await gqlMutation(removePermissionGql, {
      permission: { action: p.action, entityId: vizId.value, groupId: p.groupId },
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

const availableKeys = computed(() => {
  if (previewData.value.length > 0) return Object.keys(previewData.value[0]!)
  return []
})

const keyOptions = computed(() => availableKeys.value.map(k => ({ value: k, label: k })))

// Typed accessors for configuration
const xAxisKey = computed({
  get: () => (profile.value as BarLineScatterConfiguration).xAxisKey ?? '',
  set: (v) => { (profile.value as BarLineScatterConfiguration).xAxisKey = v; syncConfigFromProfile() },
})
const yAxisKeys = computed({
  get: () => (profile.value as BarLineScatterConfiguration).yAxisKeys ?? [],
  set: (v) => { (profile.value as BarLineScatterConfiguration).yAxisKeys = v; syncConfigFromProfile() },
})
const labelKey = computed({
  get: () => (profile.value as PieDoughnutConfiguration).labelKey ?? '',
  set: (v) => { (profile.value as PieDoughnutConfiguration).labelKey = v; syncConfigFromProfile() },
})
const valueKey = computed({
  get: () => {
    if (type.value === 'NUMBER') return (profile.value as NumberConfiguration).valueKey ?? ''
    return (profile.value as PieDoughnutConfiguration).valueKey ?? ''
  },
  set: (v) => {
    if (type.value === 'NUMBER') (profile.value as NumberConfiguration).valueKey = v
    else (profile.value as PieDoughnutConfiguration).valueKey = v
    syncConfigFromProfile()
  },
})
const tableColumnKeys = computed({
  get: () => (profile.value as TableConfiguration).tableColumnKeys ?? [],
  set: (v) => { (profile.value as TableConfiguration).tableColumnKeys = v; syncConfigFromProfile() },
})
const regionColumn = computed({
  get: () => (profile.value as TopoJsonMapConfiguration).regionColumn ?? '',
  set: (v) => { (profile.value as TopoJsonMapConfiguration).regionColumn = v; syncConfigFromProfile() },
})
const mapValueColumn = computed({
  get: () => (profile.value as TopoJsonMapConfiguration).valueColumn ?? '',
  set: (v) => { (profile.value as TopoJsonMapConfiguration).valueColumn = v; syncConfigFromProfile() },
})
const geoLatitudeColumn = computed({
  get: () => (profile.value as GeoPointMapConfiguration).latitudeColumn ?? '',
  set: (v) => { (profile.value as GeoPointMapConfiguration).latitudeColumn = v; syncConfigFromProfile() },
})
const geoLongitudeColumn = computed({
  get: () => (profile.value as GeoPointMapConfiguration).longitudeColumn ?? '',
  set: (v) => { (profile.value as GeoPointMapConfiguration).longitudeColumn = v; syncConfigFromProfile() },
})
const geoIdColumn = computed({
  get: () => (profile.value as GeoPointMapConfiguration).idColumn ?? '',
  set: (v) => { (profile.value as GeoPointMapConfiguration).idColumn = v; syncConfigFromProfile() },
})
const geoSeriesColumn = computed({
  get: () => (profile.value as GeoPointMapConfiguration).seriesColumn ?? '',
  set: (v) => { (profile.value as GeoPointMapConfiguration).seriesColumn = v; syncConfigFromProfile() },
})
const liveAppId = computed({
  get: () => (profile.value as LiveSessionsMapConfiguration).appId || ALL_APPLICATIONS,
  set: (v) => { (profile.value as LiveSessionsMapConfiguration).appId = v === ALL_APPLICATIONS ? '' : v; syncConfigFromProfile() },
})
const liveAppVersion = computed({
  get: () => (profile.value as LiveSessionsMapConfiguration).appVersion ?? '',
  set: (v) => { (profile.value as LiveSessionsMapConfiguration).appVersion = v; syncConfigFromProfile() },
})
const datePickerStartParam = computed({
  get: () => (profile.value as DatePickerConfiguration).startDateParam ?? 'startDate',
  set: (v) => { (profile.value as DatePickerConfiguration).startDateParam = v; syncConfigFromProfile() },
})
const datePickerEndParam = computed({
  get: () => (profile.value as DatePickerConfiguration).endDateParam ?? 'endDate',
  set: (v) => { (profile.value as DatePickerConfiguration).endDateParam = v; syncConfigFromProfile() },
})

function syncConfigFromProfile() {
  const cfg = profile.value.getConfiguration()
  if (Object.keys(profile.value.fieldSettings).length > 0) {
    cfg.fields = profile.value.fieldSettings
  }
  configJson.value = cfg
}

function syncProfileFromConfig() {
  profile.value.loadConfiguration(configJson.value)
}

const previewConfig = computed(() => configJson.value)

// Watchers
watch(viz, (v) => {
  if (v) {
    key.value = v.key ?? ''
    name.value = v.name ?? ''
    description.value = v.description ?? ''
    type.value = (v.type ?? 'BAR') as VisualizationType
    queryId.value = v.queryId ?? ''
    const cfg = v.configuration ?? {}
    configJson.value = cfg
    profile.value = createVisualization(type.value, cfg)
  }
}, { immediate: true })

watch(type, (newType, oldType) => {
  if (newType !== oldType) {
    const cfg = profile.value.getConfiguration()
    profile.value = createVisualization(newType, cfg)
  }
})

// Execute query for preview
async function runPreview() {
  if (!queryId.value) return
  previewLoading.value = true
  previewError.value = ''
  try {
    const result = await gqlQuery<{ analytics: { queries: { execute: { records: Record<string, unknown>[] } } } }>(executeGql, { queryId: queryId.value, parameters: [] })
    previewData.value = result.analytics.queries.execute.records ?? []
    previewRan.value = true
    // Re-process through profile
    profile.value.setData(previewData.value)
  } catch (e: unknown) {
    previewError.value = e instanceof Error ? e.message : 'Query execution failed'
  } finally {
    previewLoading.value = false
  }
}

function updateFieldSetting(fieldKey: string, prop: string, value: string) {
  if (!profile.value.fieldSettings[fieldKey]) {
    profile.value.fieldSettings[fieldKey] = {}
  }
  ;(profile.value.fieldSettings[fieldKey] as Record<string, string>)[prop] = value
  syncConfigFromProfile()
}

// Save
async function onSave() {
  if (!key.value.trim() || !name.value.trim()) {
    toast.error('Key and name are required')
    return
  }
  saving.value = true
  try {
    const cfg = configJson.value

    const input: Record<string, unknown> = {
      key: key.value,
      name: name.value,
      description: description.value,
      type: type.value,
      queryId: queryId.value || null,
      configuration: cfg,
    }
    if (isNew.value) {
      const r = await gqlMutation<{ analytics: { visualizations: { add: { id: string } } } }>(addGql, { visualization: input })
      toast.success('Visualization created')
      router.replace(`/analytics/visualizations/${r.analytics.visualizations.add.id}`)
    } else {
      await gqlMutation(editGql, { visualization: { ...input, id: vizId.value } })
      toast.success('Saved')
      refresh()
    }
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string } | undefined
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to save')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Visualizations', isNew ? 'New' : name || '…')"
        :title="isNew ? 'New Visualization' : name || 'Loading…'"
      >
        <template #actions>
          <Button
            v-if="showQueryField"
            size="sm"
            icon="pulse"
            :disabled="!queryId || previewLoading"
            @click="runPreview">
            {{ previewLoading ? 'Running…' : 'Preview' }}
          </Button>
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
            :disabled="saving || !key.trim() || !name.trim()"
            @click="onSave">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="editor-layout">
      <div class="main-content">
        <!-- Basic config -->
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
            <TextInput v-model="description" label="Description" style="margin-top: 14px" />
            <div class="form-grid" style="margin-top: 14px">
              <Select v-model="type" :options="TYPE_OPTIONS" label="Type" />
              <Select
                v-if="showQueryField"
                v-model="queryId"
                :options="availableQueries"
                label="Query"
                placeholder="Select a query…"
                searchable />
            </div>
          </div>
        </SectionCard>

        <!-- Live sessions map: subscription-fed, so it configures an application (not a query) and
             previews live inline rather than through the query-run flow. -->
        <SectionCard v-if="type === 'LIVE_SESSIONS_MAP'" title="Live Sessions">
          <div class="card-body">
            <div class="form-grid">
              <Select
                v-model="liveAppId"
                :options="appIdOptions"
                label="Application"
                placeholder="Select an application…"
                searchable />
              <TextInput
                v-model="liveAppVersion"
                label="Version (optional)"
                placeholder="All versions" />
            </div>
            <p class="live-hint">
              Streams the <code>liveSessions</code> feed — no query needed. Sessions appear as they heartbeat and
              drop off after 15 minutes of inactivity. <strong>All applications</strong> streams every registered
              app on one map, colored by application; selecting one app colors its sessions by version.
            </p>
            <p v-if="appIdOptions.length === 1" class="live-hint">
              No analytics applications are registered yet — register one on a project's
              <em>Analytics Applications</em> (a <code>sessions.&lt;appId&gt;</code> identifier) to filter this
              map to a single app.
            </p>
            <div class="live-preview">
              <ClientOnly>
                <LiveSessionsVisualization
                  :name="name || 'Live Sessions'"
                  :configuration="configJson"
                  :height="360"
                  frameless />
              </ClientOnly>
            </div>
          </div>
        </SectionCard>

        <!-- Type-specific axis/field selectors (only after preview) -->
        <SectionCard v-if="previewRan && availableKeys.length" title="Data Mapping">
          <div class="card-body">
            <div class="form-grid">
              <template v-if="['BAR', 'LINE', 'SCATTER'].includes(type)">
                <Select
                  v-model="xAxisKey"
                  :options="keyOptions"
                  label="X Axis"
                  placeholder="Select…" />
                <Select
                  v-model="yAxisKeys"
                  :options="keyOptions"
                  label="Y Axis"
                  placeholder="Select…"
                  multiple />
              </template>
              <template v-else-if="['PIE', 'DOUGHNUT'].includes(type)">
                <Select
                  v-model="labelKey"
                  :options="keyOptions"
                  label="Label Field"
                  placeholder="Select…" />
                <Select
                  v-model="valueKey"
                  :options="keyOptions"
                  label="Value Field"
                  placeholder="Select…" />
              </template>
              <template v-else-if="type === 'NUMBER'">
                <Select
                  v-model="valueKey"
                  :options="keyOptions"
                  label="Value Field"
                  placeholder="Select…" />
              </template>
              <template v-else-if="type === 'TABLE'">
                <Select
                  v-model="tableColumnKeys"
                  :options="keyOptions"
                  label="Columns (empty = all)"
                  placeholder="Select columns…"
                  multiple />
              </template>
              <template v-else-if="type === 'TOPO_JSON_MAP'">
                <Select
                  v-model="regionColumn"
                  :options="keyOptions"
                  label="Region Column"
                  placeholder="Select…" />
                <Select
                  v-model="mapValueColumn"
                  :options="keyOptions"
                  label="Value Column"
                  placeholder="Select…" />
              </template>
              <template v-else-if="type === 'GEO_POINT_MAP'">
                <Select
                  v-model="geoLatitudeColumn"
                  :options="keyOptions"
                  label="Latitude Column"
                  placeholder="Select…" />
                <Select
                  v-model="geoLongitudeColumn"
                  :options="keyOptions"
                  label="Longitude Column"
                  placeholder="Select…" />
                <Select
                  v-model="geoIdColumn"
                  :options="keyOptions"
                  label="Point ID Column (optional)"
                  placeholder="Select…" />
                <Select
                  v-model="geoSeriesColumn"
                  :options="keyOptions"
                  label="Series / Color Column (optional)"
                  placeholder="Select…" />
              </template>
              <template v-else-if="type === 'DATEPICKER'">
                <TextInput v-model="datePickerStartParam" label="Start Date Parameter" />
                <TextInput v-model="datePickerEndParam" label="End Date Parameter" />
              </template>
            </div>
          </div>
        </SectionCard>

        <!-- Live Preview -->
        <SectionCard v-if="previewRan" title="Preview">
          <div class="card-body">
            <div v-if="previewError" class="preview-error">{{ previewError }}</div>
            <ClientOnly v-else>
              <AnalyticsVisualization
                :name="name || 'Preview'"
                :type="type"
                :configuration="previewConfig"
                :data="previewData"
                :height="250"
                frameless
              />
            </ClientOnly>
          </div>
        </SectionCard>

        <!-- Field Settings -->
        <SectionCard v-if="previewRan && availableKeys.length" title="Field Settings">
          <div class="card-body">
            <div class="field-settings">
              <div class="field-row field-row-header">
                <span>Field</span>
                <span>Label</span>
                <span>Format</span>
                <span>Type</span>
              </div>
              <div v-for="fieldKey in availableKeys" :key="fieldKey" class="field-row">
                <span class="mono field-key">{{ fieldKey }}</span>
                <TextInput
                  :model-value="profile.fieldSettings[fieldKey]?.label ?? ''"
                  size="sm"
                  placeholder="Label"
                  @update:model-value="(v: string) => updateFieldSetting(fieldKey, 'label', v)"
                />
                <TextInput
                  :model-value="profile.fieldSettings[fieldKey]?.format ?? ''"
                  size="sm"
                  placeholder="Format"
                  @update:model-value="(v: string) => updateFieldSetting(fieldKey, 'format', v)"
                />
                <Select
                  :model-value="profile.fieldSettings[fieldKey]?.type ?? 'auto'"
                  :options="FIELD_TYPE_OPTIONS"
                  size="sm"
                  @update:model-value="(v: string | string[] | null | undefined) => { if (typeof v === 'string') updateFieldSetting(fieldKey, 'type', v) }"
                />
              </div>
            </div>
          </div>
        </SectionCard>

        <!-- Raw JSON config (collapsible) -->
        <div class="collapsible-section">
          <button class="collapsible-header" @click="showJsonEditor = !showJsonEditor">
            <Icon :name="showJsonEditor ? 'chevronDown' : 'chevron'" :size="12" color="var(--fg-3)" />
            <span>Configuration (JSON)</span>
          </button>
          <div v-if="showJsonEditor" class="collapsible-body">
            <ClientOnly>
              <JsonEditorVue
                v-model="configJson"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="jse-theme-dark json-editor"
                @update:model-value="syncProfileFromConfig"
              />
            </ClientOnly>
          </div>
        </div>
      </div>

      <!-- Sidebar -->
      <div class="sidebar">
        <SectionCard title="Info">
          <div class="card-body sidebar-info">
            <div class="info-row"><span class="info-label">Type</span><Badge :color="accent">{{ type }}</Badge></div>
            <div v-if="queryId" class="info-row"><span class="info-label">Query</span><span class="mono info-value">{{ queryId.slice(0, 8) }}…</span></div>
            <div v-if="previewData.length" class="info-row"><span class="info-label">Records</span><span class="info-value">{{ previewData.length }}</span></div>
            <div v-if="availableKeys.length" class="info-row"><span class="info-label">Fields</span><span class="info-value">{{ availableKeys.length }}</span></div>
          </div>
        </SectionCard>

        <div v-if="!previewRan && showQueryField" class="sidebar-hint">
          Select a type and query, then click <strong>Preview</strong> to load data and configure the visualization.
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
        @row-action="({ action, row }: { action: string; row: VizPermission }) => action === 'delete' ? permDeleteTarget = row : null">
        <template #col-group="{ row }: { row: VizPermission }">
          <span class="perm-group">{{ row.group?.name ?? row.groupId }}</span>
        </template>
        <template #col-action="{ row }: { row: VizPermission }">
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
          :options="ANALYTICS_VISUALIZATION_PERMISSION_ACTIONS"
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
  </PageShell>
</template>

<style scoped>
.editor-layout {
  display: grid;
  grid-template-columns: 1fr 240px;
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

.card-body {
  padding: 14px 16px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.live-hint {
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.5;
  margin: 12px 0 0;
}

.live-hint code {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  color: var(--fg-2);
}

.live-preview {
  margin-top: 14px;
  min-height: 360px;
}

.preview-error {
  color: var(--err);
  font-size: 13px;
}

.preview-error {
  color: var(--err);
  font-size: 13px;
  padding: 12px 0;
}

.field-settings {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row {
  display: grid;
  grid-template-columns: 120px 1fr 1fr 100px;
  gap: 8px;
  align-items: center;
}

.field-row-header {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--line);
}

.field-key {
  font-size: 12px;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label {
  font-size: 12px;
  color: var(--fg-3);
}

.info-value {
  font-size: 12px;
  color: var(--fg-1);
}

.sidebar-hint {
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.5;
  padding: 12px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
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
