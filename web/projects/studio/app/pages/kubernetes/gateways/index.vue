<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sGateway, K8sHttpRoute } from '~/composables/useK8sTypes'
import type { EditResourceYamlTarget } from '~/components/kubernetes/EditResourceYamlModal.vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()
const applyOpen = ref(false)
const yamlTarget = ref<EditResourceYamlTarget | null>(null)

function openGatewayYaml(gw: K8sGateway) {
  yamlTarget.value = {
    displayKind: 'Gateway',
    kind: 'Gateway',
    name: gw.name,
    namespace: gw.ns,
    group: 'gateway.networking.k8s.io',
  }
}

const clusterId = computed(() => current.value?.id)
const { data: gwData, status: gwStatus, refresh: refreshGw } = useK8sGateways({ cluster: clusterId })
const { data: gcData, status: gcStatus, refresh: refreshGc } = useK8sGatewayClasses(clusterId)
const { data: routeData, status: routeStatus, refresh: refreshRoutes } = useK8sHttpRoutes({ cluster: clusterId })

const GATEWAYS = computed(() => gwData.value ?? [])
const GATEWAY_CLASSES = computed(() => gcData.value ?? [])
const HTTP_ROUTES = computed(() => routeData.value ?? [])

// Live refresh — Gateway API changes (class acceptance, gateway
// programming, route attachment) re-run all three list queries.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['GatewayClass', 'Gateway', 'HTTPRoute'],
  onChange: () => { refreshGw(); refreshGc(); refreshRoutes() },
})
const isLoading = computed(() => gwStatus.value === 'pending' || gcStatus.value === 'pending' || routeStatus.value === 'pending')

function refreshAll() {
  refreshGw(); refreshGc(); refreshRoutes()
}

const search = ref('')

const filteredGateways = computed<K8sGateway[]>(() => GATEWAYS.value.filter(g => nsFilter.matches(g.ns) && (!search.value || `${g.name} ${g.class}`.toLowerCase().includes(search.value.toLowerCase()))))

const routesByGateway = computed(() => {
  const map: Record<string, K8sHttpRoute[]> = {}
  for (const gw of filteredGateways.value) {
    map[gw.name] = HTTP_ROUTES.value.filter(r => r.parents.includes(gw.name))
  }
  return map
})

const classColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1fr)' },
  { key: 'controller', label: 'Controller', width: 'minmax(280px, 2fr)', muted: true },
  { key: 'accepted', label: 'Accepted', width: '110px' },
  { key: 'gateways', label: 'Gateways', width: '90px', align: 'right' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const classRows = computed(() => GATEWAY_CLASSES.value.map(gc => ({
  ...gc,
  gateways: GATEWAYS.value.filter(g => g.class === gc.name).length,
})))
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Gateway API')"
        title="Gateway API"
        :subtitle="`${GATEWAYS.length} gateways · ${HTTP_ROUTES.length} routes · ${GATEWAY_CLASSES.length} classes`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refreshAll">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="applyOpen = true">New gateway</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter gateways…" class="search" />
      <K8sNamespaceFilter />
      <span class="spacer" />
      <span class="muted">{{ filteredGateways.length }} of {{ GATEWAYS.length }}</span>
    </div>

    <SectionCard glass>
      <template #header>
        <div class="card-head">
          <h3>Gateway classes</h3>
          <span class="muted">{{ GATEWAY_CLASSES.length }} controllers installed</span>
        </div>
      </template>
      <GlassTable
        :columns="classColumns"
        :rows="classRows"
        row-key="name"
        :arrow="false">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-controller="{ row }"><span class="mono">{{ row.controller }}</span></template>
        <template #col-accepted="{ row }">
          <K8sStatusBadge :status="row.accepted ? 'Accepted' : 'Failed'" />
        </template>
        <template #col-gateways="{ row }"><span class="mono">{{ row.gateways }}</span></template>
      </GlassTable>
    </SectionCard>

    <SectionCard
      v-for="gw in filteredGateways"
      :key="gw.id"
      padded
      glass
      class="gw-card">
      <template #header>
        <div class="gw-head">
          <Icon name="globe" :size="15" :color="accent" />
          <h3>{{ gw.name }}</h3>
          <Badge :color="accent">{{ gw.class }}</Badge>
          <K8sStatusBadge :status="gw.status === 'ok' ? 'Programmed' : 'Degraded'" :pulse="gw.status !== 'ok'" />
          <span class="mono muted ns">{{ gw.ns }}</span>
          <span class="spacer" />
          <Button size="sm" icon="code" @click="openGatewayYaml(gw)">YAML</Button>
        </div>
      </template>

      <div class="kv-row">
        <div class="kv">
          <div class="k">Addresses</div>
          <div class="v">
            <div v-for="a in gw.addresses" :key="a" class="mono">{{ a }}</div>
          </div>
        </div>
        <div class="kv">
          <div class="k">Listeners</div>
          <div class="v mono">{{ gw.listeners }} active</div>
        </div>
        <div class="kv">
          <div class="k">Attached routes</div>
          <div class="v mono">{{ routesByGateway[gw.name]?.length || 0 }}</div>
        </div>
      </div>

      <div v-if="routesByGateway[gw.name]?.length" class="routes-block">
        <div class="routes-label">HTTPRoutes</div>
        <div class="routes-list">
          <div v-for="r in routesByGateway[gw.name]" :key="r.id" class="route-row">
            <span class="mono name">{{ r.name }}</span>
            <span class="mono muted ns">{{ r.ns }}</span>
            <span class="mono">{{ r.hosts.join(', ') }}</span>
            <span class="mono dim">{{ r.paths.join(', ') }}</span>
            <span class="mono dim">→ {{ r.backends.join(', ') }}</span>
            <K8sStatusBadge :status="r.status === 'ok' ? 'Healthy' : r.status === 'warn' ? 'Degraded' : 'Failing'" :pulse="r.status !== 'ok'" />
          </div>
        </div>
      </div>
    </SectionCard>

    <ApplyManifestModal
      v-if="applyOpen"
      :accent="accent"
      @close="applyOpen = false"
      @applied="refreshAll" />

    <EditResourceYamlModal
      :target="yamlTarget"
      :accent="accent"
      @close="yamlTarget = null"
      @applied="yamlTarget = null; refreshAll()" />
  </PageShell>
</template>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head h3 { margin: 0; font-size: 14px; font-weight: 600; }
.muted { color: var(--fg-3); font-size: 12px; }

.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }

.gw-head { display: flex; align-items: center; gap: 10px; width: 100%; }
.gw-head h3 { margin: 0; font-size: 14px; font-weight: 600; letter-spacing: -0.005em; }
.gw-head .ns { font-size: 11.5px; }

.kv-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 16px;
}
.kv { min-width: 0; }
.kv .k { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 6px; }
.kv .v { font-size: 13px; overflow: hidden; text-overflow: ellipsis; }
.kv .v .mono { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }

.routes-block { border-top: 1px solid var(--line); padding-top: 12px; }
.routes-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 8px; }
.routes-list { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
.route-row {
  display: grid;
  grid-template-columns: 1fr 100px 1.2fr 1fr 1.2fr auto;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  font-size: 12px;
}
.route-row + .route-row { border-top: 1px solid var(--line); }
.route-row .name { font-weight: 500; }
.route-row .dim { color: var(--fg-3); }
</style>
