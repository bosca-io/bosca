<script setup lang="ts">
const stages = [
  { id: 'Inbox',     count: 38,  pct: 100 },
  { id: 'Drafting',  count: 92,  pct: 80,  highlight: true },
  { id: 'Review',    count: 41,  pct: 60 },
  { id: 'Localize',  count: 24,  pct: 50 },
  { id: 'Schedule',  count: 19,  pct: 45 },
  { id: 'Published', count: 146, pct: 100, terminal: true },
]

const days = 30
const pub = Array.from({ length: days }, (_, i) => 4 + Math.sin(i / 3) * 2 + (i / days) * 5 + (i % 4 === 0 ? 2 : 0))
const drafted = Array.from({ length: days }, (_, i) => 6 + Math.cos(i / 4) * 3 + (i / days) * 4 + (i % 5 === 0 ? 3 : 0))
const chartMax = Math.max(...pub, ...drafted) + 2
const cw = 720, ch = 130

function xy(arr: number[]) {
  return arr.map((v, i) => `${(i / (days - 1)) * cw},${ch - (v / chartMax) * (ch - 6) - 4}`).join(' ')
}

const pubPts = xy(pub)
const draftedPts = xy(drafted)

function stageBarBg(s: typeof stages[0]) {
  if (s.terminal) return 'linear-gradient(135deg, color-mix(in oklch, var(--ok) 28%, var(--bg-2)), color-mix(in oklch, var(--ok) 16%, var(--bg-2)))'
  if (s.highlight) return 'linear-gradient(135deg, color-mix(in oklch, var(--brand-2) 32%, var(--bg-2)), color-mix(in oklch, var(--brand-1) 18%, var(--bg-2)))'
  return 'var(--bg-2)'
}

function stageBarBorder(s: typeof stages[0]) {
  if (s.highlight) return '1px solid color-mix(in oklch, var(--brand-2) 32%, transparent)'
  if (s.terminal)  return '1px solid color-mix(in oklch, var(--ok) 28%, transparent)'
  return '1px solid var(--line)'
}

function stageFillBg(s: typeof stages[0]) {
  if (s.terminal) return 'color-mix(in oklch, var(--ok) 25%, transparent)'
  return 'color-mix(in oklch, var(--brand-1) 16%, transparent)'
}
</script>

<template>
  <div class="pipeline-flow">

    <!-- Pipeline stages row -->
    <div class="pipeline-stages">
      <template v-for="(s, i) in stages" :key="s.id">
        <div class="pipeline-stage">
          <!-- Stage header: name left, count right -->
          <div class="pipeline-stage-header">
            <span>{{ s.id }}</span>
            <span
              class="tabular"
              :style="{
                color: s.highlight ? 'var(--fg-0)' : 'var(--fg-1)',
                fontSize: '14px',
                fontWeight: 600,
              }"
            >{{ s.count }}</span>
          </div>

          <!-- Stage bar -->
          <div
            class="pipeline-stage-bar"
            :style="{
              background: stageBarBg(s),
              border: stageBarBorder(s),
            }"
          >
            <div
              class="pipeline-stage-fill"
              :style="{
                width: `${s.pct}%`,
                background: stageFillBg(s),
              }"
            />
          </div>
        </div>

        <!-- Chevron between stages -->
        <div v-if="i < stages.length - 1" class="pipeline-chevron">
          <Icon name="chevron" :size="12" color="var(--fg-4)" />
        </div>
      </template>
    </div>

    <!-- Throughput chart -->
    <div class="pipeline-throughput">
      <!-- Legend row -->
      <div class="pipeline-legend">
        <!-- Drafted legend -->
        <div class="pipeline-legend-item">
          <span class="pipeline-legend-swatch pipeline-legend-swatch--drafted" />
          <span class="pipeline-legend-label">Drafted</span>
          <span class="pipeline-legend-value tabular">247</span>
        </div>
        <!-- Published legend -->
        <div class="pipeline-legend-item">
          <span class="pipeline-legend-swatch pipeline-legend-swatch--published" />
          <span class="pipeline-legend-label">Published</span>
          <span class="pipeline-legend-value tabular">146</span>
        </div>
        <span class="spacer" />
        <span class="pipeline-legend-period mono">30d · daily</span>
      </div>

      <!-- SVG area chart -->
      <svg
        :viewBox="`0 0 ${cw} ${ch}`"
        preserveAspectRatio="none"
        class="pipeline-chart-svg"
      >
        <defs>
          <linearGradient
            id="th-1"
            x1="0"
            y1="0"
            x2="0"
            y2="1">
            <stop offset="0" stop-color="var(--brand-2)" stop-opacity="0.35" />
            <stop offset="1" stop-color="var(--brand-2)" stop-opacity="0" />
          </linearGradient>
          <linearGradient
            id="th-2"
            x1="0"
            y1="0"
            x2="0"
            y2="1">
            <stop offset="0" stop-color="var(--ok)" stop-opacity="0.32" />
            <stop offset="1" stop-color="var(--ok)" stop-opacity="0" />
          </linearGradient>
        </defs>

        <!-- Grid lines -->
        <line
          v-for="p in [0.25, 0.5, 0.75]"
          :key="p"
          x1="0"
          :x2="cw"
          :y1="ch * p"
          :y2="ch * p"
          stroke="var(--line)"
          stroke-dasharray="2 4"
        />

        <!-- Drafted area + line -->
        <polyline :points="`0,${ch} ${draftedPts} ${cw},${ch}`" fill="url(#th-1)" />
        <polyline
          :points="draftedPts"
          fill="none"
          stroke="var(--brand-2)"
          stroke-width="1.5" />

        <!-- Published area + line -->
        <polyline :points="`0,${ch} ${pubPts} ${cw},${ch}`" fill="url(#th-2)" />
        <polyline
          :points="pubPts"
          fill="none"
          stroke="var(--ok)"
          stroke-width="1.5" />
      </svg>
    </div>

  </div>
</template>

<style scoped>
.pipeline-flow {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pipeline-stages {
  display: flex;
  gap: 8px;
}

.pipeline-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.pipeline-stage-header {
  font-size: 11px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: .08em;
  font-weight: 600;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.pipeline-stage-bar {
  height: 38px;
  border-radius: var(--r-sm);
  position: relative;
  overflow: hidden;
}

.pipeline-stage-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
}

.pipeline-chevron {
  display: flex;
  align-items: center;
  padding-top: 22px;
  color: var(--fg-4);
}

.pipeline-throughput {
  margin-top: 4px;
  padding: 10px 4px 0;
  border-top: 1px dashed var(--line);
}

.pipeline-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 6px;
}

.pipeline-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.pipeline-legend-swatch {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.pipeline-legend-swatch--drafted {
  background: var(--brand-2);
}

.pipeline-legend-swatch--published {
  background: var(--ok);
}

.pipeline-legend-label {
  font-size: 11.5px;
  color: var(--fg-2);
}

.pipeline-legend-value {
  font-size: 12px;
  color: var(--fg-0);
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.pipeline-legend-period {
  font-size: 10.5px;
  color: var(--fg-3);
}

.pipeline-chart-svg {
  width: 100%;
  height: 130px;
}
</style>
