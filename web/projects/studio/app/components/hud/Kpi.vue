<script setup lang="ts">
const props = withDefaults(defineProps<{
  label: string
  value: string
  delta: string
  sub?: string
  tone?: string
  spark?: number[]
}>(), {
  sub: undefined,
  tone: 'info',
  spark: () => [],
})

const toneColor = computed(() => {
  return props.tone === 'ok'
    ? 'var(--ok)'
    : props.tone === 'warn'
      ? 'var(--warn)'
      : props.tone === 'err'
        ? 'var(--err)'
        : 'var(--info)'
})

const sparkData = computed(() => props.spark ?? [])

const sparkPts = computed(() => {
  const data = sparkData.value
  if (!data || data.length < 2) return ''
  const sw = 220, sh = 28
  const max = Math.max(...data)
  const min = Math.min(...data)
  return data
    .map((v, i) => `${(i / (data.length - 1)) * sw},${sh - ((v - min) / Math.max(1, max - min)) * (sh - 2) - 1}`)
    .join(' ')
})

const gradientId = computed(() => `spk-${toneColor.value.replace(/[^a-z0-9]/gi, '')}`)
</script>

<template>
  <div class="kpi-card">
    <!-- Label -->
    <div class="kpi-label">
      {{ label }}
    </div>

    <!-- Value + Delta -->
    <div class="kpi-value-row">
      <span class="kpi-value tabular">{{ value }}</span>
      <span class="kpi-delta mono" :style="{ color: toneColor }">{{ delta }}</span>
    </div>

    <!-- Sub -->
    <div class="kpi-sub">{{ sub }}</div>

    <!-- Sparkline bleeding to edges -->
    <div class="kpi-sparkline-wrap">
      <svg
        viewBox="0 0 220 28"
        preserveAspectRatio="none"
        class="kpi-sparkline-svg"
      >
        <defs>
          <linearGradient
            :id="gradientId"
            x1="0"
            y1="0"
            x2="0"
            y2="1">
            <stop offset="0" :stop-color="toneColor" stop-opacity="0.32" />
            <stop offset="1" :stop-color="toneColor" stop-opacity="0" />
          </linearGradient>
        </defs>
        <polyline
          v-if="sparkPts"
          :points="`0,28 ${sparkPts} 220,28`"
          :fill="`url(#${gradientId})`"
        />
        <polyline
          v-if="sparkPts"
          :points="sparkPts"
          fill="none"
          :stroke="toneColor"
          stroke-width="1.2"
        />
      </svg>
    </div>
  </div>
</template>

<style scoped>
.kpi-card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 12px 14px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
  overflow: hidden;
}

.kpi-label {
  font-size: 11px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: .08em;
  font-weight: 600;
}

.kpi-value-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.kpi-value {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.1;
}

.kpi-delta {
  font-size: 11.5px;
  font-weight: 600;
}

.kpi-sub {
  font-size: 11.5px;
  color: var(--fg-3);
  margin-bottom: 8px;
}

.kpi-sparkline-wrap {
  margin: 0 -14px;
}

.kpi-sparkline-svg {
  display: block;
  height: 28px;
  width: 100%;
  opacity: .9;
  pointer-events: none;
}
</style>
