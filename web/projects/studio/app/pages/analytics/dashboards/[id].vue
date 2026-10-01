<script setup lang="ts">
import gql from 'graphql-tag'
import {
  DashboardGrid,
  AnalyticsVisualization,
  type GridItem,
  type VisualizationType,
  type QueryExecutionParameter,
} from '@bosca/ui-analytics'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const dashboardId = computed(() => route.params.id as string)
const isNew = computed(() => dashboardId.value === 'new')

const dashboardGql = gql`
  query GetDashboard($id: UUID!) {
    analytics {
      dashboards {
        byId(id: $id) {
          id key name description configuration
          visualizations {
            id
            configuration
            visualization { id key name type queryId configuration }
          }
          parameters { parameter name description type arrayType defaultValue required }
          permissions { action groupId group { id name } }
        }
      }
    }
  }
`

const groupsGql = gql`
  query AnalyticsDashboardGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
`
const addPermissionGql = gql`
  mutation AddAnalyticsDashboardPermission($permission: PermissionInput!) {
    analytics { dashboards { addPermission(permission: $permission) { action groupId } } }
  }
`
const removePermissionGql = gql`
  mutation RemoveAnalyticsDashboardPermission($permission: PermissionInput!) {
    analytics { dashboards { deletePermission(permission: $permission) { action groupId } } }
  }
`

const allVisualizationsGql = gql`
  query GetAllVisualizations {
    analytics { visualizations { all { id key name type queryId configuration } } }
  }
`

const executeGql = gql`
  query ExecuteQuery($queryId: UUID!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
    analytics { queries { execute(queryId: $queryId, parameters: $parameters) { records cached stale refreshedAt } } }
  }
`

const queryByIdGql = gql`
  query GetQueryParams($id: UUID!) {
    analytics { queries { queryById(id: $id) { parameters { parameter } } } }
  }
`

const addGql = gql`
  mutation AddDashboard($dashboard: AnalyticsDashboardInput!) {
    analytics { dashboards { add(dashboard: $dashboard) { id } } }
  }
`

const editGql = gql`
  mutation EditDashboard($dashboard: AnalyticsDashboardInput!) {
    analytics { dashboards { edit(dashboard: $dashboard) { id } } }
  }
`

interface DashboardPermission {
  action: string
  groupId: string
  group: { id: string; name: string } | null
}

interface DashboardData {
  id: string; key: string; name: string; description: string | null; configuration: Record<string, unknown>
  visualizations: Array<{ id: string; configuration: Record<string, unknown>; visualization: { id: string; key: string; name: string; type: string; queryId: string | null; configuration: Record<string, unknown> } }>
  parameters: DashboardParam[]
  permissions: DashboardPermission[]
}

interface VizSummary { id: string; key: string; name: string; type: string; queryId: string | null; configuration: Record<string, unknown> }

// Use a computed so the query re-fires when dashboardId changes (e.g. after
// router.replace following a successful create). Returning undefined when
// isNew is true causes useGraphQL to skip the fetch entirely.
const dashboardDetailId = computed<string | undefined>(() => isNew.value ? undefined : dashboardId.value)
const { data, refresh } = useAsyncQuery<{ analytics: { dashboards: { byId: DashboardData | null } } }>(
  'dashboard-detail', dashboardGql, { id: dashboardDetailId },
)

const { data: vizData } = useAsyncQuery<{ analytics: { visualizations: { all: VizSummary[] } } }>(
  'all-visualizations', allVisualizationsGql, {},
)

const dashboard = computed(() => isNew.value ? null : data.value?.analytics?.dashboards?.byId ?? null)
const availableVisualizations = computed(() => vizData.value?.analytics?.visualizations?.all ?? [])

const key = ref('')
const name = ref('')
const description = ref('')
const saving = ref(false)
const layoutExpanded = ref(false)
const configuringVizId = ref<string | null>(null)
const configuringViz = computed(() =>
  configuringVizId.value ? currentVisualizations.value.find(v => v.uniqueId === configuringVizId.value) ?? null : null
)

interface VizDisplayConfig {
  showTitle: boolean
  showBorder: boolean
  showBackground: boolean
  boldTitle: boolean
  titleOverride: string
  titleSize: 'sm' | 'md' | 'lg'
  locked: boolean
}

interface DashboardViz {
  uniqueId: string
  visualization: VizSummary
  x: number
  y: number
  w: number
  h: number
  display: VizDisplayConfig
}

const currentVisualizations = ref<DashboardViz[]>([])

// Per-visualization query data
const vizQueryData = ref<Record<string, { data: Record<string, unknown>[]; loading: boolean; error: string; cached: boolean; stale: boolean; refreshedAt: string | null }>>({})

// Execution parameters — initialized from dashboard defaults, updated by DatePicker visualizations
const executionParameters = ref<{ parameter: string; value: unknown }[]>([])

// Cache of which parameters each query accepts: queryId -> Set of parameter names
const queryParamCache = ref<Record<string, Set<string>>>({})

async function fetchQueryParams(queryId: string): Promise<Set<string>> {
  if (queryParamCache.value[queryId]) return queryParamCache.value[queryId]
  try {
    const result = await gqlQuery<{ analytics: { queries: { queryById: { parameters: Array<{ parameter: string }> } | null } } }>(queryByIdGql, { id: queryId })
    const params = (result.analytics?.queries?.queryById?.parameters ?? []).map((p) => p.parameter)
    const paramSet = new Set<string>(params)
    queryParamCache.value[queryId] = paramSet
    return paramSet
  } catch {
    return new Set()
  }
}

function parametersForQuery(acceptedParams: Set<string>): { parameter: string; value: unknown }[] {
  if (acceptedParams.size === 0) return []
  return executionParameters.value.filter(p => acceptedParams.has(p.parameter))
}

async function loadVizData(viz: DashboardViz) {
  if (!viz.visualization.queryId) return
  const key = viz.uniqueId
  vizQueryData.value[key] = { data: [], loading: true, error: '', cached: false, stale: false, refreshedAt: null }
  try {
    const accepted = await fetchQueryParams(viz.visualization.queryId)
    const params = parametersForQuery(accepted)
    const result = await gqlQuery<{ analytics: { queries: { execute: { records: Record<string, unknown>[]; cached: boolean; stale: boolean; refreshedAt: string | null } } } }>(executeGql, { queryId: viz.visualization.queryId, parameters: params })
    const execution = result.analytics.queries.execute
    vizQueryData.value[key] = { data: execution.records ?? [], loading: false, error: '', cached: execution.cached ?? false, stale: execution.stale ?? false, refreshedAt: execution.refreshedAt ?? null }
  } catch (e: unknown) {
    vizQueryData.value[key] = { data: [], loading: false, error: e instanceof Error ? e.message : 'Query failed', cached: false, stale: false, refreshedAt: null }
  }
}

function loadAllVizData() {
  for (const viz of currentVisualizations.value) {
    loadVizData(viz)
  }
}

function onVizParamChange(params: QueryExecutionParameter[]) {
  for (const p of params) {
    const idx = executionParameters.value.findIndex(o => o.parameter === p.parameter)
    if (idx >= 0) executionParameters.value[idx] = p
    else executionParameters.value.push(p)
  }
  loadAllVizData()
}

interface DashboardParam {
  parameter: string
  name: string
  description: string
  type: string
  arrayType: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- dynamic value driven by param type
  defaultValue: any
  required: boolean
}

const parameters = ref<DashboardParam[]>([])

const ANALYTICS_DASHBOARD_PERMISSION_ACTIONS: SelectOption[] = [
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
const permDeleteTarget = ref<DashboardPermission | null>(null)
const permDeleteLoading = ref(false)
const showPermissionsModal = ref(false)

const permissions = computed<DashboardPermission[]>(() => dashboard.value?.permissions ?? [])
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
  if (!dashboardId.value || !permAddGroupId.value || !permAddAction.value) return
  permAdding.value = true
  try {
    await gqlMutation(addPermissionGql, {
      permission: {
        action: permAddAction.value,
        entityId: dashboardId.value,
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
  if (!p || !dashboardId.value) return
  permDeleteLoading.value = true
  try {
    await gqlMutation(removePermissionGql, {
      permission: { action: p.action, entityId: dashboardId.value, groupId: p.groupId },
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

watch(dashboard, (d) => {
  if (d) {
    key.value = d.key
    name.value = d.name
    description.value = d.description ?? ''
    parameters.value = (d.parameters ?? []).map((p: DashboardParam) => ({
      parameter: p.parameter,
      name: p.name,
      description: p.description ?? '',
      type: p.type,
      arrayType: p.arrayType ?? 'NONE',
      defaultValue: p.defaultValue,
      required: p.required ?? false,
    }))
    executionParameters.value = parameters.value
      .filter(p => p.defaultValue != null)
      .map(p => ({ parameter: p.parameter, value: p.defaultValue }))
    currentVisualizations.value = (d.visualizations ?? []).map((v: DashboardData['visualizations'][number]) => {
      const cfg = v.configuration || {}
      return {
        uniqueId: v.id,
        visualization: v.visualization,
        x: (cfg.x as number) ?? 0,
        y: (cfg.y as number) ?? 0,
        w: (cfg.w as number) ?? 24,
        h: (cfg.h as number) ?? 12,
        display: {
          showTitle: (cfg.showTitle as boolean) ?? true,
          showBorder: (cfg.showBorder as boolean) ?? true,
          showBackground: (cfg.showBackground as boolean) ?? true,
          boldTitle: (cfg.boldTitle as boolean) ?? false,
          titleOverride: (cfg.titleOverride as string) ?? '',
          titleSize: (cfg.titleSize as 'sm' | 'md' | 'lg') ?? 'sm',
          locked: (cfg.locked as boolean) ?? false,
        },
      }
    })
    loadAllVizData()
  }
}, { immediate: true })

// Add visualization
const selectedVizId = ref('')
const vizOptions = computed(() => availableVisualizations.value.map((v) => ({ value: v.id, label: `${v.name} (${v.type})` })))

function addVisualization() {
  if (!selectedVizId.value) return
  const viz = availableVisualizations.value.find((v) => v.id === selectedVizId.value)
  if (!viz) return
  const nextY = currentVisualizations.value.length
    ? Math.max(...currentVisualizations.value.map(v => v.y + v.h))
    : 0
  const newViz: DashboardViz = {
    uniqueId: crypto.randomUUID(),
    visualization: viz,
    x: 0,
    y: nextY,
    w: 24,
    h: 12,
    display: { showTitle: true, showBorder: true, showBackground: true, boldTitle: false, titleOverride: '', titleSize: 'sm', locked: false },
  }
  currentVisualizations.value.push(newViz)
  loadVizData(newViz)
  selectedVizId.value = ''
}

function removeVisualization(uniqueId: string) {
  currentVisualizations.value = currentVisualizations.value.filter(v => v.uniqueId !== uniqueId)
  Reflect.deleteProperty(vizQueryData.value, uniqueId)
}

// Grid layout bridge
const gridLayout = computed<GridItem[]>(() =>
  currentVisualizations.value.map(v => ({ id: v.uniqueId, x: v.x, y: v.y, w: v.w, h: v.h }))
)

function onGridLayoutUpdate(newLayout: GridItem[]) {
  for (const item of newLayout) {
    const viz = currentVisualizations.value.find(v => v.uniqueId === item.id)
    if (viz) {
      viz.x = item.x
      viz.y = item.y
      viz.w = item.w
      viz.h = item.h
    }
  }
}

function vizForGridItem(item: GridItem) {
  return currentVisualizations.value.find(v => v.uniqueId === item.id)
}

const lockedVizIds = computed(() =>
  currentVisualizations.value.filter(v => v.display.locked).map(v => v.uniqueId)
)

// Parameters
const PARAM_TYPES = ['STRING', 'INTEGER', 'FLOAT', 'BOOLEAN', 'DATE', 'DATETIME', 'TIME', 'ARRAY', 'OBJECT', 'NONE'].map(t => ({ value: t, label: t }))
const showParamsModal = ref(false)

function addParameter() {
  parameters.value.push({ parameter: '', name: '', description: '', type: 'STRING', arrayType: 'NONE', defaultValue: null, required: false })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

// Save
async function onSave() {
  if (!key.value.trim() || !name.value.trim()) {
    toast.error('Key and name are required')
    return
  }
  saving.value = true
  try {
    const visualizationsInput = currentVisualizations.value
      .filter(v => v.visualization?.id)
      .map(v => ({
        visualizationId: v.visualization.id,
        configuration: { x: v.x, y: v.y, w: v.w, h: v.h, ...v.display },
      }))
    const input = {
      key: key.value,
      name: name.value,
      description: description.value || '',
      configuration: dashboard.value?.configuration ?? {},
      visualizations: visualizationsInput,
    } as Record<string, unknown>
    if (parameters.value.length > 0) {
      input.parameters = parameters.value
    }
    if (isNew.value) {
      const result = await gqlMutation<{ analytics: { dashboards: { add: { id: string } } } }>(addGql, { dashboard: input })
      toast.success('Dashboard created')
      router.replace(`/analytics/dashboards/${result.analytics.dashboards.add.id}`)
    } else {
      await gqlMutation(editGql, { dashboard: { ...input, id: dashboardId.value } })
      toast.success('Dashboard saved')
      refresh()
    }
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string } | undefined
    const msg = err?.errors?.[0]?.message ?? err?.message ?? 'Failed to save'
    toast.error(msg)
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
        :breadcrumb="buildBreadcrumb('Analytics', 'Dashboards', isNew ? 'New' : name || '…')"
        :title="isNew ? 'New Dashboard' : name || 'Loading…'"
      >
        <template #actions>
          <Button size="sm" icon="gear" @click="showParamsModal = true">Parameters</Button>
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

    <div class="editor-layout" :class="{ 'editor-layout--expanded': layoutExpanded }">
      <div class="main-content">
        <SectionCard v-if="!layoutExpanded" title="Configuration">
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
          </div>
        </SectionCard>

        <SectionCard title="Layout" class="layout-card">
          <template #right>
            <div class="add-viz-row">
              <Select
                v-model="selectedVizId"
                :options="vizOptions"
                placeholder="Select…"
                size="sm"
                searchable />
              <Button
                size="sm"
                icon="plus"
                :disabled="!selectedVizId"
                @click="addVisualization">Add</Button>
              <Button
                size="sm"
                icon="arrowUpRight"
                :class="{ 'collapse-btn': layoutExpanded }"
                @click="layoutExpanded = !layoutExpanded">
                {{ layoutExpanded ? 'Collapse' : 'Expand' }}
              </Button>
            </div>
          </template>

          <div v-if="currentVisualizations.length" class="grid-area">
            <DashboardGrid
              :layout="gridLayout"
              :columns="48"
              :cell-height="20"
              :gap="4"
              :locked-ids="lockedVizIds"
              @update:layout="onGridLayoutUpdate"
            >
              <template #item="{ item }">
                <div class="grid-viz-item" :class="{ 'grid-viz-item--no-border': !vizForGridItem(item)?.display.showBorder, 'grid-viz-item--no-bg': !vizForGridItem(item)?.display.showBackground }">
                  <div class="grid-viz-actions">
                    <button class="grid-viz-action" :title="vizForGridItem(item)?.display.locked ? 'Unlock' : 'Lock'" @click.stop="vizForGridItem(item)!.display.locked = !vizForGridItem(item)!.display.locked">
                      <Icon :name="vizForGridItem(item)?.display.locked ? 'lock' : 'unlock'" :size="12" />
                    </button>
                    <button class="grid-viz-action" title="Configure" @click.stop="configuringVizId = item.id">
                      <Icon name="gear" :size="12" />
                    </button>
                    <button class="grid-viz-action grid-viz-action--danger" title="Remove" @click.stop="removeVisualization(item.id)">
                      <Icon name="x" :size="12" />
                    </button>
                  </div>
                  <div v-if="vizForGridItem(item)?.display.showTitle" class="grid-viz-title" :class="[`grid-viz-title--${vizForGridItem(item)?.display.titleSize}`, { 'grid-viz-title--bold': vizForGridItem(item)?.display.boldTitle }]">
                    {{ vizForGridItem(item)?.display.titleOverride || vizForGridItem(item)?.visualization.name }}
                  </div>
                  <div class="grid-viz-chart">
                    <!-- LIVE_SESSIONS_MAP has no query — it opens the liveSessions subscription itself. -->
                    <LiveSessionsVisualization
                      v-if="vizForGridItem(item)?.visualization.type === 'LIVE_SESSIONS_MAP'"
                      :name="(vizForGridItem(item)?.display.titleOverride || vizForGridItem(item)?.visualization.name) ?? ''"
                      :configuration="vizForGridItem(item)?.visualization.configuration"
                      frameless
                    />
                    <AnalyticsVisualization
                      v-else
                      :name="(vizForGridItem(item)?.display.titleOverride || vizForGridItem(item)?.visualization.name) ?? ''"
                      :type="(vizForGridItem(item)?.visualization.type as VisualizationType) ?? 'BAR'"
                      :configuration="vizForGridItem(item)?.visualization.configuration"
                      :data="vizQueryData[item.id]?.data"
                      :loading="vizQueryData[item.id]?.loading ?? false"
                      :error="vizQueryData[item.id]?.error"
                      :parameters="executionParameters"
                      frameless
                      @param-change="onVizParamChange"
                    />
                  </div>
                  <div
                    v-if="vizQueryData[item.id]?.cached && vizQueryData[item.id]?.refreshedAt"
                    class="grid-viz-asof"
                    :class="{ 'grid-viz-asof--stale': vizQueryData[item.id]?.stale }"
                    :title="vizQueryData[item.id]?.stale
                      ? `Stale cached result, last refreshed ${new Date(vizQueryData[item.id]!.refreshedAt!).toLocaleString()}`
                      : `Fresh cached result as of ${new Date(vizQueryData[item.id]!.refreshedAt!).toLocaleString()}`">
                    {{ vizQueryData[item.id]?.stale ? 'Stale · last refreshed' : 'Fresh as of' }}
                    {{ new Date(vizQueryData[item.id]!.refreshedAt!).toLocaleString() }}
                  </div>
                </div>
              </template>
            </DashboardGrid>
          </div>
          <div v-else class="empty-state">No visualizations added yet. Select one above to add it.</div>
        </SectionCard>
      </div>

      <div v-if="!layoutExpanded" class="sidebar">
        <SectionCard title="Parameters">
          <div class="card-body">
            <div v-if="parameters.length" class="param-list">
              <div v-for="p in parameters" :key="p.parameter" class="param-row">
                <span class="mono" style="font-size: 12px; color: var(--fg-1)">{{ p.parameter || p.name || '…' }}</span>
                <span style="font-size: 11px; color: var(--fg-3)">{{ p.type }}</span>
              </div>
            </div>
            <div v-else class="empty-state">No parameters defined.</div>
          </div>
        </SectionCard>

        <SectionCard title="Visualizations">
          <div class="card-body">
            <div class="param-list">
              <div class="param-row" style="color: var(--fg-3); font-size: 12px">
                <span>{{ currentVisualizations.length }} visualization{{ currentVisualizations.length !== 1 ? 's' : '' }}</span>
              </div>
            </div>
          </div>
        </SectionCard>
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
        @row-action="({ action, row }: { action: string; row: DashboardPermission }) => action === 'delete' ? permDeleteTarget = row : null">
        <template #col-group="{ row }: { row: DashboardPermission }">
          <span class="perm-group">{{ row.group?.name ?? row.groupId }}</span>
        </template>
        <template #col-action="{ row }: { row: DashboardPermission }">
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
          :options="ANALYTICS_DASHBOARD_PERMISSION_ACTIONS"
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

    <!-- Parameters Modal -->
    <Modal
      v-if="showParamsModal"
      title="Dashboard Parameters"
      icon="gear"
      :accent="accent"
      width="700px"
      @close="showParamsModal = false">
      <div class="params-editor">
        <div v-if="parameters.length === 0" class="empty-state">No parameters. Click below to add one.</div>
        <div v-for="(param, index) in parameters" :key="index" class="param-card">
          <div class="param-card-header">
            <span class="param-card-title">{{ param.name || 'New Parameter' }}</span>
            <button class="viz-action viz-action-danger" @click="removeParameter(index)">×</button>
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
      <template #footer>
        <Button size="sm" icon="plus" @click="addParameter">Add Parameter</Button>
        <span style="flex: 1" />
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="showParamsModal = false">Done</Button>
      </template>
    </Modal>

    <!-- Item Configuration Modal -->
    <Modal
      v-if="configuringViz"
      :title="`Configure: ${configuringViz.visualization.name}`"
      icon="gear"
      :accent="accent"
      width="420px"
      @close="configuringVizId = null">
      <div class="config-editor">
        <TextInput
          v-model="configuringViz.display.titleOverride"
          label="Title Override"
          size="sm"
          :placeholder="configuringViz.visualization.name" />
        <Select
          v-model="configuringViz.display.titleSize"
          :options="[{ value: 'sm', label: 'Small' }, { value: 'md', label: 'Medium' }, { value: 'lg', label: 'Large' }]"
          label="Title Size"
          size="sm" />
        <div class="config-toggles">
          <label class="config-toggle">
            <Checkbox v-model="configuringViz.display.showTitle" />
            <span>Show title</span>
          </label>
          <label class="config-toggle">
            <Checkbox v-model="configuringViz.display.boldTitle" />
            <span>Bold title</span>
          </label>
          <label class="config-toggle">
            <Checkbox v-model="configuringViz.display.showBorder" />
            <span>Show border</span>
          </label>
          <label class="config-toggle">
            <Checkbox v-model="configuringViz.display.showBackground" />
            <span>Show background</span>
          </label>
          <label class="config-toggle">
            <Checkbox v-model="configuringViz.display.locked" />
            <span>Lock position</span>
          </label>
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="configuringVizId = null">Done</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.editor-layout {
  display: grid;
  grid-template-columns: 1fr 260px;
  gap: 18px;
  height: 100%;
}

.editor-layout--expanded {
  grid-template-columns: 1fr;
}

.main-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.layout-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.layout-card :deep(.section-header) {
  flex-shrink: 0;
}

.collapse-btn :deep(svg) {
  transform: rotate(180deg);
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

.add-viz-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.grid-area {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
}

.grid-viz-item {
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

:deep(.dashboard-grid__item:has(.grid-viz-item--no-border)) {
  border-color: transparent;
  box-shadow: none;
}

:deep(.dashboard-grid__item:has(.grid-viz-item--no-bg)) {
  background: transparent;
}

.grid-viz-title {
  padding: 6px 10px 2px;
  font-weight: 500;
  color: var(--fg-2);
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grid-viz-title--sm {
  font-size: 11px;
}

.grid-viz-title--md {
  font-size: 14px;
}

.grid-viz-title--lg {
  font-size: 18px;
}

.grid-viz-title--bold {
  font-weight: 600;
  color: var(--fg-0);
}

.grid-viz-chart {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.grid-viz-asof {
  position: absolute;
  bottom: 4px;
  right: 8px;
  z-index: 2;
  font-size: 9.5px;
  color: var(--fg-3);
  pointer-events: auto;
  opacity: 0.75;
}

.grid-viz-asof--stale {
  color: #ffb547;
  opacity: 1;
}

.grid-viz-actions {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 3;
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
}

.grid-viz-item:hover .grid-viz-actions {
  opacity: 1;
}

.grid-viz-action {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  color: var(--fg-2);
  background: var(--bg-2);
  border: 1px solid var(--line);
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.grid-viz-action:hover {
  color: var(--fg-0);
  border-color: var(--fg-3);
}

.grid-viz-action--danger:hover {
  color: var(--err);
  border-color: var(--err);
}

.config-editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.config-toggles {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.config-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
  cursor: pointer;
}

.empty-state {
  font-size: 13px;
  color: var(--fg-3);
  padding: 14px 16px;
}

.param-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.param-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.params-editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 60vh;
  overflow: auto;
}

.param-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
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

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
  margin-bottom: 6px;
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
