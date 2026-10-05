<script setup lang="ts">
const jobs = [
  { name: 'meilisearch.reindex',   state: 'running', pct: 64,  eta: '2m 04s' },
  { name: 'transcode.video.hls',   state: 'running', pct: 38,  eta: '4m 51s' },
  { name: 'embed.documents',       state: 'queued',  pct: 0,   eta: '—'      },
  { name: 'localize.fr.batch',     state: 'done',    pct: 100, eta: 'ok'     },
  { name: 'webhook.hubspot.sync',  state: 'failed',  pct: 100, eta: 'retry'  },
]

function stateColor(state: string) {
  if (state === 'running') return 'var(--brand-2)'
  if (state === 'done')    return 'var(--ok)'
  if (state === 'failed')  return 'var(--err)'
  return 'var(--fg-3)'
}

function dotClass(state: string) {
  if (state === 'done')    return 'dot ok'
  if (state === 'failed')  return 'dot err'
  if (state === 'running') return 'dot info'
  return 'dot'
}
</script>

<template>
  <div class="jobs-list">
    <div
      v-for="(j, i) in jobs"
      :key="i"
      class="jobs-item"
    >
      <!-- Header row -->
      <div class="jobs-header-row">
        <span
          :class="dotClass(j.state)"
          :style="j.state === 'queued' ? { background: 'var(--fg-3)' } : undefined"
        />
        <span class="jobs-name mono">{{ j.name }}</span>
        <span class="mono" :style="{ fontSize: '10.5px', color: stateColor(j.state) }">{{ j.state }}</span>
        <span class="jobs-eta mono tabular">{{ j.eta }}</span>
      </div>

      <!-- Progress bar -->
      <div class="jobs-progress-track">
        <div
          class="jobs-progress-fill"
          :style="{
            width: `${j.pct}%`,
            background: stateColor(j.state),
            opacity: j.state === 'queued' ? 0 : 1,
          }"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.jobs-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.jobs-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.jobs-header-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.jobs-name {
  font-size: 12px;
  color: var(--fg-0);
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jobs-eta {
  font-size: 10.5px;
  color: var(--fg-3);
  width: 56px;
  text-align: right;
}

.jobs-progress-track {
  height: 4px;
  background: var(--bg-2);
  border-radius: 2px;
  overflow: hidden;
}

.jobs-progress-fill {
  height: 100%;
}
</style>
