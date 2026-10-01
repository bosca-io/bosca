<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sCertificate } from '~/composables/useK8sTypes'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const id = computed(() => route.params.name as string)
const clusterId = computed(() => current.value?.id)
const { data: certsData, refresh: refreshCerts } = useK8sCertificates({ cluster: clusterId })
const cert = computed<K8sCertificate | undefined>(
  () => (certsData.value ?? []).find(c => c.name === id.value),
)

// Live refresh — issuance / renewal / readiness transitions on the
// certificate re-run the query.
useK8sResourceWatch({
  cluster: clusterId,
  namespace: () => cert.value?.ns,
  kinds: ['Certificate'],
  onChange: refreshCerts,
})

const deleteTarget = ref<DeleteResourceTarget | null>(null)
function openDelete() {
  const c = cert.value
  if (!c) return
  deleteTarget.value = {
    displayKind: 'Certificate',
    kind: 'Certificate',
    name: c.name,
    namespace: c.ns,
    group: 'cert-manager.io',
  }
}
function onDeleted() {
  deleteTarget.value = null
  router.replace('/kubernetes/certs')
}

const conditions = computed(() => cert.value
  ? cert.value.status === 'Failed'
    ? [
        { type: 'Issuing', status: false, reason: 'Failed', message: cert.value.error || 'Unknown error', at: '12m ago' },
        { type: 'Ready', status: false, reason: 'IssuanceFailed', message: 'No valid certificate', at: '12m ago' },
      ]
    : [
        { type: 'Issuing', status: false, reason: 'Renewed', message: 'Certificate is up to date', at: cert.value.readySince },
        { type: 'Ready', status: true, reason: 'Ready', message: 'Certificate is valid', at: cert.value.readySince },
      ]
  : []
)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'cert-manager', to: '/kubernetes/certs' }, cert?.name || id]"
        :title="cert?.name || id"
      >
        <template v-if="cert" #subtitle>
          <K8sStatusBadge :status="cert.status" :pulse="cert.status !== 'Ready'" />
          <span class="sub-text">{{ cert.ns }} · issued by {{ cert.issuer }}</span>
        </template>
        <template #actions>
          <Button size="sm" icon="trash" @click="openDelete">Delete</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!cert" class="empty">Certificate not found.</div>

    <template v-else>
      <div class="grid">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Certificate</h3></template>
          <div class="kv">
            <div class="k">Namespace</div><div class="v mono">{{ cert.ns }}</div>
            <div class="k">Issuer</div><div class="v mono">{{ cert.issuer }}</div>
            <div class="k">Secret</div><div class="v mono">{{ cert.secretName }}</div>
            <div class="k">Status</div><div class="v">{{ cert.status }}</div>
            <div class="k">Ready since</div><div class="v">{{ cert.readySince }}</div>
            <div class="k">Expires</div>
            <div class="v">
              <span :style="{ color: cert.expires < 14 ? 'var(--err)' : cert.expires < 30 ? 'var(--warn)' : 'inherit' }">{{ cert.expires }} days</span>
            </div>
            <div class="k">Renews in</div>
            <div class="v">{{ cert.renewsIn < 0 ? 'Not scheduled' : `${cert.renewsIn} days` }}</div>
          </div>
        </SectionCard>

        <SectionCard padded glass>
          <template #header><h3 class="card-h">DNS names</h3></template>
          <div class="dns">
            <div v-for="d in cert.dns" :key="d" class="dns-row mono">
              <Icon name="globe" :size="13" color="var(--fg-3)" /> {{ d }}
            </div>
          </div>
        </SectionCard>
      </div>

      <SectionCard padded glass>
        <template #header><h3 class="card-h">Conditions</h3></template>
        <div class="conds">
          <div
            v-for="c in conditions"
            :key="c.type"
            class="cond"
            :class="{ ok: c.status, fail: !c.status && c.type === 'Ready' }">
            <Icon :name="c.status ? 'check' : 'alert'" :size="14" :color="c.status ? 'var(--ok)' : 'var(--err)'" />
            <div class="cond-body">
              <div class="cond-head">
                <span class="cond-type">{{ c.type }}</span>
                <Badge :color="c.status ? '#34d99a' : '#ff5d6c'">{{ c.reason }}</Badge>
                <span class="dim small">{{ c.at }}</span>
              </div>
              <div class="cond-msg">{{ c.message }}</div>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        v-if="cert.error"
        padded
        glass
        class="err-card">
        <template #header>
          <h3 class="card-h" style="color: var(--err)">Error</h3>
        </template>
        <pre class="err">{{ cert.error }}</pre>
      </SectionCard>
    </template>

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="onDeleted" />
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }
.empty { padding: 32px; text-align: center; color: var(--fg-3); }
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
.kv { display: grid; grid-template-columns: 130px 1fr; row-gap: 8px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.small { font-size: 11px; }

.dns { display: flex; flex-direction: column; gap: 6px; }
.dns-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 12.5px;
}

.conds { display: flex; flex-direction: column; gap: 10px; }
.cond {
  display: flex; gap: 12px;
  padding: 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
}
.cond.fail { border-color: color-mix(in oklab, var(--err) 30%, transparent); }
.cond-body { flex: 1; }
.cond-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.cond-type { font-weight: 600; font-size: 12.5px; }
.cond-msg { font-size: 12px; color: var(--fg-2); }

.err-card { border-color: color-mix(in oklab, var(--err) 30%, transparent); }
.err {
  margin: 0;
  padding: 10px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--err);
  white-space: pre-wrap;
}
</style>
