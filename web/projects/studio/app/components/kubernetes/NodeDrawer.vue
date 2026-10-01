<script setup lang="ts">
import { computed } from 'vue'
import type { K8sNode } from '~/composables/useK8sTypes'

const props = defineProps<{
  node: K8sNode | null
}>()
const emit = defineEmits<{
  close: []
}>()

const { accent } = useCurrentSubsystem()
const { current } = useK8sCluster()

// `useK8sPods` returns a page; we collect cluster-wide pods and then
// filter by spec.nodeName client-side. The pods endpoint doesn't
// accept a node filter today — when it grows one (`useK8sPods({
// cluster, node })`), the client filter can drop out without
// touching this component.
const { data: podsPage, refresh: refreshPods } = useK8sPods({
  cluster: () => current.value?.id,
  limit: 5000,
})

// Live refresh of the pods-on-node list — gated on the drawer being
// open (cluster resolves to undefined when no node is inspected).
useK8sResourceWatch({
  cluster: () => (props.node ? current.value?.id : undefined),
  kinds: ['Pod'],
  onChange: refreshPods,
})

const podsOnNode = computed(() => {
  if (!props.node) return []
  const items = podsPage.value?.items ?? []
  return items.filter(p => p.node === props.node!.name)
})

// Live node CPU + memory % from the metrics-server stream. The
// subscription opens once the drawer has a node and tears down on
// node change / unmount; sample-time freshness lets the bars tick
// while the drawer is open.
const liveNodeNameRef = computed(() => props.node?.name ?? undefined)
const { latest: liveNodeMetrics } = useK8sNodeMetricsStream({
  cluster: () => current.value?.id,
  node: liveNodeNameRef,
})
const liveCpuPct = computed(() => liveNodeMetrics.value?.cpuPercent ?? props.node?.cpu ?? 0)
const liveMemPct = computed(() => liveNodeMetrics.value?.memoryPercent ?? props.node?.mem ?? 0)

const yaml = computed(() => {
  const n = props.node
  if (!n) return ''
  return `apiVersion: v1
kind: Node
metadata:
  name: ${n.name}
  labels:
    node-role.kubernetes.io/${n.role}: ""
    topology.kubernetes.io/zone: ${n.zone}
    node.kubernetes.io/instance-type: ${n.instance}
spec:
  taints:
${n.taints.length ? n.taints.map((t) => {
  const head = t.split(':')[0] ?? ''
  const effect = t.split(':')[1] || 'NoSchedule'
  const key = head.split('=')[0] ?? head
  return `    - key: ${key}\n      effect: ${effect}`
}).join('\n') : '    []'}
status:
  conditions:
    - type: Ready
      status: "${n.status === 'Ready' ? 'True' : 'False'}"
  nodeInfo:
    kubeletVersion: ${n.version}
`
})
</script>

<template>
  <Drawer
    v-if="node"
    :title="node.name"
    icon="database"
    :accent="accent"
    width="640px"
    @close="emit('close')"
  >
    <template v-if="node" #title>
      <span class="mono">{{ node.name }}</span>
    </template>
    <template v-if="node" #subtitle>
      <span class="sub-row">
        <K8sStatusBadge :status="node.status" :pulse="node.status !== 'Ready'" />
        <span class="sub-text">{{ node.role }} · {{ node.instance }} · {{ node.zone }}</span>
      </span>
    </template>

    <template v-if="node">
      <section class="kv">
        <div class="k">Role</div><div class="v">
          <Badge :color="node.role === 'control-plane' ? '#a78bff' : '#5ec5ff'">{{ node.role }}</Badge>
        </div>
        <div class="k">Instance</div><div class="v mono">{{ node.instance }}</div>
        <div class="k">Zone</div><div class="v mono">{{ node.zone }}</div>
        <div class="k">Status</div><div class="v">{{ node.status }}</div>
        <div class="k">Version</div><div class="v mono">{{ node.version }}</div>
        <div class="k">Age</div><div class="v">{{ node.age }}</div>
      </section>

      <section class="usage">
        <h4 class="section-h">Resource usage</h4>
        <div class="usage-row">
          <span class="lbl">CPU</span>
          <K8sUsageBar :pct="liveCpuPct" />
        </div>
        <div class="usage-row">
          <span class="lbl">Memory</span>
          <K8sUsageBar :pct="liveMemPct" />
        </div>
        <div class="usage-row">
          <span class="lbl">Pods</span>
          <span class="mono">{{ node.pods }}</span>
        </div>
      </section>

      <section v-if="node.taints.length">
        <h4 class="section-h">Taints</h4>
        <div class="taints">
          <span v-for="t in node.taints" :key="t" class="taint mono">{{ t }}</span>
        </div>
      </section>

      <section v-if="podsOnNode.length">
        <h4 class="section-h">Pods on this node ({{ podsOnNode.length }})</h4>
        <div class="pods-list">
          <NuxtLink
            v-for="p in podsOnNode"
            :key="p.name"
            :to="`/kubernetes/pods/${encodeURIComponent(p.name)}`"
            class="pod-row"
          >
            <span class="mono name">{{ p.name }}</span>
            <K8sStatusBadge :status="p.status" :pulse="p.status !== 'Running'" />
            <span class="mono dim">{{ p.workload.ns }}/{{ p.workload.name }}</span>
          </NuxtLink>
        </div>
      </section>

      <section>
        <h4 class="section-h">Manifest</h4>
        <CodeEditor
          :model-value="yaml"
          language="text"
          :rows="12"
          readonly />
      </section>
    </template>

    <template v-if="node" #footer>
      <Button @click="emit('close')">Close</Button>
    </template>
  </Drawer>
</template>

<style scoped>
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.sub-row { display: inline-flex; align-items: center; gap: 10px; min-width: 0; }
.sub-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dim { color: var(--fg-3); }

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

.section-h {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}
.usage { margin-bottom: 22px; }
.usage-row {
  display: grid;
  grid-template-columns: 90px 1fr;
  align-items: center;
  gap: 14px;
  padding: 6px 0;
  font-size: 12.5px;
}
.lbl { color: var(--fg-3); }

.taints { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 22px; }
.taint {
  padding: 3px 8px;
  border: 1px solid color-mix(in oklab, var(--warn) 30%, transparent);
  background: color-mix(in oklab, var(--warn) 10%, transparent);
  border-radius: 4px;
  color: var(--warn);
  font-size: 11px;
}

.pods-list {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 22px;
}
.pod-row {
  display: grid;
  grid-template-columns: 1.5fr auto 1fr;
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
