<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value: string
  sub?: string
  accent?: string
  /** Value text size. Use 'sm' for textual values (e.g. "Connected"); 'lg' (default) suits short numbers. */
  valueSize?: 'sm' | 'lg'
}>(), {
  valueSize: 'lg',
})
</script>

<template>
  <div class="stat-tile">
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value tabular" :class="`size-${valueSize}`">{{ value }}</div>
    <div v-if="sub" class="stat-sub" :style="{ color: accent || 'var(--fg-3)' }">{{ sub }}</div>
  </div>
</template>

<style scoped>
.stat-tile {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
}

.stat-label {
  font-size: 11px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
}

.stat-value {
  font-weight: 600;
  margin-top: 6px;
  letter-spacing: -0.02em;
}

.stat-value.size-lg { font-size: 28px; }
.stat-value.size-sm { font-size: 25px; }

.stat-sub {
  font-size: 12px;
  margin-top: 4px;
}
</style>
