<script setup lang="ts">
const states = [
  { id: 'draft',     label: 'Draft',       count: 92,  color: '#6c7388' },
  { id: 'review',    label: 'In Review',   count: 41,  color: '#5ec5ff' },
  { id: 'translate', label: 'Translation', count: 24,  color: '#ffb547' },
  { id: 'sched',     label: 'Scheduled',   count: 19,  color: '#a78bff' },
  { id: 'pub',       label: 'Published',   count: 146, color: '#34d99a' },
  { id: 'archived',  label: 'Archived',    count: 312, color: '#454b60' },
]

const total = states.reduce((a, b) => a + b.count, 0)
</script>

<template>
  <div class="workflow-states">
    <!-- Stacked bar -->
    <div class="workflow-bar">
      <div
        v-for="s in states"
        :key="s.id"
        :title="s.label"
        :style="{ flex: s.count, background: s.color }"
      />
    </div>

    <!-- State list -->
    <div class="workflow-list">
      <div
        v-for="s in states"
        :key="s.id"
        class="workflow-list-item"
      >
        <span class="workflow-swatch" :style="{ background: s.color }" />
        <span class="workflow-label">{{ s.label }}</span>
        <span class="workflow-pct tabular">{{ (s.count / total * 100).toFixed(1) }}%</span>
        <span class="workflow-count tabular">{{ s.count }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workflow-states {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.workflow-bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--bg-2);
}

.workflow-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.workflow-list-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
}

.workflow-swatch {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.workflow-label {
  font-size: 12.5px;
  color: var(--fg-1);
  flex: 1;
}

.workflow-pct {
  font-size: 12px;
  color: var(--fg-3);
}

.workflow-count {
  font-size: 13px;
  color: var(--fg-0);
  font-weight: 600;
  width: 38px;
  text-align: right;
}
</style>
