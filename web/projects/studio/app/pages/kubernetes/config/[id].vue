<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sConfigResource } from '~/composables/useK8sTypes'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'
import type { EditResourceYamlTarget } from '~/components/kubernetes/EditResourceYamlModal.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()

const id = computed(() => decodeURIComponent(route.params.id as string))
const clusterId = computed(() => current.value?.id)
const { data: resourcesData, refresh: refreshResources } = useK8sConfigResources({ cluster: clusterId })
const resource = computed<K8sConfigResource | undefined>(
  () => (resourcesData.value ?? []).find(r => r.id === id.value),
)

const kindGqlRef = computed(() => resource.value?.kind)
const { data: entriesData, refresh: refreshEntries } = useK8sConfigEntries({
  cluster: clusterId,
  namespace: () => resource.value?.ns,
  kind: kindGqlRef,
  name: () => resource.value?.name,
})

// Live refresh — edits to the ConfigMap / Secret re-run both the
// summary row and the entries (keys + values) queries.
useK8sResourceWatch({
  cluster: clusterId,
  namespace: () => resource.value?.ns,
  kinds: ['ConfigMap', 'Secret'],
  onChange: () => { refreshResources(); refreshEntries() },
})

const entriesByKey = computed(() => {
  const map: Record<string, string> = {}
  for (const entry of entriesData.value ?? []) {
    map[entry.key] = entry.value
  }
  return map
})

const revealed = ref<Record<string, boolean>>({})
function reveal(key: string) { revealed.value[key] = !revealed.value[key] }

function valueFor(key: string): string {
  return entriesByKey.value[key] ?? '(loading…)'
}

function masked(s: string): string {
  if (s.length <= 6) return '••••••'
  return `${s.slice(0, 4)}${'•'.repeat(Math.max(8, Math.min(40, s.length - 8)))}${s.slice(-4)}`
}

const editTarget = ref<EditResourceYamlTarget | null>(null)
function openEdit() {
  const r = resource.value
  if (!r) return
  editTarget.value = {
    displayKind: r.kind,
    kind: r.kind,
    name: r.name,
    namespace: r.ns,
  }
}

const deleteTarget = ref<DeleteResourceTarget | null>(null)
function openDelete() {
  const r = resource.value
  if (!r) return
  deleteTarget.value = {
    displayKind: r.kind,
    kind: r.kind,
    name: r.name,
    namespace: r.ns,
  }
}
function onDeleted() {
  deleteTarget.value = null
  router.replace('/kubernetes/config')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Config & Secrets', to: '/kubernetes/config' }, resource?.name || id]"
        :title="resource?.name || id"
      >
        <template #actions>
          <Button size="sm" icon="code" @click="openEdit">Edit YAML</Button>
          <Button size="sm" icon="trash" @click="openDelete">Delete</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!resource" class="empty">Resource not found.</div>

    <template v-else>
      <SectionCard padded glass>
        <template #header><h3 class="card-h">Properties</h3></template>
        <div class="kv">
          <div class="k">Kind</div>
          <div class="v">
            <Badge :color="resource.kind === 'Secret' ? '#a78bff' : '#5ec5ff'">{{ resource.type || resource.kind }}</Badge>
          </div>
          <div class="k">Namespace</div><div class="v mono">{{ resource.ns }}</div>
          <div class="k">Size</div><div class="v mono">{{ resource.size }}</div>
          <div class="k">Age</div><div class="v">{{ resource.age }}</div>
          <div class="k">Managed by</div>
          <div class="v">
            <Badge v-if="resource.managedBy" color="#5ec5ff">{{ resource.managedBy }}</Badge>
            <span v-else class="dim">—</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard padded glass>
        <template #header>
          <div class="card-row">
            <h3 class="card-h">{{ resource.kind === 'Secret' ? 'Encoded keys' : 'Keys' }}</h3>
            <span v-if="resource.kind === 'Secret'" class="muted">Reveal values one at a time. Reveal events are audited.</span>
          </div>
        </template>
        <div class="keys">
          <div v-for="k in resource.keys" :key="k" class="key-card">
            <div class="key-head">
              <span class="mono key-name">{{ k }}</span>
              <span class="spacer" />
              <Button
                v-if="resource.kind === 'Secret'"
                size="sm"
                :icon="revealed[k] ? 'eye' : 'eye'"
                @click="reveal(k)">
                {{ revealed[k] ? 'Hide' : 'Reveal' }}
              </Button>
              <Button size="sm" icon="copy" @click="toast.success(`Copied ${k}`)">Copy</Button>
            </div>
            <pre class="key-val">{{ resource.kind === 'Secret' && !revealed[k] ? masked(valueFor(k)) : valueFor(k) }}</pre>
          </div>
        </div>
      </SectionCard>
    </template>

    <EditResourceYamlModal
      :target="editTarget"
      :accent="accent"
      @close="editTarget = null"
      @applied="editTarget = null" />

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="onDeleted" />
  </PageShell>
</template>

<style scoped>
.empty { padding: 32px; text-align: center; color: var(--fg-3); }
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.card-row { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.muted { color: var(--fg-3); font-size: 12px; }

.kv { display: grid; grid-template-columns: 130px 1fr; row-gap: 8px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }

.keys { display: flex; flex-direction: column; gap: 12px; }
.key-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-2);
  overflow: hidden;
}
.key-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}
.spacer { flex: 1; }
.key-name { font-weight: 500; font-size: 12.5px; }
.key-val {
  margin: 0;
  padding: 12px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--bg-1);
}
</style>
