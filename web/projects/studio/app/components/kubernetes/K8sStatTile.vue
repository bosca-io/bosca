<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  unit?: string
  delta?: { v: string; dir: 'up' | 'down' } | null
  foot?: string
  spark?: number[]
  sparkColor?: string
}>()
</script>

<template>
  <div class="k8s-stat">
    <div class="label">{{ label }}</div>
    <div class="value">
      <span>{{ value }}</span>
      <span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <div class="foot">
      <span v-if="delta" class="delta" :class="delta.dir">
        <span class="arrow">{{ delta.dir === 'up' ? '↑' : '↓' }}</span>
        {{ delta.v }}
      </span>
      <span v-if="foot" class="foot-text">
        <slot name="foot">{{ foot }}</slot>
      </span>
      <span v-else-if="$slots.foot" class="foot-text"><slot name="foot" /></span>
    </div>
    <div v-if="spark && spark.length" class="spark">
      <Sparkline
        :values="spark"
        :accent="sparkColor || 'var(--brand-2, #326ce5)'"
        :height="34"
        :width="200" />
    </div>
  </div>
</template>

<style scoped>
.k8s-stat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 14px 0;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg-1, #101015);
  overflow: hidden;
  min-height: 110px;
}
.label {
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3, #9b9ba5);
}
.value {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.unit { font-size: 13px; color: var(--fg-3, #9b9ba5); font-weight: 500; }
.foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-2, #c2c2c8);
  margin-top: 2px;
  padding-bottom: 8px;
}
.delta {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 5px;
  border-radius: 4px;
  font-weight: 500;
  font-size: 11.5px;
  background: color-mix(in oklab, var(--ok, #34d99a) 12%, transparent);
  color: var(--ok, #34d99a);
}
.delta.down {
  background: color-mix(in oklab, var(--err, #ff5d6c) 12%, transparent);
  color: var(--err, #ff5d6c);
}
.spark {
  margin-top: auto;
  opacity: 0.85;
  margin-left: -14px;
  margin-right: -14px;
}
</style>
