<script setup lang="ts">
import { computed, ref } from 'vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()
const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })

const { data: namespacesData, status: queryStatus, refresh } = useK8sNamespaces(() => current.value?.id)
// Live refresh — namespace create / phase change / delete re-runs the
// query. The per-namespace workload/pod/service counts also drift as
// resources churn, so watch those kinds too.
useK8sResourceWatch({
  cluster: () => current.value?.id,
  kinds: ['Namespace', 'Pod', 'Service', 'Deployment', 'StatefulSet', 'DaemonSet'],
  onChange: refresh,
})
const cards = computed(() => namespacesData.value ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const createOpen = ref(false)
const createName = ref('')
const createSubmitting = ref(false)

async function confirmCreate() {
  const name = createName.value.trim()
  if (!name) { toast.error('Namespace name is required'); return }
  createSubmitting.value = true
  try {
    const created = await mutations.createNamespace({ name })
    toast.success(`Created namespace ${created.name}`)
    createOpen.value = false
    createName.value = ''
    await refresh()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Create failed')
  } finally {
    createSubmitting.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Namespaces')"
        title="Namespaces"
        :subtitle="`${cards.length} namespaces`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="createOpen = true">New namespace</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && cards.length === 0" class="empty">Loading namespaces…</div>
    <div v-else-if="!cards.length" class="empty">No namespaces in this cluster.</div>
    <div v-else class="grid">
      <SectionCard
        v-for="n in cards"
        :key="n.name"
        padded
        glass
        class="ns-card">
        <div class="row-top">
          <span class="mono name">{{ n.name }}</span>
          <K8sStatusBadge :status="n.status" :pulse="n.status !== 'Active'" />
        </div>
        <div class="stats">
          <div class="stat">
            <div class="num">{{ n.workloads }}</div>
            <div class="label">workloads</div>
          </div>
          <div class="stat">
            <div class="num">{{ n.pods }}</div>
            <div class="label">pods</div>
          </div>
          <div class="stat">
            <div class="num">{{ n.services }}</div>
            <div class="label">services</div>
          </div>
        </div>
      </SectionCard>
    </div>

    <Modal v-if="createOpen" title="Create namespace" @close="createOpen = false">
      <div class="modal-body">
        <label class="field-label">
          Name
          <input
            v-model="createName"
            class="field-input"
            type="text"
            placeholder="my-namespace"
            :disabled="createSubmitting"
            @keydown.enter="confirmCreate"
          >
        </label>
        <p class="field-hint">Must match the kubernetes name rules — lowercase letters, digits, and `-`.</p>
      </div>
      <template #footer>
        <Button size="sm" :disabled="createSubmitting" @click="createOpen = false">Cancel</Button>
        <Button
          size="sm"
          variant="primary"
          :disabled="createSubmitting || !createName.trim()"
          @click="confirmCreate">
          {{ createSubmitting ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.ns-card { padding: 16px; }
.row-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }

.modal-body { padding: 4px 0 12px; }
.field-label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.field-input {
  width: 320px;
  padding: 8px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 14px;
}
.field-input:focus { outline: 2px solid var(--brand); outline-offset: -2px; }
.field-hint { color: var(--fg-3); font-size: 11px; margin: 10px 0 0; }
.name { font-weight: 500; font-size: 13.5px; }
.mono { font-family: var(--font-mono); }
.stats { display: flex; gap: 22px; color: var(--fg-3); font-size: 12px; }
.num { font-family: var(--font-mono); color: var(--fg-0); font-size: 16px; font-weight: 600; font-variant-numeric: tabular-nums; }
.label { margin-top: 2px; font-size: 11px; }
.empty { padding: 40px; text-align: center; color: var(--fg-3); }
</style>
