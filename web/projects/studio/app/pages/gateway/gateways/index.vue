<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()

type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

interface GatewayRow {
  id: string
  name: string
  url: string
  healthCheckPath: string | null
  healthCheckIntervalSecs: number
  connectTimeoutSecs: number
  requestTimeoutSecs: number
  poolMaxIdle: number
  poolIdleTimeoutSecs: number
  enabled: boolean
  healthStatus: HealthStatus
  healthStatusChangedAt: string
  healthStatusReason: string | null
  modifiedAt: string
  version: number
}

const activeTab = ref('Enabled')
const showCreate = ref(false)
const showEdit = ref(false)
const showDelete = ref(false)
const editTarget = ref<GatewayRow | null>(null)
const deleteTarget = ref<GatewayRow | null>(null)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')

const form = reactive({
  name: '',
  url: '',
  healthCheckPath: '',
  healthCheckIntervalSecs: 30,
  connectTimeoutSecs: 5,
  requestTimeoutSecs: 300,
  poolMaxIdle: 10,
  poolIdleTimeoutSecs: 90,
})

function resetForm() {
  form.name = ''
  form.url = ''
  form.healthCheckPath = ''
  form.healthCheckIntervalSecs = 30
  form.connectTimeoutSecs = 5
  form.requestTimeoutSecs = 300
  form.poolMaxIdle = 10
  form.poolIdleTimeoutSecs = 90
  error.value = ''
}

const listGql = gql`
  query {
    gateway {
      services {
        all {
          id
          name
          url
          healthCheckPath
          healthCheckIntervalSecs
          connectTimeoutSecs
          requestTimeoutSecs
          poolMaxIdle
          poolIdleTimeoutSecs
          enabled
          healthStatus
          healthStatusChangedAt
          healthStatusReason
          modifiedAt
          version
        }
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  gateway: { services: { all: GatewayRow[] } }
}>('gateway-services', listGql)

const allGateways = computed(() => data.value?.gateway?.services?.all ?? [])

const gateways = computed(() => {
  if (activeTab.value === 'Enabled') return allGateways.value.filter(g => g.enabled)
  if (activeTab.value === 'Disabled') return allGateways.value.filter(g => !g.enabled)
  return allGateways.value
})

const isLoading = computed(() => status.value === 'pending')

const subtitle = computed(() => {
  const enabled = allGateways.value.filter(g => g.enabled).length
  const disabled = allGateways.value.length - enabled
  return `${enabled} enabled · ${disabled} disabled`
})

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: '1fr' },
  { key: 'url', label: 'Upstream URL', width: '2fr' },
  { key: 'health', label: 'Health Check', width: '1fr' },
  { key: 'upstreamHealth', label: 'Upstream', width: '140px' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
]

/**
 * Maps the gateway's last-reported health to the badge color. We treat
 * UNKNOWN distinctly from DOWN so an operator can tell the difference
 * between "the proxy says it's broken" and "the proxy hasn't probed
 * yet (or no health_check_path is configured)".
 */
function healthBadgeColor(status: HealthStatus, accentColor: string): string {
  if (status === 'UP') return accentColor
  if (status === 'DOWN') return 'var(--err, #ff5c5c)'
  return '#888'
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

function openEdit(gateway: GatewayRow) {
  editTarget.value = gateway
  form.name = gateway.name
  form.url = gateway.url
  form.healthCheckPath = gateway.healthCheckPath ?? ''
  form.healthCheckIntervalSecs = gateway.healthCheckIntervalSecs
  form.connectTimeoutSecs = gateway.connectTimeoutSecs
  form.requestTimeoutSecs = gateway.requestTimeoutSecs
  form.poolMaxIdle = gateway.poolMaxIdle
  form.poolIdleTimeoutSecs = gateway.poolIdleTimeoutSecs
  error.value = ''
  showEdit.value = true
}

function openDelete(gateway: GatewayRow) {
  deleteTarget.value = gateway
  showDelete.value = true
}

const createGql = gql`
  mutation CreateGateway($input: GatewayInput!) {
    gateway { services { create(input: $input) { id } } }
  }
`

const updateGql = gql`
  mutation UpdateGateway($id: UUID!, $input: GatewayInput!, $expectedVersion: Long!) {
    gateway { services { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
  }
`

const toggleGql = gql`
  mutation ToggleGateway($id: UUID!, $enabled: Boolean!, $expectedVersion: Long!) {
    gateway { services { toggleEnabled(id: $id, enabled: $enabled, expectedVersion: $expectedVersion) { id } } }
  }
`

const deleteGql = gql`
  mutation DeleteGateway($id: UUID!, $expectedVersion: Long!) {
    gateway { services { delete(id: $id, expectedVersion: $expectedVersion) { id } } }
  }
`

function inputPayload() {
  return {
    name: form.name.trim(),
    url: form.url.trim(),
    healthCheckPath: form.healthCheckPath.trim() || null,
    healthCheckIntervalSecs: form.healthCheckIntervalSecs,
    connectTimeoutSecs: form.connectTimeoutSecs,
    requestTimeoutSecs: form.requestTimeoutSecs,
    poolMaxIdle: form.poolMaxIdle,
    poolIdleTimeoutSecs: form.poolIdleTimeoutSecs,
  }
}

function validateForm(): string | null {
  if (!form.name.trim()) return 'Name is required.'
  if (!form.url.trim()) return 'Upstream URL is required.'
  try {
    const u = new URL(form.url.trim())
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return 'Upstream URL must use http:// or https://.'
    }
  } catch {
    return 'Upstream URL is not a valid URL.'
  }
  return null
}

async function handleCreate() {
  const v = validateForm()
  if (v) { error.value = v; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(createGql, { input: inputPayload() })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create gateway'
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  const v = validateForm()
  if (v) { error.value = v; return }
  if (!editTarget.value) return
  saving.value = true
  error.value = ''
  try {
    await mutation(updateGql, {
      id: editTarget.value.id,
      expectedVersion: editTarget.value.version,
      input: inputPayload(),
    })
    showEdit.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update gateway'
  } finally {
    saving.value = false
  }
}

async function handleToggle(gateway: GatewayRow) {
  try {
    await mutation(toggleGql, {
      id: gateway.id,
      enabled: !gateway.enabled,
      expectedVersion: gateway.version,
    })
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to toggle gateway'
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, {
      id: deleteTarget.value.id,
      expectedVersion: deleteTarget.value.version,
    })
    showDelete.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete gateway'
  } finally {
    deleting.value = false
  }
}

function onRowClick(row: GatewayRow) {
  router.push(`/gateway/gateways/${row.id}`)
}

function menuItems(gateway: GatewayRow) {
  return [
    { id: 'edit', label: 'Edit', icon: 'edit' },
    { id: 'toggle', label: gateway.enabled ? 'Disable' : 'Enable', icon: gateway.enabled ? 'lock' : 'unlock' },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onMenuSelect(id: string, gateway: GatewayRow) {
  if (id === 'edit') openEdit(gateway)
  else if (id === 'toggle') handleToggle(gateway)
  else if (id === 'delete') openDelete(gateway)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Gateway', 'Gateways')"
        title="Gateways"
        :subtitle="subtitle"
        :tabs="['Enabled', 'All', 'Disabled']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Gateway</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="gateways"
      row-key="id"
      :loading="isLoading"
      empty-text="No gateways configured. Create one to proxy upstream traffic with Bosca auth."
      @row-click="onRowClick"
    >
      <template #col-name="{ row }">
        <span class="gateway-name">{{ row.name }}</span>
      </template>
      <template #col-url="{ value }">
        <code class="mono">{{ value }}</code>
      </template>
      <template #col-health="{ row }">
        <code v-if="row.healthCheckPath" class="mono fg-3">{{ row.healthCheckPath }}</code>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-upstreamHealth="{ row }">
        <Badge :color="healthBadgeColor(row.healthStatus, accent)">
          {{ row.healthStatus }}
        </Badge>
      </template>
      <template #col-status="{ row }">
        <Badge :color="row.enabled ? accent : '#888'">{{ row.enabled ? 'Enabled' : 'Disabled' }}</Badge>
      </template>
      <template #col-modified="{ row }">
        {{ formatDate(row.modifiedAt) }}
      </template>
      <template #actions="{ row }">
        <OverflowMenu :items="menuItems(row)" @select="onMenuSelect($event, row)">
          <template #default="{ toggle }">
            <button class="row-menu-btn" @click.stop="toggle">
              <Icon name="list" :size="14" color="var(--fg-3)" />
            </button>
          </template>
        </OverflowMenu>
      </template>
    </GlassTable>

    <Modal
      v-if="showCreate"
      title="New Gateway"
      icon="globe"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. trino" />
        <TextInput
          v-model="form.url"
          label="Upstream URL"
          placeholder="http://trino.internal:8080"
          mono />
        <TextInput
          v-model="form.healthCheckPath"
          label="Health Check Path"
          placeholder="/v1/info (optional)"
          mono />
        <div class="form-row">
          <NumberInput v-model="form.healthCheckIntervalSecs" label="Health Check Interval (s)" :min="5" />
          <NumberInput v-model="form.connectTimeoutSecs" label="Connect Timeout (s)" :min="1" />
        </div>
        <div class="form-row">
          <NumberInput v-model="form.requestTimeoutSecs" label="Request Timeout (s)" :min="1" />
          <NumberInput v-model="form.poolMaxIdle" label="Pool Max Idle" :min="0" />
        </div>
        <NumberInput v-model="form.poolIdleTimeoutSecs" label="Pool Idle Timeout (s)" :min="1" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Gateway' }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="showEdit"
      title="Edit Gateway"
      icon="pencil"
      :accent="accent"
      @close="showEdit = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. trino" />
        <TextInput
          v-model="form.url"
          label="Upstream URL"
          placeholder="http://trino.internal:8080"
          mono />
        <p class="form-warning">
          Changing the upstream URL requires <strong>gateway-admin</strong> group membership.
          A mistake here silently redirects traffic.
        </p>
        <TextInput
          v-model="form.healthCheckPath"
          label="Health Check Path"
          placeholder="/v1/info (optional)"
          mono />
        <div class="form-row">
          <NumberInput v-model="form.healthCheckIntervalSecs" label="Health Check Interval (s)" :min="5" />
          <NumberInput v-model="form.connectTimeoutSecs" label="Connect Timeout (s)" :min="1" />
        </div>
        <div class="form-row">
          <NumberInput v-model="form.requestTimeoutSecs" label="Request Timeout (s)" :min="1" />
          <NumberInput v-model="form.poolMaxIdle" label="Pool Max Idle" :min="0" />
        </div>
        <NumberInput v-model="form.poolIdleTimeoutSecs" label="Pool Idle Timeout (s)" :min="1" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleUpdate">
          {{ saving ? 'Saving…' : 'Save Changes' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Gateway"
      subtitle="Routes referencing this gateway must be deleted first."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete"
    >
      <p>Are you sure you want to delete <strong>{{ deleteTarget?.name }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.gateway-name {
  font-weight: 500;
}

.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

.form-warning {
  background: var(--bg-3);
  border-left: 3px solid var(--warn, #ffb547);
  padding: 8px 10px;
  font-size: 12px;
  color: var(--fg-2);
  border-radius: 4px;
  margin: 0;
}

.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.row-menu-btn:hover {
  background: var(--bg-3);
}

.fg-3 {
  color: var(--fg-3);
}
</style>
