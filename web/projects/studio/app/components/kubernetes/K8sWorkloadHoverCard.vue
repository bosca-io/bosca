<script setup lang="ts">
import type { K8sWorkload, WorkloadStatus } from '~/composables/useK8sTypes'

defineProps<{ workload: K8sWorkload }>()

const STATUS_LABEL: Record<WorkloadStatus, string> = {
  ok: 'Healthy',
  pending: 'Pending',
  warn: 'Degraded',
  err: 'Failing',
}
</script>

<template>
  <div class="peek">
    <div class="peek-head">
      <span class="kind">{{ workload.kind }}</span>
      <K8sStatusBadge
        :status="STATUS_LABEL[workload.status] ?? workload.status"
        :pulse="workload.status !== 'ok'"
      />
    </div>
    <div class="name mono">{{ workload.name }}</div>
    <div class="ns mono">ns/{{ workload.ns }}</div>
    <div class="facts">
      <div class="fact">
        <span class="k">Replicas</span>
        <span class="v mono">{{ workload.replicas }}</span>
      </div>
      <div class="fact">
        <span class="k">Restarts</span>
        <span class="v mono">{{ workload.restarts }}</span>
      </div>
      <div class="fact">
        <span class="k">CPU</span>
        <span class="v mono">{{ workload.cpu }}</span>
      </div>
      <div class="fact">
        <span class="k">Memory</span>
        <span class="v mono">{{ workload.mem }}Gi</span>
      </div>
      <div class="fact">
        <span class="k">Age</span>
        <span class="v mono">{{ workload.age }}</span>
      </div>
      <div class="fact">
        <span class="k">Strategy</span>
        <span class="v">{{ workload.strategy }}</span>
      </div>
    </div>
    <div v-if="workload.image && workload.image !== '—'" class="image mono">
      {{ workload.image }}
    </div>
  </div>
</template>

<style scoped>
.peek {
  width: 300px;
  max-width: 100%;
}
.peek-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.kind {
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
  color: var(--fg-3);
}
.mono { font-family: var(--mono, ui-monospace, monospace); }
.name {
  font-size: 13px;
  font-weight: 600;
  overflow-wrap: anywhere;
  line-height: 1.35;
}
.ns {
  font-size: 11.5px;
  color: var(--fg-3);
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.facts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 14px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}
.fact {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.fact .k { font-size: 11px; color: var(--fg-3); }
.fact .v { font-size: 11.5px; font-variant-numeric: tabular-nums; }
.image {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  font-size: 11px;
  color: var(--fg-2);
  overflow-wrap: anywhere;
  line-height: 1.4;
}
</style>
