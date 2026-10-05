<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const route = useRoute()
const router = useRouter()

const gatewayId = computed(() => String(route.params.id ?? ''))

type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

interface GatewayDetail {
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
  createdAt: string
  modifiedAt: string
  version: number
  routes: Array<{
    id: string
    pathPattern: string
    authMethod: string
    enabled: boolean
    readGroups: string[]
    writeGroups: string[]
  }>
}

const detailGql = gql`
  query GatewayDetail($id: UUID!) {
    gateway {
      services {
        gateway(id: $id) {
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
          createdAt
          modifiedAt
          version
          routes {
            id
            pathPattern
            authMethod
            enabled
            readGroups
            writeGroups
          }
        }
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  gateway: { services: { gateway: GatewayDetail | null } }
}>(`gateway-detail-${gatewayId.value}`, detailGql, { id: gatewayId })

const gateway = computed(() => data.value?.gateway?.services?.gateway ?? null)
const isLoading = computed(() => status.value === 'pending')
const notFound = computed(() => !isLoading.value && gateway.value == null)

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

/**
 * The health badge color encodes the most useful signal first: a real
 * "DOWN" is red because operators care, "UP" gets the subsystem accent
 * because that's the steady state, and "UNKNOWN" is muted gray because
 * it usually means the proxy hasn't probed yet or no health_check_path
 * was configured — neither of which is alarming.
 */
function healthBadgeColor(status: HealthStatus, accentColor: string): string {
  if (status === 'UP') return accentColor
  if (status === 'DOWN') return 'var(--err, #ff5c5c)'
  return '#888'
}

const toggleGql = gql`
  mutation ToggleGateway($id: UUID!, $enabled: Boolean!, $expectedVersion: Long!) {
    gateway { services { toggleEnabled(id: $id, enabled: $enabled, expectedVersion: $expectedVersion) { id } } }
  }
`

async function handleToggle() {
  if (!gateway.value) return
  try {
    await mutation(toggleGql, {
      id: gateway.value.id,
      enabled: !gateway.value.enabled,
      expectedVersion: gateway.value.version,
    })
    await refresh()
  } catch (e: unknown) {
    console.error(e)
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Gateway', 'Gateways', gateway?.name ?? 'Detail')"
        :title="gateway?.name ?? 'Gateway'"
        :subtitle="gateway?.url ?? ''"
      >
        <template #actions>
          <Button
            v-if="gateway"
            size="sm"
            :icon="gateway.enabled ? 'lock' : 'unlock'"
            @click="handleToggle">
            {{ gateway.enabled ? 'Disable' : 'Enable' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="placeholder">Loading…</div>

    <div v-else-if="notFound" class="placeholder">
      <p>Gateway not found, or you don't have permission to view it.</p>
      <Button @click="router.replace('/gateway/gateways')">Back to Gateways</Button>
    </div>

    <div v-else-if="gateway" class="detail-grid">
      <section class="card">
        <h3>Upstream Health</h3>
        <dl class="kv">
          <dt>Status</dt>
          <dd>
            <Badge :color="healthBadgeColor(gateway.healthStatus, accent)">
              {{ gateway.healthStatus }}
            </Badge>
          </dd>
          <dt>Last Changed</dt>
          <dd>{{ formatDate(gateway.healthStatusChangedAt) }}</dd>
          <dt v-if="gateway.healthStatusReason">Reason</dt>
          <dd v-if="gateway.healthStatusReason">
            <code class="mono">{{ gateway.healthStatusReason }}</code>
          </dd>
          <dt>Probe</dt>
          <dd>
            <code v-if="gateway.healthCheckPath" class="mono">{{ gateway.healthCheckPath }}</code>
            <span v-else class="fg-3">no probe configured</span>
            <span v-if="gateway.healthCheckPath" class="fg-3">
              · every {{ gateway.healthCheckIntervalSecs }}s
            </span>
          </dd>
        </dl>
      </section>

      <section class="card">
        <h3>Configuration</h3>
        <dl class="kv">
          <dt>Status</dt>
          <dd>
            <Badge :color="gateway.enabled ? accent : '#888'">
              {{ gateway.enabled ? 'Enabled' : 'Disabled' }}
            </Badge>
          </dd>
          <dt>Upstream URL</dt>
          <dd><code class="mono">{{ gateway.url }}</code></dd>
          <dt>Health Check</dt>
          <dd>
            <code v-if="gateway.healthCheckPath" class="mono">{{ gateway.healthCheckPath }}</code>
            <span v-else class="fg-3">—</span>
            <span class="fg-3"> (every {{ gateway.healthCheckIntervalSecs }}s)</span>
          </dd>
          <dt>Timeouts</dt>
          <dd>
            connect <code class="mono">{{ gateway.connectTimeoutSecs }}s</code> ·
            request <code class="mono">{{ gateway.requestTimeoutSecs }}s</code>
          </dd>
          <dt>Connection Pool</dt>
          <dd>
            max idle <code class="mono">{{ gateway.poolMaxIdle }}</code> ·
            idle timeout <code class="mono">{{ gateway.poolIdleTimeoutSecs }}s</code>
          </dd>
          <dt>Created</dt>
          <dd>{{ formatDate(gateway.createdAt) }}</dd>
          <dt>Modified</dt>
          <dd>{{ formatDate(gateway.modifiedAt) }}</dd>
        </dl>
      </section>

      <section class="card">
        <div class="card-header">
          <h3>Routes</h3>
          <NuxtLink to="/gateway/routes" class="card-link">View all →</NuxtLink>
        </div>
        <div v-if="gateway.routes.length === 0" class="empty">
          No routes configured for this gateway.
        </div>
        <ul v-else class="route-list">
          <li v-for="r in gateway.routes" :key="r.id" class="route-row">
            <div class="route-path">
              <code class="mono">{{ r.pathPattern }}</code>
              <Badge :color="r.enabled ? accent : '#888'" small>
                {{ r.enabled ? 'enabled' : 'disabled' }}
              </Badge>
            </div>
            <div class="route-meta">
              <Badge :color="r.authMethod === 'NONE' ? 'var(--warn, #ffb547)' : accent" small>
                {{ r.authMethod }}
              </Badge>
              <span class="fg-3">
                read: <span v-if="r.readGroups.length > 0">{{ r.readGroups.join(', ') }}</span>
                <span v-else class="denied">denied</span>
                <span class="sep"> · </span>
                write: <span v-if="r.writeGroups.length > 0">{{ r.writeGroups.join(', ') }}</span>
                <span v-else class="denied">denied</span>
              </span>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </PageShell>
</template>

<style scoped>
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 16px;
}

.card {
  background: var(--bg-1);
  border: 1px solid var(--bg-3);
  border-radius: 8px;
  padding: 16px;
}

.card h3 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-1);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 12px;
}

.card-link {
  font-size: 12px;
  color: var(--fg-3);
  text-decoration: none;
}

.card-link:hover {
  color: var(--fg-1);
}

.kv {
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: 8px 12px;
  margin: 0;
}

.kv dt {
  font-size: 12px;
  color: var(--fg-3);
}

.kv dd {
  margin: 0;
  font-size: 13px;
  color: var(--fg-1);
}

.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.route-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.route-row {
  padding: 10px 12px;
  background: var(--bg-2);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.route-path {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.route-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}

.empty {
  font-size: 13px;
  color: var(--fg-3);
  font-style: italic;
}

.placeholder {
  padding: 32px;
  text-align: center;
  color: var(--fg-3);
}

.small {
  font-size: 12px;
}

.fg-3 {
  color: var(--fg-3);
}
</style>
