<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sGateway, K8sHttpRoute } from '~/composables/useK8sTypes'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'
import type { EditResourceYamlTarget } from '~/components/kubernetes/EditResourceYamlModal.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const id = computed(() => route.params.id as string)
const clusterId = computed(() => current.value?.id)
const { data: gwData, refresh: refreshGateways } = useK8sGateways({ cluster: clusterId })
const { data: routesData, refresh: refreshRoutes } = useK8sHttpRoutes({ cluster: clusterId })

// Live refresh — gateway programming and route attach/detach re-run
// both queries so listeners / addresses / routes stay realtime.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['Gateway', 'HTTPRoute'],
  onChange: () => { refreshGateways(); refreshRoutes(); refreshManifest() },
})
const gw = computed<K8sGateway | undefined>(() => (gwData.value ?? []).find(g => g.id === id.value))
const routes = computed<K8sHttpRoute[]>(() => gw.value
  ? (routesData.value ?? []).filter(r => r.parents.includes(gw.value!.name))
  : [],
)

const tabs = ['Overview', 'Listeners', 'Routes', 'Manifest']
const tab = ref('Overview')

const { data: manifestYaml, status: manifestStatus, refresh: refreshManifest } = useK8sResourceYaml({
  cluster: clusterId,
  kind: () => 'Gateway',
  name: () => gw.value?.name ?? null,
  namespace: () => gw.value?.ns ?? null,
  group: () => 'gateway.networking.k8s.io',
})
const isManifestLoading = computed(() => manifestStatus.value === 'pending')

const editTarget = ref<EditResourceYamlTarget | null>(null)
function openEdit() {
  const g = gw.value
  if (!g) return
  editTarget.value = {
    displayKind: 'Gateway',
    kind: 'Gateway',
    name: g.name,
    namespace: g.ns,
    group: 'gateway.networking.k8s.io',
  }
}
function onApplied() {
  editTarget.value = null
  void refreshGateways()
  void refreshManifest()
}

const deleteTarget = ref<DeleteResourceTarget | null>(null)
function openDelete() {
  const g = gw.value
  if (!g) return
  deleteTarget.value = {
    displayKind: 'Gateway',
    kind: 'Gateway',
    name: g.name,
    namespace: g.ns,
    group: 'gateway.networking.k8s.io',
  }
}
function onDeleted() {
  deleteTarget.value = null
  router.replace('/kubernetes/gateways')
}

const statusLabel = computed(() => gw.value?.status === 'ok' ? 'Programmed' : 'Degraded')
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Gateway API', to: '/kubernetes/gateways' }, gw?.name || id]"
        :title="gw?.name || id"
        :tabs="tabs"
        :active-tab="tab"
        @tab="(v) => tab = v"
      >
        <template v-if="gw" #subtitle>
          <K8sStatusBadge :status="statusLabel" :pulse="gw.status !== 'ok'" />
          <span class="sub-text">{{ gw.ns }} · class {{ gw.class }}</span>
        </template>
        <template #actions>
          <Button size="sm" icon="code" @click="openEdit">Edit YAML</Button>
          <Button size="sm" icon="trash" @click="openDelete">Delete</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!gw" class="empty">Gateway not found.</div>

    <template v-else>
      <div v-if="tab === 'Overview'" class="grid">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Gateway</h3></template>
          <div class="kv">
            <div class="k">Namespace</div><div class="v mono">{{ gw.ns }}</div>
            <div class="k">Class</div><div class="v mono">{{ gw.class }}</div>
            <div class="k">Listeners</div><div class="v mono">{{ gw.listeners }}</div>
            <div class="k">Attached routes</div><div class="v mono">{{ routes.length }}</div>
            <div class="k">Age</div><div class="v">{{ gw.age }}</div>
          </div>
        </SectionCard>
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Addresses</h3></template>
          <div class="addresses">
            <div v-for="a in gw.addresses" :key="a" class="addr mono">{{ a }}</div>
          </div>
        </SectionCard>
      </div>

      <SectionCard v-else-if="tab === 'Listeners'" padded glass>
        <div class="listeners">
          <div class="listener">
            <div class="lh">
              <span class="mono name">http</span>
              <Badge color="#5ec5ff">HTTP</Badge>
              <span class="port mono">:80</span>
              <span class="spacer" />
              <K8sStatusBadge status="Programmed" />
            </div>
            <div class="dim">Allows all hostnames · accepts routes from all namespaces</div>
          </div>
          <div class="listener">
            <div class="lh">
              <span class="mono name">https</span>
              <Badge color="#34d99a">HTTPS</Badge>
              <span class="port mono">:443</span>
              <span class="spacer" />
              <K8sStatusBadge status="Programmed" />
            </div>
            <div class="dim">TLS terminate via secret <span class="mono">{{ gw.name }}-tls</span></div>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Routes'" padded glass>
        <div v-if="routes.length === 0" class="empty">No attached routes.</div>
        <div v-else class="routes">
          <div v-for="r in routes" :key="r.id" class="route">
            <div class="rh">
              <span class="mono name">{{ r.name }}</span>
              <Badge color="#a78bff">{{ r.ns }}</Badge>
              <K8sStatusBadge :status="r.status === 'ok' ? 'Healthy' : r.status === 'warn' ? 'Degraded' : 'Failing'" :pulse="r.status !== 'ok'" />
            </div>
            <div class="rb">
              <span class="rb-l">hosts</span><span class="mono">{{ r.hosts.join(', ') }}</span>
              <span class="rb-l">paths</span><span class="mono">{{ r.paths.join(', ') }}</span>
              <span class="rb-l">backends</span><span class="mono">{{ r.backends.join(', ') }}</span>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Manifest'" padded glass>
        <div v-if="isManifestLoading" class="empty">Loading manifest…</div>
        <CodeEditor
          v-else
          :model-value="manifestYaml"
          language="text"
          :rows="24"
          readonly />
      </SectionCard>
    </template>

    <EditResourceYamlModal
      :target="editTarget"
      :accent="accent"
      @close="editTarget = null"
      @applied="onApplied" />

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="onDeleted" />
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
.kv { display: grid; grid-template-columns: 140px 1fr; row-gap: 8px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.empty { padding: 32px; text-align: center; color: var(--fg-3); }

.addresses { display: flex; flex-direction: column; gap: 6px; }
.addr { padding: 6px 10px; background: var(--bg-2); border: 1px solid var(--line); border-radius: 6px; font-size: 12.5px; }

.listeners { display: flex; flex-direction: column; gap: 14px; }
.listener { padding: 12px; background: var(--bg-2); border: 1px solid var(--line); border-radius: 8px; }
.lh { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.lh .name { font-weight: 600; font-size: 13px; }
.lh .port { color: var(--fg-3); font-size: 12px; }
.spacer { flex: 1; }

.routes { display: flex; flex-direction: column; gap: 12px; }
.route { padding: 12px; background: var(--bg-2); border: 1px solid var(--line); border-radius: 8px; }
.rh { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.rh .name { font-weight: 600; font-size: 13px; }
.rb { display: grid; grid-template-columns: 80px 1fr; row-gap: 4px; font-size: 12px; }
.rb-l { color: var(--fg-3); }
</style>
