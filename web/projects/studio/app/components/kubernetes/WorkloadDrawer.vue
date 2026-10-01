<script setup lang="ts">
import { computed } from 'vue'
import type { K8sWorkload } from '~/composables/useK8sTypes'

const props = defineProps<{
  workload: K8sWorkload | null
}>()
const emit = defineEmits<{
  close: []
  scale: [w: K8sWorkload]
  restart: [w: K8sWorkload]
  delete: [w: K8sWorkload]
}>()

const { accent } = useCurrentSubsystem()
const { current } = useK8sCluster()

// Pods backing a workload come from the live cluster pod list,
// narrowed by `workloadId`. The query short-circuits while the
// workload is null (drawer closed) so we don't fire an unbounded
// pod list when nothing is being inspected.
const { data: podsPage, refresh: refreshPods } = useK8sPods({
  cluster: () => current.value?.id,
  workloadId: () => props.workload?.id,
})

// Both streams resolve `cluster` to undefined while the drawer is
// closed (workload null), which the composables treat as "don't
// open" — so the sockets only live while something is inspected.
// The metrics stream keeps each pod row's CPU + memory ticking; the
// pod watch re-runs the pods query on add / status change / delete.
const drawerClusterRef = computed(() => (props.workload ? current.value?.id : undefined))
const { byKey: livePodByKey } = useK8sPodsListMetricsStream({
  cluster: drawerClusterRef,
  namespace: () => props.workload?.ns,
})
useK8sResourceWatch({
  cluster: drawerClusterRef,
  namespace: () => props.workload?.ns,
  kinds: ['Pod'],
  onChange: refreshPods,
})

const pods = computed(() => (podsPage.value?.items ?? []).map((p) => {
  const live = livePodByKey.value[`${p.workload.ns}/${p.name}`]
  if (!live) return p
  return { ...p, cpu: live.cpuMillicores, mem: Math.round(live.memoryBytes / (1024 * 1024)) }
}))

const statusLabel: Record<K8sWorkload['status'], string> = { ok: 'Healthy', pending: 'Pending', warn: 'Degraded', err: 'Failing' }

const yaml = computed(() => {
  const w = props.workload
  if (!w) return ''
  return `apiVersion: apps/v1
kind: ${w.kind}
metadata:
  name: ${w.name}
  namespace: ${w.ns}
spec:
  replicas: ${w.want}
  strategy:
    type: ${w.strategy}
  template:
    spec:
      containers:
        - name: server
          image: ${w.image}
`
})
</script>

<template>
  <Drawer
    v-if="workload"
    :title="workload.name"
    icon="boxes"
    :accent="accent"
    width="640px"
    @close="emit('close')"
  >
    <template v-if="workload" #subtitle>
      <span class="sub-row">
        <K8sStatusBadge :status="statusLabel[workload.status]" :pulse="workload.status !== 'ok'" />
        <span class="sub-text">{{ workload.kind }} · {{ workload.ns }}</span>
      </span>
    </template>

    <template v-if="workload" #actions>
      <Button size="sm" icon="refresh" @click="emit('restart', workload)">Restart</Button>
      <Button size="sm" icon="size" @click="emit('scale', workload)">Scale</Button>
      <NuxtLink :to="`/kubernetes/workloads/${workload.id}`">
        <Button size="sm" icon="externalLink">Full page</Button>
      </NuxtLink>
    </template>

    <template v-if="workload">
      <section class="kv">
        <div class="k">Image</div><div class="v mono break">{{ workload.image }}</div>
        <div class="k">Strategy</div><div class="v">{{ workload.strategy }}</div>
        <div class="k">Replicas</div><div class="v mono">{{ workload.replicas }}</div>
        <div class="k">CPU</div><div class="v mono">{{ workload.cpu }} cores</div>
        <div class="k">Memory</div><div class="v mono">{{ workload.mem }} GiB</div>
        <div class="k">Restarts</div><div class="v mono">{{ workload.restarts }}</div>
        <div class="k">Age</div><div class="v">{{ workload.age }}</div>
      </section>

      <section v-if="pods.length" class="pods-section">
        <h4 class="section-h">Pods ({{ pods.length }})</h4>
        <div class="pods-list">
          <NuxtLink
            v-for="p in pods"
            :key="p.name"
            :to="`/kubernetes/pods/${encodeURIComponent(p.name)}`"
            class="pod-row"
          >
            <span class="mono name">{{ p.name }}</span>
            <K8sStatusBadge :status="p.status" :pulse="p.status !== 'Running'" />
            <span class="mono dim">CPU {{ p.cpu }}m · Mem {{ p.mem }}Mi</span>
            <span class="mono dim">{{ p.age }}</span>
          </NuxtLink>
        </div>
      </section>

      <section>
        <h4 class="section-h">Manifest</h4>
        <CodeEditor
          :model-value="yaml"
          language="text"
          :rows="14"
          readonly />
      </section>
    </template>

    <template v-if="workload" #footer>
      <Button @click="emit('close')">Close</Button>
      <Button icon="trash" @click="emit('delete', workload)">Delete</Button>
    </template>
  </Drawer>
</template>

<style scoped>
.sub-row { display: inline-flex; align-items: center; gap: 10px; min-width: 0; }
.sub-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.kv {
  display: grid;
  grid-template-columns: 110px 1fr;
  row-gap: 8px;
  column-gap: 14px;
  font-size: 12.5px;
  margin-bottom: 22px;
}
.k { color: var(--fg-3); font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.break { word-break: break-all; }
.dim { color: var(--fg-3); }

.section-h {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}
.pods-section { margin-bottom: 22px; }
.pods-list {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
}
.pod-row {
  display: grid;
  grid-template-columns: 1.4fr auto 1fr auto;
  gap: 10px;
  align-items: center;
  padding: 8px 10px;
  font-size: 12px;
  color: inherit;
  text-decoration: none;
}
.pod-row + .pod-row { border-top: 1px solid var(--line); }
.pod-row:hover { background: var(--bg-2); }
.pod-row .name { font-weight: 500; }
</style>
