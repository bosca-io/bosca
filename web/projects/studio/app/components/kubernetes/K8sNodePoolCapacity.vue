<script setup lang="ts">
import { computed } from 'vue'
import type { K8sNode } from '~/composables/useK8sTypes'

const props = defineProps<{ nodes: K8sNode[] }>()

// Well-known labels that name the node pool / node group a node belongs
// to, in priority order. The first one present on a node identifies its
// pool — this is how Karpenter and the managed providers expose pool
// membership. When none are present (kubeadm, kind, bare-metal) we fall
// back to the node's role so the card still groups meaningfully instead
// of lumping everything into one bucket.
const POOL_LABEL_KEYS = [
  'karpenter.sh/nodepool',
  'eks.amazonaws.com/nodegroup',
  'cloud.google.com/gke-nodepool',
  'kubernetes.azure.com/agentpool',
  'agentpool',
]

interface Pool {
  name: string
  instances: string[]
  nodes: K8sNode[]
  hasControlPlane: boolean
}

function poolName(n: K8sNode): string {
  const labels = n.labels ?? {}
  for (const key of POOL_LABEL_KEYS) {
    const value = labels[key]
    if (value) return value
  }
  return n.role || 'unlabeled'
}

const pools = computed<Pool[]>(() => {
  const byName = new Map<string, Pool>()
  for (const node of props.nodes) {
    const name = poolName(node)
    let pool = byName.get(name)
    if (!pool) {
      pool = { name, instances: [], nodes: [], hasControlPlane: false }
      byName.set(name, pool)
    }
    pool.nodes.push(node)
    if (node.role === 'control-plane') pool.hasControlPlane = true
    if (node.instance && !pool.instances.includes(node.instance)) pool.instances.push(node.instance)
  }
  // Control-plane pools first, then larger pools, then alphabetical —
  // a stable order so the card doesn't reshuffle on every metrics tick.
  return Array.from(byName.values()).sort((a, b) => {
    if (a.hasControlPlane !== b.hasControlPlane) return a.hasControlPlane ? -1 : 1
    if (b.nodes.length !== a.nodes.length) return b.nodes.length - a.nodes.length
    return a.name.localeCompare(b.name)
  })
})

function avgCpu(p: Pool): number {
  if (!p.nodes.length) return 0
  return Math.round(p.nodes.reduce((s, n) => s + n.cpu, 0) / p.nodes.length)
}
function instanceLabel(p: Pool): string {
  if (!p.instances.length) return '—'
  if (p.instances.length === 1) return p.instances[0] ?? '—'
  return `${p.instances.length} instance types`
}
function color(avg: number): string {
  if (avg > 80) return 'var(--err, #ff5d6c)'
  if (avg > 60) return 'var(--warn, #ffb547)'
  return 'color-mix(in oklab, var(--brand-2, #326ce5) 70%, transparent)'
}
</script>

<template>
  <div v-if="pools.length" class="pools">
    <div v-for="p in pools" :key="p.name" class="row">
      <div class="label" :title="p.name">{{ p.name }}</div>
      <div class="bar">
        <div class="seg" :style="{ width: `${avgCpu(p)}%`, background: color(avgCpu(p)) }" />
        <div class="caption">{{ instanceLabel(p) }}</div>
      </div>
      <div class="val">{{ p.nodes.length }}× · {{ avgCpu(p) }}%</div>
    </div>
  </div>
  <div v-else class="empty">No nodes observed in this cluster.</div>
</template>

<style scoped>
.pools { display: flex; flex-direction: column; gap: 4px; }
.row {
  display: grid;
  grid-template-columns: 100px 1fr 80px;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
}
.label {
  font-size: 12.5px;
  color: var(--fg-2, #c2c2c8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar {
  position: relative;
  height: 18px;
  background: var(--bg-2, #14141b);
  border: 1px solid var(--line);
  border-radius: 4px;
  overflow: hidden;
}
.seg {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: 4px;
  transition: width 0.4s;
}
.caption {
  position: absolute;
  left: 8px;
  top: 0;
  bottom: 0;
  display: grid;
  align-items: center;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  color: var(--fg-3, #9b9ba5);
}
.val {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
  color: var(--fg-2, #c2c2c8);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.empty { color: var(--fg-3, #9b9ba5); font-size: 12.5px; padding: 16px 4px; text-align: center; }
</style>
