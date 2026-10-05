<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

interface GatewayOption {
  id: string
  name: string
}

interface RouteRow {
  id: string
  gatewayId: string
  gateway: GatewayOption | null
  pathPattern: string
  hosts: string[]
  authMethod: 'OAUTH2' | 'JWT' | 'BASIC' | 'NONE'
  stripPrefix: boolean
  readGroups: string[]
  writeGroups: string[]
  injectHeaders: Record<string, string> | null
  sortOrder: number
  enabled: boolean
  modifiedAt: string
  version: number
}

const showCreate = ref(false)
const showEdit = ref(false)
const showDelete = ref(false)
const editTarget = ref<RouteRow | null>(null)
const deleteTarget = ref<RouteRow | null>(null)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')

interface RouteForm {
  gatewayId: string
  pathPattern: string
  hosts: string[]
  authMethod: 'OAUTH2' | 'JWT' | 'BASIC' | 'NONE'
  stripPrefix: boolean
  readGroups: string[]
  writeGroups: string[]
  /** Held as a parsed object so JsonEditorVue can edit it directly.
   *  Stringified only at the wire boundary inside inputPayload. */
  injectHeaders: Record<string, string>
  sortOrder: number
}

const form = reactive<RouteForm>({
  gatewayId: '',
  pathPattern: '',
  hosts: [],
  authMethod: 'JWT',
  stripPrefix: false,
  readGroups: [],
  writeGroups: [],
  injectHeaders: {},
  sortOrder: 0,
})

function resetForm() {
  form.gatewayId = ''
  form.pathPattern = ''
  form.hosts = []
  form.authMethod = 'JWT'
  form.stripPrefix = false
  form.readGroups = []
  form.writeGroups = []
  form.injectHeaders = {}
  form.sortOrder = 0
  selectedPresetId.value = 'custom'
  error.value = ''
}

const listGql = gql`
  query {
    gateway {
      routes {
        all {
          id
          gatewayId
          gateway { id name }
          pathPattern
          hosts
          authMethod
          stripPrefix
          readGroups
          writeGroups
          injectHeaders
          sortOrder
          enabled
          modifiedAt
          version
        }
      }
    }
  }
`

const gatewaysGql = gql`
  query {
    gateway {
      services {
        all { id name enabled }
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  gateway: { routes: { all: RouteRow[] } }
}>('gateway-routes', listGql)

const { data: gatewaysData } = useAsyncQuery<{
  gateway: { services: { all: Array<GatewayOption & { enabled: boolean }> } }
}>('gateway-routes-services', gatewaysGql)

const routes = computed(() => data.value?.gateway?.routes?.all ?? [])
const gatewayOptions = computed(() =>
  (gatewaysData.value?.gateway?.services?.all ?? [])
    .filter(g => g.enabled)
    .map(g => ({ value: g.id, label: g.name })),
)

const isLoading = computed(() => status.value === 'pending')

const columns: GlassTableColumn[] = [
  { key: 'pathPattern', label: 'Path Pattern', width: '2fr' },
  { key: 'hosts', label: 'Hosts', width: '1.5fr' },
  { key: 'gateway', label: 'Gateway', width: '1fr' },
  { key: 'authMethod', label: 'Auth', width: '110px' },
  { key: 'readGroups', label: 'Read', width: '1fr' },
  { key: 'writeGroups', label: 'Write', width: '1fr' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'modified', label: 'Modified', width: '120px', muted: true },
]

const AUTH_OPTIONS = [
  { value: 'JWT', label: 'JWT Bearer' },
  { value: 'OAUTH2', label: 'Studio login (browser session)' },
  { value: 'BASIC', label: 'HTTP Basic Auth' },
  { value: 'NONE', label: 'Unauthenticated (public)' },
]

/**
 * Route presets: pre-built field bundles for common upstreams.
 * Selecting a preset overlays its fields onto the current form so the
 * operator doesn't have to remember which `X-Forwarded-*` shape a
 * given backend expects. Fields the preset doesn't touch are left
 * alone — picking a preset is additive, never destructive.
 *
 * `hint` is shown below the dropdown after a selection — used to call
 * out *upstream-side* config the proxy can't enforce (e.g. Trino's
 * `http-server.process-forwarded=true`).
 */
interface RoutePreset {
  id: string
  label: string
  description: string
  apply: (f: RouteForm) => void
  hint?: string
}

const ROUTE_PRESETS: RoutePreset[] = [
  {
    id: 'custom',
    label: 'Custom (no preset)',
    description: 'Start from scratch — leaves the form untouched.',
    apply: () => {},
  },
  {
    id: 'trino',
    label: 'Trino',
    description: 'Trino coordinator behind the gateway, with prefix stripping.',
    apply: (f) => {
      if (!f.pathPattern) f.pathPattern = '/trino/**'
      f.stripPrefix = true
    },
    hint: 'Trino requires http-server.process-forwarded=true on the coordinator AND a configured trusted_proxies CIDR (or external_scheme=https) on the gateway so the X-Forwarded-Proto the proxy stamps is "https". Without that, Trino refuses BASIC auth as "insecure".',
  },
  {
    id: 'passthrough',
    label: 'Raw pass-through',
    description: 'No prefix stripping, no special headers — the gateway forwards as-is.',
    apply: (f) => {
      f.stripPrefix = false
      f.injectHeaders = {}
    },
  },
]

const PRESET_OPTIONS = ROUTE_PRESETS.map(p => ({ value: p.id, label: p.label }))

const selectedPresetId = ref<string>('custom')

const activePresetHint = computed(() => {
  const p = ROUTE_PRESETS.find(p => p.id === selectedPresetId.value)
  return p?.hint ?? ''
})

function applyPreset(value: string | string[] | null | undefined) {
  if (typeof value !== 'string') return
  selectedPresetId.value = value
  const preset = ROUTE_PRESETS.find(p => p.id === value)
  if (preset) preset.apply(form)
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

function openEdit(route: RouteRow) {
  editTarget.value = route
  form.gatewayId = route.gatewayId
  form.pathPattern = route.pathPattern
  form.hosts = [...(route.hosts ?? [])]
  form.authMethod = route.authMethod
  form.stripPrefix = route.stripPrefix
  form.readGroups = [...route.readGroups]
  form.writeGroups = [...route.writeGroups]
  // Clone so edits to the form don't mutate the cached query result.
  form.injectHeaders = { ...(route.injectHeaders ?? {}) }
  form.sortOrder = route.sortOrder
  error.value = ''
  showEdit.value = true
}

function openDelete(route: RouteRow) {
  deleteTarget.value = route
  showDelete.value = true
}

const createGql = gql`
  mutation CreateRoute($input: GatewayRouteInput!) {
    gateway { routes { create(input: $input) { id } } }
  }
`

const updateGql = gql`
  mutation UpdateRoute($id: UUID!, $input: GatewayRouteInput!, $expectedVersion: Long!) {
    gateway { routes { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
  }
`

const toggleGql = gql`
  mutation ToggleRoute($id: UUID!, $enabled: Boolean!, $expectedVersion: Long!) {
    gateway { routes { toggleEnabled(id: $id, enabled: $enabled, expectedVersion: $expectedVersion) { id } } }
  }
`

const deleteGql = gql`
  mutation DeleteRoute($id: UUID!, $expectedVersion: Long!) {
    gateway { routes { delete(id: $id, expectedVersion: $expectedVersion) { id } } }
  }
`

// Host pattern accepted by the proxy: a literal DNS host
// (`api.bosca.io`) or a leading-`*.` wildcard (`*.bosca.io`). The Rust
// matcher and the Kotlin service both reject anything else, so we
// pre-validate at the form boundary to give the operator immediate
// feedback rather than waiting for the GraphQL error round-trip.
const HOST_PATTERN_RE = /^(\*\.)?([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\.([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?))*$/

function validateForm(): { headers: Record<string, string> } | string {
  if (!form.gatewayId) return 'Gateway is required.'
  if (!form.pathPattern) return 'Path pattern is required.'
  if (!form.pathPattern.startsWith('/')) return 'Path pattern must start with /.'
  const badHost = form.hosts.find(h => !HOST_PATTERN_RE.test(h))
  if (badHost) {
    return `Invalid host pattern "${badHost}". Use a literal host (api.bosca.io) or a leading-*. wildcard (*.bosca.io).`
  }
  if (form.authMethod === 'NONE' && (form.readGroups.length > 0 || form.writeGroups.length > 0)) {
    return 'auth_method = NONE bypasses group checks; clear Read/Write Groups or pick a real auth method.'
  }
  // The form holds the headers as a JS object courtesy of JsonEditorVue
  // — no JSON parsing needed. We still validate shape: must be an
  // object whose values are strings, since the proxy only injects
  // string-valued headers and a non-string would silently drop at the
  // Rust side.
  if (typeof form.injectHeaders !== 'object' || form.injectHeaders === null || Array.isArray(form.injectHeaders)) {
    return 'Inject Headers must be a JSON object.'
  }
  for (const [k, v] of Object.entries(form.injectHeaders)) {
    if (typeof v !== 'string') {
      return `Header "${k}" must have a string value.`
    }
  }
  return { headers: form.injectHeaders }
}

function inputPayload(headers: Record<string, string>) {
  return {
    gatewayId: form.gatewayId,
    pathPattern: form.pathPattern,
    hosts: form.hosts,
    authMethod: form.authMethod,
    stripPrefix: form.stripPrefix,
    readGroups: form.readGroups,
    writeGroups: form.writeGroups,
    injectHeaders: headers,
    sortOrder: form.sortOrder,
  }
}

async function handleCreate() {
  const v = validateForm()
  if (typeof v === 'string') { error.value = v; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(createGql, { input: inputPayload(v.headers) })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create route'
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  const v = validateForm()
  if (typeof v === 'string') { error.value = v; return }
  if (!editTarget.value) return
  saving.value = true
  error.value = ''
  try {
    await mutation(updateGql, {
      id: editTarget.value.id,
      expectedVersion: editTarget.value.version,
      input: inputPayload(v.headers),
    })
    showEdit.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update route'
  } finally {
    saving.value = false
  }
}

async function handleToggle(route: RouteRow) {
  try {
    await mutation(toggleGql, {
      id: route.id,
      enabled: !route.enabled,
      expectedVersion: route.version,
    })
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to toggle route'
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
    error.value = e instanceof Error ? e.message : 'Failed to delete route'
  } finally {
    deleting.value = false
  }
}

function menuItems(route: RouteRow) {
  return [
    { id: 'edit', label: 'Edit', icon: 'edit' },
    { id: 'toggle', label: route.enabled ? 'Disable' : 'Enable', icon: route.enabled ? 'lock' : 'unlock' },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onMenuSelect(id: string, route: RouteRow) {
  if (id === 'edit') openEdit(route)
  else if (id === 'toggle') handleToggle(route)
  else if (id === 'delete') openDelete(route)
}

const subtitle = computed(() => {
  const enabled = routes.value.filter(r => r.enabled).length
  return `${routes.value.length} routes · ${enabled} enabled`
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Gateway', 'Routes')"
        title="Routes"
        :subtitle="subtitle"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Route</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="routes"
      row-key="id"
      :loading="isLoading"
      empty-text="No routes configured. Add one to expose an upstream path."
    >
      <template #col-pathPattern="{ value }">
        <code class="mono">{{ value }}</code>
      </template>
      <template #col-hosts="{ row }">
        <span v-if="row.hosts.length === 0" class="fg-3">any host</span>
        <span v-else class="hosts-cell">
          <code v-for="h in row.hosts" :key="h" class="mono host-chip">{{ h }}</code>
        </span>
      </template>
      <template #col-gateway="{ row }">
        <span v-if="row.gateway">{{ row.gateway.name }}</span>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-authMethod="{ value }">
        <Badge :color="value === 'NONE' ? 'var(--warn, #ffb547)' : accent">
          {{ value }}
        </Badge>
      </template>
      <template #col-readGroups="{ row }">
        <span v-if="row.readGroups.length === 0" class="fg-3">denied</span>
        <span v-else>{{ row.readGroups.join(', ') }}</span>
      </template>
      <template #col-writeGroups="{ row }">
        <span v-if="row.writeGroups.length === 0" class="fg-3">denied</span>
        <span v-else>{{ row.writeGroups.join(', ') }}</span>
      </template>
      <template #col-status="{ row }">
        <Badge :color="row.enabled ? accent : '#888'">
          {{ row.enabled ? 'Enabled' : 'Disabled' }}
        </Badge>
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
      v-if="showCreate || showEdit"
      :title="showCreate ? 'New Route' : 'Edit Route'"
      icon="route"
      :accent="accent"
      @close="showCreate = false; showEdit = false">
      <div class="form-stack">
        <Select
          v-model="form.gatewayId"
          :options="gatewayOptions"
          label="Gateway"
          placeholder="Select gateway"
          :accent="accent" />
        <div class="preset-row">
          <Select
            :model-value="selectedPresetId"
            :options="PRESET_OPTIONS"
            label="Preset"
            :accent="accent"
            @update:model-value="applyPreset"
          />
          <p v-if="activePresetHint" class="form-hint preset-hint">
            {{ activePresetHint }}
          </p>
        </div>
        <TextInput
          v-model="form.pathPattern"
          label="Path Pattern"
          placeholder="/api/foo/**"
          mono />
        <p class="form-hint">
          Use <code>/foo/**</code> for prefix match, <code>/foo/*</code> for single-segment prefix,
          or an exact path like <code>/health</code>.
        </p>
        <TagInput
          v-model="form.hosts"
          label="Hosts"
          placeholder="api.bosca.io or *.bosca.io — leave empty to match any host" />
        <p class="form-hint">
          Restrict this route to specific hostnames. Each entry is a literal host
          (<code>api.bosca.io</code>) or a leading-<code>*.</code> wildcard
          (<code>*.bosca.io</code>, matching one label). Empty = match any host.
          Host-literal outranks host-wildcard outranks host-any.
        </p>
        <Select
          v-model="form.authMethod"
          :options="AUTH_OPTIONS"
          label="Auth Method"
          :accent="accent" />
        <p
          v-if="form.authMethod === 'NONE'"
          class="form-warning">
          <strong>NONE</strong> exposes this route without authentication. Read and Write
          Groups are ignored — anyone can reach the upstream.
        </p>
        <Checkbox
          v-model="form.stripPrefix"
          label="Strip matched prefix before forwarding"
          :accent="accent" />
        <GroupPicker
          v-model="form.readGroups"
          label="Read Groups"
          placeholder="Search groups allowed to read (GET / HEAD / OPTIONS)…"
          hint="Principal must belong to one of these groups. API tokens additionally need the gateway:read scope. Empty list denies all reads."
          :accent="accent" />
        <GroupPicker
          v-model="form.writeGroups"
          label="Write Groups"
          placeholder="Search groups allowed to write (POST / PUT / PATCH / DELETE)…"
          hint="Principal must belong to one of these groups. API tokens additionally need the gateway:write scope. Empty list denies all writes."
          :accent="accent" />
        <div class="json-field">
          <label class="json-label">Inject Headers</label>
          <JsonEditorVue
            :model-value="form.injectHeaders"
            :main-menu-bar="false"
            :navigation-bar="false"
            class="jse-theme-dark json-editor-sm"
            @update:model-value="form.injectHeaders = $event"
          />
          <p class="form-hint">
            Substituted before the request leaves the gateway. Variables:
            <code v-pre>{{user.email}}</code>, <code v-pre>{{user.subject}}</code>,
            <code v-pre>{{user.groups}}</code>, <code v-pre>{{request.host}}</code>,
            <code v-pre>{{request.scheme}}</code>, <code v-pre>{{request.port}}</code>,
            <code v-pre>{{request.clientIp}}</code>.
          </p>
        </div>
        <NumberInput v-model="form.sortOrder" label="Sort Order (lower wins on ties)" :min="0" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false; showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="showCreate ? handleCreate() : handleUpdate()">
          {{ saving ? 'Saving…' : (showCreate ? 'Create Route' : 'Save Changes') }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Route"
      subtitle="The path will stop matching at the proxy on the next config refresh."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete"
    >
      <p>Delete route <code class="mono">{{ deleteTarget?.pathPattern }}</code>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

.form-hint {
  font-size: 11px;
  color: var(--fg-3);
  margin: -8px 0 0;
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

.json-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.json-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.json-editor-sm {
  min-height: 160px;
  max-height: 320px;
  border-radius: var(--r-sm);
  overflow: hidden;
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

.hosts-cell {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.host-chip {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--bg-2);
  border: 1px solid var(--bg-3);
  font-size: 11px;
}

.preset-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preset-hint {
  margin: 0;
  padding: 6px 10px;
  background: var(--bg-2);
  border-left: 3px solid var(--accent, var(--brand-2));
  border-radius: 4px;
}
</style>
