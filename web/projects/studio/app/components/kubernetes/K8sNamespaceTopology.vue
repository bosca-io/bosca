<script setup lang="ts">
import type { K8sNamespaceTopology, K8sWorkload } from '~/composables/useK8sTypes'

const props = defineProps<{
  topology: K8sNamespaceTopology[]
  workloads: K8sWorkload[]
}>()
const emit = defineEmits<{ pick: [workload: K8sWorkload] }>()

function workloadStatus(svc: { pods: { s: string }[] }): 'Healthy' | 'Degraded' | 'Failing' {
  if (svc.pods.some(p => p.s === 'err')) return 'Failing'
  if (svc.pods.every(p => p.s === 'ok')) return 'Healthy'
  return 'Degraded'
}

function findWorkload(name: string): K8sWorkload | undefined {
  return props.workloads.find(w => w.name === name)
}
</script>

<template>
  <div class="topo-grid">
    <div v-for="g in topology" :key="g.ns" class="group">
      <div class="head">
        <span class="mono">ns/{{ g.ns }}</span>
        <span class="muted">{{ g.svcs.length }} workloads</span>
      </div>
      <div class="services">
        <div
          v-for="s in g.svcs"
          :key="s.name"
          class="service"
          :class="{ clickable: !!findWorkload(s.name) }"
          @click="findWorkload(s.name) && emit('pick', findWorkload(s.name)!)"
        >
          <div class="title">
            <span class="name">{{ s.name }}</span>
            <K8sStatusBadge :status="workloadStatus(s)" :pulse="workloadStatus(s) !== 'Healthy'" />
          </div>
          <div class="pods">
            <span
              v-for="(p, i) in s.pods"
              :key="i"
              class="pod"
              :class="{ err: p.s === 'err', warn: p.s === 'warn' }"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}
.group { min-width: 0; }
.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
  gap: 8px;
}
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; color: var(--fg-2, #c2c2c8); }
.muted { font-size: 11px; color: var(--fg-3, #9b9ba5); }
.services { display: flex; flex-direction: column; gap: 6px; }
.service {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-2, #14141b);
  transition: border-color 0.15s, background 0.15s;
}
.service.clickable { cursor: pointer; }
.service.clickable:hover { border-color: var(--line-2); background: var(--bg-3, #181820); }
.title { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.name { flex: 1; font-weight: 500; font-size: 12.5px; }
.pods { display: flex; gap: 3px; flex-wrap: wrap; }
.pod {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: var(--ok, #34d99a);
  opacity: 0.85;
}
.pod.warn { background: var(--warn, #ffb547); }
.pod.err { background: var(--err, #ff5d6c); }
</style>
