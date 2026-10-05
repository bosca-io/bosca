<script setup lang="ts">
import { computed } from 'vue'
import type { K8sNode } from '~/composables/useK8sTypes'

const props = defineProps<{ nodes: K8sNode[] }>()

interface Cell { node: K8sNode; cls: string }

const cells = computed<Cell[]>(() => props.nodes.map((n) => {
  let cls = 'use-1'
  if (n.cpu > 80 || n.mem > 80) cls = 'use-4'
  else if (n.cpu > 60 || n.mem > 60) cls = 'use-3'
  else if (n.cpu > 40 || n.mem > 40) cls = 'use-2'
  if (n.status === 'DiskPressure' || n.status === 'MemoryPressure') cls += ' warn'
  if (n.status === 'NotReady') cls = 'fail'
  return { node: n, cls }
}))
</script>

<template>
  <div class="fleet">
    <div
      v-for="c in cells"
      :key="c.node.name"
      class="cell"
      :class="c.cls"
      :title="`${c.node.name} · CPU ${c.node.cpu}% · Mem ${c.node.mem}% · ${c.node.status}`"
    />
  </div>
</template>

<style scoped>
.fleet {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(28px, 1fr));
  gap: 4px;
}
.cell {
  aspect-ratio: 1;
  border-radius: 4px;
  background: color-mix(in oklab, var(--brand-2, #326ce5) 12%, var(--bg-2, #14141b));
  border: 1px solid var(--line);
  transition: transform 0.15s;
}
.cell:hover { transform: scale(1.08); }
.cell.use-1 { background: color-mix(in oklab, var(--brand-2, #326ce5) 18%, var(--bg-2, #14141b)); }
.cell.use-2 { background: color-mix(in oklab, var(--brand-2, #326ce5) 38%, var(--bg-2, #14141b)); }
.cell.use-3 { background: color-mix(in oklab, var(--brand-2, #326ce5) 60%, var(--bg-2, #14141b)); }
.cell.use-4 { background: color-mix(in oklab, var(--brand-2, #326ce5) 80%, var(--bg-2, #14141b)); }
.cell.warn { background: color-mix(in oklab, var(--warn, #ffb547) 50%, transparent); border-color: color-mix(in oklab, var(--warn, #ffb547) 60%, transparent); }
.cell.fail { background: color-mix(in oklab, var(--err, #ff5d6c) 60%, transparent); border-color: color-mix(in oklab, var(--err, #ff5d6c) 70%, transparent); }
</style>
