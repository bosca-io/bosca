<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Tabs } from '@bosca/ui'
import type { GoalActivation, GoalPopulationAnalysis, MetricInterval, PlannedComparison } from '~/utils/goalActivation'
const props = withDefaults(defineProps<{
  rows: { variationKey: string; assignments: number; goalAnalysis?: GoalPopulationAnalysis | null }[]
  comparisons?: PlannedComparison[]
  metricType: string
  activation?: GoalActivation | null
  accent?: string
  cuped?: boolean
}>(), { comparisons: () => [], activation: null, accent: 'var(--brand-2)', cuped: false })
const segment = ref<'overall' | 'activated' | 'notActivated'>('overall')
const funnelMode = ref<'counts' | 'percentages'>('counts')
const rows = computed(() => props.rows.flatMap(r => r.goalAnalysis ? [{ ...r, analysis: r.goalAnalysis }] : []))
const activeRows = computed(() => rows.value.filter(r => r.analysis.activationConfigured))
const hasPopulations = computed(() => rows.value.some(r => r.analysis.activationConfigured || r.analysis.notApplicable))
const largestEligible = computed(() => Math.max(1, ...rows.value.map(r => r.analysis.overall.subjects)))
const cutoff = computed(() => rows.value[0]?.analysis.cutoff ?? null)
const cutoffLabel = computed(() => {
  const date = cutoff.value ? new Date(cutoff.value) : null
  return date && !Number.isNaN(date.getTime()) ? date.toLocaleString() : cutoff.value
})

const views = computed(() => [
  'Comparison',
  ...hasPopulations.value ? ['Populations', 'Funnel'] : [],
  ...hasPopulations.value && props.metricType === 'UNIQUE_CONVERSION' ? ['Reach'] : [],
  ...hasPopulations.value ? ['Placement', 'Exposure'] : [],
  ...props.metricType !== 'UNIQUE_CONVERSION' ? ['Distribution'] : [],
  'Data quality',
])
const view = ref('Comparison')
watch(views, (available) => {
  if (!available.includes(view.value)) view.value = 'Comparison'
})

function eventDescription(key: string): string {
  const rule = props.activation?.variationRules.find(r => r.variationKey === key)
  if (rule?.mode === 'NOT_APPLICABLE') return 'No exposure stage for this variation.'
  const f = rule?.mode === 'EVENT' ? rule.filter : props.activation?.defaultFilter
  if (!f) return 'Exposure follows the configured goal activation event.'
  return [f.eventType, f.elementType, f.elementId, f.elementIdContains && `ID contains ${f.elementIdContains}`,
    f.pagePath, ...f.pagePathPrefixes ?? [], f.itemExtraKey && `${f.itemExtraKey}: ${f.itemExtraValue ?? 'present'}`,
    f.recommendationCard && 'card with content target, at least 50% visible for one second',
    `applications: ${(f.applicationIds?.length ? f.applicationIds : props.activation?.applicationIds)?.join(', ') || 'captured eligibility application'}`].filter(Boolean).join('; ')
}
const binary = computed(() => props.metricType === 'UNIQUE_CONVERSION')
const display = (v: number | null | undefined) => v == null ? '—' : v.toLocaleString(undefined, { maximumFractionDigits: 3 })
const percent = (v: number | null | undefined) => v == null ? '—' : `${(v * 100).toFixed(2)}%`
const estimate = (v: number | null | undefined) => binary.value ? percent(v) : display(v)
const interval = (v: MetricInterval | null | undefined) => v ? `${display(v.lower)} to ${display(v.upper)}` : '—'
/** Rate intervals share their estimate's percent scale. */
const rateInterval = (v: MetricInterval | null | undefined) => v ? `${percent(v.lower)} to ${percent(v.upper)}` : '—'
const estimateInterval = (v: MetricInterval | null | undefined) => binary.value ? rateInterval(v) : interval(v)
/** Binary planned differences are rate differences, shown in percentage points. */
const difference = (v: number | null | undefined) => v == null ? '—' : binary.value ? `${(v * 100).toFixed(2)} pp` : display(v)
const differenceInterval = (v: MetricInterval | null | undefined) => v ? `${difference(v.lower)} to ${difference(v.upper)}` : '—'
/**
 * Outcome counts differ by metric: converting subjects for binary goals and subjects with at
 * least one matching event for event counts. Session-duration aggregation records observations only.
 */
const outcomeCountLabel = computed(() => props.metricType === 'EVENT_COUNT' ? 'Subjects with events' : props.metricType === 'SESSION_DURATION' ? null : 'Conversions')
const exclusions = computed(() => rows.value[0]?.analysis.diagnostics ?? null)
const rate = (n: number | null, d: number | null) => n == null || d == null ? 'Not applicable' : d > 0 ? `${percent(n / d)} (${n} / ${d})` : `— (${n} / ${d})`
const populationLabel = (a: GoalPopulationAnalysis) => a.notApplicable ? 'Not applicable' : a.activationConfigured ? 'Configured' : 'Not configured'
const conclusionColor = (c: string) => c === 'CONCLUSIVE' ? '#34d99a' : c === 'EQUIVALENT' ? '#5ec5ff' : c === 'DATA_QUALITY_BLOCKED' ? '#e5484d' : '#6c7388'

// ── Chart geometry: every chart shares a 720-unit viewBox with a 150-unit label column. ──
const PLOT_X = 170
const PLOT_W = 520

const estimateRange = computed(() => {
  const values = rows.value.flatMap(r => [r.analysis.overall.estimate ?? 0, r.analysis.overall.interval?.lower ?? 0, r.analysis.overall.interval?.upper ?? 0])
  return { min: Math.min(0, ...values), max: Math.max(0.001, ...values) }
})
const estimateX = (v: number) => PLOT_X + PLOT_W * (v - estimateRange.value.min) / (estimateRange.value.max - estimateRange.value.min)
const differenceRange = computed(() => {
  const values = props.comparisons.flatMap(c => [c.difference ?? 0, c.interval?.lower ?? 0, c.interval?.upper ?? 0])
  return { min: Math.min(-0.001, ...values), max: Math.max(0.001, ...values) }
})
const differenceX = (v: number) => PLOT_X + PLOT_W * (v - differenceRange.value.min) / (differenceRange.value.max - differenceRange.value.min)

const populationNames = { overall: 'Overall', activated: 'Exposed · descriptive', notActivated: 'Not exposed · descriptive' } as const
const populationRows = computed(() => rows.value.flatMap(r => (['overall', 'activated', 'notActivated'] as const).map((key, index) => ({
  variationKey: r.variationKey, first: index === 0, label: populationNames[key], population: r.analysis[key], analysis: r.analysis,
}))))

const FUNNEL_ROW = 26
const FUNNEL_HEAD = 24
const FUNNEL_BAR = 440
const funnelGroups = computed(() => rows.value.map((r) => {
  const a = r.analysis
  const engaged = a.attribution?.engagedSubjects ?? (props.metricType === 'UNIQUE_CONVERSION' ? a.activated?.conversions ?? null : null)
  return {
    arm: r.variationKey,
    stages: [
      { key: 'assigned', label: 'Assigned', n: r.assignments, d: r.assignments },
      { key: 'eligible', label: 'Eligible', n: a.overall.subjects, d: r.assignments },
      { key: 'exposed', label: 'Observed / exposed', n: a.activated?.subjects ?? null, d: a.overall.subjects },
      { key: 'engaged', label: a.attribution ? 'Engaged' : 'Outcome after exposure', n: engaged, d: a.activated?.subjects ?? null },
      { key: 'attributed', label: 'Attributed destination', n: a.attribution?.attributedSubjects ?? null, d: engaged },
    ],
  }
}).map((g, _, all) => ({
  ...g,
  // A stage no arm measures (no attribution configured, or no exposed outcome for continuous
  // metrics) is omitted rather than drawn as a row of N/A values.
  stages: g.stages.filter(s => all.some(other => other.stages.find(o => o.key === s.key)?.n != null)),
})))
const stages = computed(() => funnelGroups.value.flatMap(g => g.stages.map(s => ({ arm: g.arm, ...s }))))
const funnelGroupHeight = computed(() => FUNNEL_HEAD + (funnelGroups.value[0]?.stages.length ?? 0) * FUNNEL_ROW + 8)
const funnelMax = computed(() => Math.max(1, ...stages.value.map(s => s.n ?? 0)))
const barWidth = (n: number | null, d: number | null) => n == null ? 0 : funnelMode.value === 'counts' ? FUNNEL_BAR * n / funnelMax.value : d ? FUNNEL_BAR * n / d : 0

const REACH_X = 60
const REACH_W = 640
const REACH_Y = 250
const REACH_H = 230
const reachX = (v: number) => REACH_X + v * REACH_W
const reachY = (v: number) => REACH_Y - v * REACH_H
const usefulness = (a: GoalPopulationAnalysis) => a.activated?.subjects ? a.activated.conversions / a.activated.subjects : 0

const placementColumns = ['expectedOnly', 'unexpectedOnly', 'both', 'neither'] as const
const placementNames = ['Expected only', 'Unexpected only', 'Both', 'Neither']
const placementShare = (r: { analysis: GoalPopulationAnalysis }, key: typeof placementColumns[number]) =>
  (r.analysis.placement?.[key] ?? 0) / Math.max(1, r.analysis.overall.subjects)

const intensityMax = computed(() => Math.max(1, ...activeRows.value.map(r => r.analysis.exposureIntensity?.p99 ?? 0)))
const intensityX = (v: number | null | undefined) => PLOT_X + FUNNEL_BAR * (v ?? 0) / intensityMax.value

const percentiles = ['p50', 'p75', 'p90', 'p95', 'p99'] as const
const distributionRows = computed(() => rows.value.map(r => ({ key: r.variationKey, population: r.analysis[segment.value], analysis: r.analysis })))
const distributionMax = computed(() => Math.max(props.metricType === 'SESSION_DURATION' ? 300 : 1, ...distributionRows.value.map(r => r.population?.distribution?.p99 ?? 0)))
const percentileX = (j: number) => 90 + j * 140
const dy = (v: number) => 240 - 210 * v / distributionMax.value

const segmentOptions = [
  { value: 'overall', label: 'Overall' },
  { value: 'activated', label: 'Exposed (descriptive)' },
  { value: 'notActivated', label: 'Not exposed (descriptive)' },
] as const
const funnelOptions = [{ value: 'counts', label: 'Counts' }, { value: 'percentages', label: 'Percentages' }] as const
</script>
<template>
  <section
    v-if="rows.length"
    class="goal-analysis"
    :style="{ '--ga-accent': accent }"
    aria-label="Goal population analysis">
    <header class="ga-head">
      <div class="ga-head-row">
        <span class="ga-title">Detailed analysis</span>
        <span v-if="cutoff" class="ga-cutoff">Aggregation cutoff <time :datetime="cutoff">{{ cutoffLabel }}</time></span>
      </div>
      <p class="ga-note">Overall outcomes use all eligible subjects. Exposed and not-exposed outcomes are descriptive: reaching a placement happens after assignment.</p>
    </header>

    <Tabs
      v-model="view"
      class="ga-tabs"
      :tabs="views"
      :accent="accent" />

    <div class="ga-body">
      <section v-if="view === 'Comparison'" aria-label="Overall outcome comparison" class="ga-pane">
        <h4>Overall outcome · all eligible</h4>
        <svg
          class="ga-chart"
          :viewBox="`0 0 720 ${rows.length * 32 + 44}`"
          role="img"
          aria-label="Overall estimates and 95 percent intervals">
          <line
            :x1="PLOT_X"
            :x2="PLOT_X + PLOT_W"
            :y1="rows.length * 32 + 12"
            :y2="rows.length * 32 + 12"
            class="axis" />
          <g v-for="(r, i) in rows" :key="r.variationKey" :transform="`translate(0, ${22 + i * 32})`">
            <text x="0" y="4" class="label">{{ r.variationKey }}</text>
            <line
              :x1="PLOT_X"
              :x2="PLOT_X + PLOT_W"
              y1="0"
              y2="0"
              class="track" />
            <line
              v-if="r.analysis.overall.interval"
              :x1="estimateX(r.analysis.overall.interval.lower)"
              :x2="estimateX(r.analysis.overall.interval.upper)"
              y1="0"
              y2="0"
              class="whisker" />
            <circle
              v-if="r.analysis.overall.estimate !== null"
              :cx="estimateX(r.analysis.overall.estimate)"
              cy="0"
              r="5"
              class="point" />
          </g>
          <text :x="PLOT_X" :y="rows.length * 32 + 32" class="tick">{{ estimate(estimateRange.min) }}</text>
          <text
            :x="PLOT_X + PLOT_W"
            :y="rows.length * 32 + 32"
            text-anchor="end"
            class="tick">{{ estimate(estimateRange.max) }}</text>
        </svg>
        <table>
          <caption>Overall estimates and 95% intervals</caption>
          <thead><tr><th>Variation</th><th class="num">Eligible</th><th v-if="outcomeCountLabel" class="num">{{ outcomeCountLabel }}</th><th v-if="!binary" class="num">Observations</th><th class="num">Estimate</th><th class="num">95% interval</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.variationKey">
              <th>{{ r.variationKey }}</th>
              <td class="num">{{ r.analysis.overall.subjects.toLocaleString() }}</td>
              <td v-if="outcomeCountLabel" class="num">{{ r.analysis.overall.conversions.toLocaleString() }}</td>
              <td v-if="!binary" class="num">{{ r.analysis.overall.observationCount.toLocaleString() }}</td>
              <td class="num">{{ estimate(r.analysis.overall.estimate) }}</td>
              <td class="num">{{ estimateInterval(r.analysis.overall.interval) }}</td>
            </tr>
          </tbody>
        </table>

        <template v-if="comparisons.length">
          <h4 class="ga-subhead">Planned differences · corrected comparison family</h4>
          <svg
            class="ga-chart"
            :viewBox="`0 0 720 ${comparisons.length * 32 + 44}`"
            role="img"
            aria-label="Planned pairwise differences with comparison intervals">
            <line
              :x1="differenceX(0)"
              :x2="differenceX(0)"
              y1="6"
              :y2="comparisons.length * 32 + 12"
              class="floor" />
            <g v-for="(c, i) in comparisons" :key="`${c.leftVariationKey}:${c.rightVariationKey}`" :transform="`translate(0, ${22 + i * 32})`">
              <text x="0" y="4" class="label">{{ c.leftVariationKey }} − {{ c.rightVariationKey }}</text>
              <line
                v-if="c.interval"
                :x1="differenceX(c.interval.lower)"
                :x2="differenceX(c.interval.upper)"
                y1="0"
                y2="0"
                class="whisker" />
              <circle
                v-if="c.difference !== null"
                :cx="differenceX(c.difference)"
                cy="0"
                r="5"
                class="point" />
              <title>{{ c.conclusion.replaceAll('_', ' ') }}: {{ c.reason }}</title>
            </g>
            <text :x="differenceX(differenceRange.min)" :y="comparisons.length * 32 + 32" class="tick">{{ difference(differenceRange.min) }}</text>
            <text
              :x="differenceX(0)"
              :y="comparisons.length * 32 + 32"
              text-anchor="middle"
              class="tick">0</text>
            <text
              :x="differenceX(differenceRange.max)"
              :y="comparisons.length * 32 + 32"
              text-anchor="end"
              class="tick">{{ difference(differenceRange.max) }}</text>
          </svg>
          <table>
            <caption>
              Left minus right on the overall population{{ binary ? ', in percentage points' : '' }}{{ cuped ? '; CUPED-adjusted means where the covariate applied' : '' }}.
              Evidence must reach the corrected threshold.
            </caption>
            <thead><tr><th>Comparison</th><th class="num">Samples</th><th class="num">Difference</th><th class="num">Interval</th><th class="num">Evidence</th><th class="num">Threshold</th><th>Conclusion</th></tr></thead>
            <tbody>
              <tr v-for="c in comparisons" :key="`${c.leftVariationKey}:${c.rightVariationKey}`">
                <th>{{ c.leftVariationKey }} − {{ c.rightVariationKey }}</th>
                <td class="num">{{ c.leftSubjects }} / {{ c.rightSubjects }}</td>
                <td class="num">{{ difference(c.difference) }}</td>
                <td class="num">{{ differenceInterval(c.interval) }}</td>
                <td class="num">{{ c.evidenceLabel }} {{ percent(c.evidence) }}</td>
                <td class="num">{{ percent(c.threshold) }}</td>
                <td class="conclusion">
                  <span class="pill" :style="{ '--pill': conclusionColor(c.conclusion) }">{{ c.conclusion.replaceAll('_', ' ') }}</span>
                  <span class="reason">{{ c.reason }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>

      <section v-else-if="view === 'Populations'" aria-label="Population outcomes" class="ga-pane">
        <h4>Overall and descriptive population outcomes</h4>
        <table>
          <caption>Outcomes from eligibility, after exposure, and among subjects never exposed</caption>
          <thead><tr><th>Variation</th><th>Population</th><th class="num">Subjects</th><th v-if="outcomeCountLabel" class="num">{{ outcomeCountLabel }}</th><th v-if="!binary" class="num">Observations</th><th class="num">Estimate</th><th class="num">95% interval</th></tr></thead>
          <tbody>
            <tr v-for="r in populationRows" :key="`${r.variationKey}:${r.label}`" :class="{ 'group-start': r.first }">
              <th>{{ r.first ? r.variationKey : '' }}</th>
              <td>{{ r.label }}</td>
              <td class="num">{{ r.population?.subjects ?? populationLabel(r.analysis) }}</td>
              <td v-if="outcomeCountLabel" class="num">{{ r.population?.conversions ?? 'Not applicable' }}</td>
              <td v-if="!binary" class="num">{{ r.population?.observationCount ?? 'Not applicable' }}</td>
              <td class="num">{{ r.population ? estimate(r.population.estimate) : populationLabel(r.analysis) }}</td>
              <td class="num">{{ r.population ? estimateInterval(r.population.interval) : populationLabel(r.analysis) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Funnel'" aria-label="Variation funnel" class="ga-pane">
        <div class="ga-pane-head">
          <h4>Variation funnel</h4>
          <div class="ga-toggle" role="group" aria-label="Funnel display">
            <button
              v-for="o in funnelOptions"
              :key="o.value"
              type="button"
              :aria-pressed="funnelMode === o.value"
              :class="{ active: funnelMode === o.value }"
              @click="funnelMode = o.value">{{ o.label }}</button>
          </div>
        </div>
        <svg
          class="ga-chart"
          :viewBox="`0 0 720 ${funnelGroups.length * funnelGroupHeight}`"
          role="img"
          aria-label="Assigned, eligible, observed, engaged, and attributed destination counts">
          <g v-for="(g, gi) in funnelGroups" :key="g.arm" :transform="`translate(0, ${gi * funnelGroupHeight})`">
            <text x="0" y="16" class="group">{{ g.arm }}</text>
            <g v-for="(s, i) in g.stages" :key="s.label" :transform="`translate(0, ${FUNNEL_HEAD + i * FUNNEL_ROW + 10})`">
              <text x="12" y="4" class="label">{{ s.label }}</text>
              <rect
                :x="PLOT_X"
                y="-7"
                :width="FUNNEL_BAR"
                height="12"
                rx="3"
                class="bar-track" />
              <rect
                :x="PLOT_X"
                y="-7"
                :width="barWidth(s.n, s.d)"
                height="12"
                rx="3"
                class="bar" />
              <text
                x="720"
                y="4"
                text-anchor="end"
                class="value">{{ s.n === null ? 'N/A' : funnelMode === 'counts' ? s.n.toLocaleString() : percent(s.d ? s.n / s.d : null) }}</text>
              <title>{{ s.label }}: {{ rate(s.n, s.d) }}. {{ eventDescription(g.arm) }}</title>
            </g>
          </g>
        </svg>
        <table>
          <caption>Funnel counts, rates, and stage denominators</caption>
          <thead><tr><th>Variation</th><th>Stage</th><th class="num">Count</th><th class="num">Rate (numerator / denominator)</th></tr></thead>
          <tbody>
            <tr v-for="(s, i) in stages" :key="`${s.arm}:${s.label}`" :class="{ 'group-start': i > 0 && stages[i - 1]?.arm !== s.arm }">
              <th>{{ i === 0 || stages[i - 1]?.arm !== s.arm ? s.arm : '' }}</th>
              <td>{{ s.label }}</td>
              <td class="num">{{ s.n ?? 'Not applicable' }}</td>
              <td class="num">{{ rate(s.n, s.d) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Reach'" aria-label="Reach versus usefulness" class="ga-pane">
        <h4>Reach versus usefulness · descriptive</h4>
        <svg
          class="ga-chart"
          viewBox="0 0 720 290"
          role="img"
          aria-label="Exposure rate versus outcome rate among exposed subjects, with 95 percent interval whiskers">
          <line
            v-for="t in [0.25, 0.5, 0.75, 1]"
            :key="`gx${t}`"
            :x1="reachX(t)"
            :x2="reachX(t)"
            :y1="reachY(1)"
            :y2="REACH_Y"
            class="grid" />
          <line
            v-for="t in [0.25, 0.5, 0.75, 1]"
            :key="`gy${t}`"
            :x1="REACH_X"
            :x2="reachX(1)"
            :y1="reachY(t)"
            :y2="reachY(t)"
            class="grid" />
          <path :d="`M${REACH_X} ${reachY(1)} V${REACH_Y} H${reachX(1)}`" class="axis" />
          <text
            v-for="t in [0, 0.5, 1]"
            :key="`tx${t}`"
            :x="reachX(t)"
            :y="REACH_Y + 16"
            text-anchor="middle"
            class="tick">{{ t * 100 }}%</text>
          <text
            v-for="t in [0, 0.5, 1]"
            :key="`ty${t}`"
            :x="REACH_X - 8"
            :y="reachY(t) + 4"
            text-anchor="end"
            class="tick">{{ t * 100 }}%</text>
          <text
            :x="reachX(0.5)"
            y="286"
            text-anchor="middle"
            class="label">Exposure / eligible →</text>
          <text :x="REACH_X + 6" y="14" class="label">Outcome / exposed ↑</text>
          <g v-for="(r, i) in activeRows" :key="r.variationKey" :class="`series-${i % 3}`">
            <line
              v-if="r.analysis.reachInterval"
              :x1="reachX(r.analysis.reachInterval.lower)"
              :x2="reachX(r.analysis.reachInterval.upper)"
              :y1="reachY(usefulness(r.analysis))"
              :y2="reachY(usefulness(r.analysis))"
              class="whisker" />
            <line
              v-if="r.analysis.activated?.interval"
              :x1="reachX(r.analysis.reach ?? 0)"
              :x2="reachX(r.analysis.reach ?? 0)"
              :y1="reachY(r.analysis.activated.interval.lower)"
              :y2="reachY(r.analysis.activated.interval.upper)"
              class="whisker" />
            <circle
              :cx="reachX(r.analysis.reach ?? 0)"
              :cy="reachY(usefulness(r.analysis))"
              :r="4 + 16 * Math.sqrt(r.analysis.overall.subjects / largestEligible)"
              class="bubble" />
            <text :x="reachX(r.analysis.reach ?? 0) + 10" :y="reachY(usefulness(r.analysis)) - 10" class="label">{{ r.variationKey }}</text>
          </g>
        </svg>
        <table>
          <caption>Reach and exposed outcome rates</caption>
          <thead><tr><th>Variation</th><th class="num">Reach</th><th class="num">Reach 95% interval</th><th class="num">Outcome / exposed</th><th class="num">Outcome 95% interval</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.variationKey">
              <th>{{ r.variationKey }}</th>
              <td class="num">{{ r.analysis.activated ? rate(r.analysis.activated.subjects, r.analysis.overall.subjects) : populationLabel(r.analysis) }}</td>
              <td class="num">{{ rateInterval(r.analysis.reachInterval) }}</td>
              <td class="num">{{ r.analysis.activated ? rate(r.analysis.activated.conversions, r.analysis.activated.subjects) : populationLabel(r.analysis) }}</td>
              <td class="num">{{ rateInterval(r.analysis.activated?.interval) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Placement'" aria-label="Placement integrity" class="ga-pane">
        <h4>Assignment by observed placement</h4>
        <svg
          class="ga-chart"
          :viewBox="`0 0 720 ${rows.length * 42 + 30}`"
          role="img"
          aria-label="Expected only, unexpected only, both, and neither observed placement">
          <text
            v-for="(name, j) in placementNames"
            :key="name"
            :x="PLOT_X + j * 135 + 63"
            y="14"
            text-anchor="middle"
            class="tick">{{ name }}</text>
          <g v-for="(r, i) in rows" :key="r.variationKey" :transform="`translate(0, ${26 + i * 42})`">
            <text x="0" y="22" class="label">{{ r.variationKey }}</text>
            <g v-for="(key, j) in placementColumns" :key="key">
              <rect
                :x="PLOT_X + j * 135"
                y="0"
                width="127"
                height="34"
                rx="4"
                class="bar-track" />
              <rect
                :x="PLOT_X + j * 135"
                y="0"
                width="127"
                height="34"
                rx="4"
                class="cell"
                :fill-opacity="0.08 + 0.72 * placementShare(r, key)" />
              <text
                :x="PLOT_X + j * 135 + 63"
                y="22"
                text-anchor="middle"
                class="value">{{ r.analysis.placement?.[key]?.toLocaleString() ?? 'N/A' }}</text>
            </g>
          </g>
        </svg>
        <table>
          <caption>Placement integrity counts within each eligible arm</caption>
          <thead><tr><th>Variation</th><th class="num">Eligible</th><th v-for="name in placementNames" :key="name" class="num">{{ name }}</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.variationKey">
              <th>{{ r.variationKey }}</th>
              <td class="num">{{ r.analysis.overall.subjects }}</td>
              <td v-for="key in placementColumns" :key="key" class="num">{{ r.analysis.placement?.[key] ?? 'Not applicable' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Exposure'" aria-label="Exposure intensity" class="ga-pane">
        <h4>Exposure intensity · activation events per exposed subject</h4>
        <svg
          class="ga-chart"
          :viewBox="`0 0 720 ${activeRows.length * 32 + 20}`"
          role="img"
          aria-label="Exposure intensity median, p95, and p99">
          <g v-for="r in activeRows" :key="r.variationKey" :transform="`translate(0, ${20 + activeRows.indexOf(r) * 32})`">
            <text x="0" y="4" class="label">{{ r.variationKey }}</text>
            <line
              :x1="PLOT_X"
              :x2="PLOT_X + FUNNEL_BAR"
              y1="0"
              y2="0"
              class="track" />
            <line
              :x1="PLOT_X"
              :x2="intensityX(r.analysis.exposureIntensity?.p99)"
              y1="0"
              y2="0"
              class="whisker" />
            <circle :cx="intensityX(r.analysis.exposureIntensity?.p50)" r="5" class="point" />
            <rect
              :x="intensityX(r.analysis.exposureIntensity?.p95) - 4"
              y="-4"
              width="8"
              height="8"
              class="point" />
            <text
              x="720"
              y="4"
              text-anchor="end"
              class="value">p99 {{ r.analysis.exposureIntensity?.p99 ?? '—' }}</text>
          </g>
        </svg>
        <table>
          <caption>Logical exposure events · circle p50, square p95, line end p99</caption>
          <thead><tr><th>Variation</th><th class="num">Exposed subjects</th><th class="num">Events</th><th class="num">Mean</th><th class="num">p50</th><th class="num">p95</th><th class="num">p99</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.variationKey">
              <th>{{ r.variationKey }}</th>
              <td class="num">{{ r.analysis.activated?.subjects ?? populationLabel(r.analysis) }}</td>
              <td class="num">{{ r.analysis.exposureEvents ?? 'Not applicable' }}</td>
              <td class="num">{{ display(r.analysis.exposureIntensity?.mean) }}</td>
              <td class="num">{{ display(r.analysis.exposureIntensity?.p50) }}</td>
              <td class="num">{{ display(r.analysis.exposureIntensity?.p95) }}</td>
              <td class="num">{{ display(r.analysis.exposureIntensity?.p99) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Distribution'" aria-label="Outcome distributions" class="ga-pane">
        <div class="ga-pane-head">
          <h4>{{ metricType === 'SESSION_DURATION' ? 'Session length' : 'Event count' }} distribution</h4>
          <div class="ga-toggle" role="group" aria-label="Population">
            <button
              v-for="o in segmentOptions"
              :key="o.value"
              type="button"
              :aria-pressed="segment === o.value"
              :class="{ active: segment === o.value }"
              @click="segment = o.value">{{ o.label }}</button>
          </div>
        </div>
        <svg
          class="ga-chart"
          viewBox="0 0 720 285"
          role="img"
          aria-label="Percentile profiles p50, p75, p90, p95, and p99">
          <line
            x1="70"
            x2="700"
            :y1="dy(0)"
            :y2="dy(0)"
            class="axis" />
          <template v-if="metricType === 'SESSION_DURATION'">
            <line
              x1="70"
              x2="700"
              :y1="dy(300)"
              :y2="dy(300)"
              class="floor" />
            <text x="72" :y="dy(300) - 6" class="tick">300s inactivity floor</text>
          </template>
          <text
            x="62"
            :y="dy(distributionMax) + 4"
            text-anchor="end"
            class="tick">{{ display(distributionMax) }}</text>
          <text
            x="62"
            :y="dy(0) + 4"
            text-anchor="end"
            class="tick">0</text>
          <text
            v-for="(p, j) in percentiles"
            :key="p"
            :x="percentileX(j)"
            :y="dy(0) + 16"
            text-anchor="middle"
            class="tick">{{ p }}</text>
          <g v-for="(r, i) in distributionRows" :key="r.key" :class="`series-${i % 3}`">
            <template v-if="r.population?.distribution">
              <polyline :points="percentiles.map((p, j) => `${percentileX(j)},${dy(r.population?.distribution?.[p] ?? 0)}`).join(' ')" class="profile" />
              <circle
                v-for="(p, j) in percentiles"
                :key="p"
                :cx="percentileX(j)"
                :cy="dy(r.population.distribution[p])"
                r="3"
                class="point" />
            </template>
            <line
              :x1="90 + i * 180"
              :x2="114 + i * 180"
              y1="276"
              y2="276"
              class="profile" />
            <text :x="120 + i * 180" y="280" class="label">{{ r.key }}</text>
          </g>
        </svg>
        <table>
          <caption>Exact subject-level distribution values</caption>
          <thead><tr><th>Variation</th><th class="num">Subjects</th><th class="num">Observations</th><th class="num">Mean</th><th class="num">SD</th><th class="num">95% interval</th><th v-for="p in percentiles" :key="p" class="num">{{ p }}</th><th class="num">Maximum</th></tr></thead>
          <tbody>
            <tr v-for="r in distributionRows" :key="r.key">
              <th>{{ r.key }}</th>
              <td class="num">{{ r.population?.subjects ?? populationLabel(r.analysis) }}</td>
              <td class="num">{{ r.population?.observationCount ?? 'Not applicable' }}</td>
              <td class="num">{{ r.population ? display(r.population.estimate) : populationLabel(r.analysis) }}</td>
              <td class="num">{{ display(r.population?.distribution?.standardDeviation) }}</td>
              <td class="num">{{ r.population ? estimateInterval(r.population.interval) : populationLabel(r.analysis) }}</td>
              <td v-for="p in percentiles" :key="p" class="num">{{ display(r.population?.distribution?.[p]) }}</td>
              <td class="num">{{ display(r.population?.distribution?.maximum) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Data quality'" aria-label="Data quality diagnostics" class="ga-pane">
        <h4>Data quality and destination attribution</h4>
        <p v-if="exclusions" class="ga-dq-exclusions">
          Configured exclusions: {{ exclusions.excludedPrincipals.toLocaleString() }} principals and {{ exclusions.excludedInstallations.toLocaleString() }} linked installations, removed from every variation.
        </p>
        <div class="ga-dq-grid">
          <div v-for="r in rows" :key="r.variationKey" class="ga-dq">
            <div class="ga-dq-name">{{ r.variationKey }}</div>
            <p v-if="r.analysis.placement && (r.analysis.placement.unexpectedOnly || r.analysis.placement.both)" role="status" class="ga-warn">
              Unexpected exposure: {{ r.analysis.placement.unexpectedOnly }} unexpected only; {{ r.analysis.placement.both }} observed both. These subjects remain in the assigned arm.
            </p>
            <p v-if="!r.analysis.activationConfigured" class="ga-dq-none">
              Delivery checks run on goal activation events; {{ r.analysis.notApplicable ? 'this variation has no exposure stage.' : 'none is configured for this variation.' }}
            </p>
            <dl v-else class="ga-kv">
              <div><dt>Duplicate deliveries</dt><dd :class="{ flagged: r.analysis.diagnostics.duplicateDeliveries > 0 }">{{ r.analysis.diagnostics.duplicateDeliveries }}</dd></div>
              <div><dt>Outside application scope</dt><dd :class="{ flagged: r.analysis.diagnostics.outsideApplication > 0 }">{{ r.analysis.diagnostics.outsideApplication }}</dd></div>
              <div><dt>Missing application</dt><dd :class="{ flagged: r.analysis.diagnostics.missingApplication > 0 }">{{ r.analysis.diagnostics.missingApplication }}</dd></div>
            </dl>
            <template v-if="r.analysis.attribution">
              <div class="ga-dq-sub">Destination attribution</div>
              <dl class="ga-kv">
                <div class="wide"><dt>Verified destination</dt><dd>{{ rate(r.analysis.attribution.attributedSubjects, r.analysis.attribution.engagedSubjects) }} engaged subjects</dd></div>
                <div class="wide"><dt>95% interval</dt><dd>{{ rateInterval(r.analysis.attribution.interval) }}</dd></div>
                <div><dt>Logical interactions</dt><dd>{{ r.analysis.attribution.interactions }}</dd></div>
                <div><dt>Matched interactions</dt><dd>{{ r.analysis.attribution.attributedInteractions }}</dd></div>
                <div><dt>Unresolved targets</dt><dd :class="{ flagged: r.analysis.attribution.unresolvedTargets > 0 }">{{ r.analysis.attribution.unresolvedTargets }}</dd></div>
                <div><dt>Unsupported content types</dt><dd :class="{ flagged: r.analysis.attribution.unsupportedTypes > 0 }">{{ r.analysis.attribution.unsupportedTypes }}</dd></div>
                <div><dt>Missing route slugs</dt><dd :class="{ flagged: r.analysis.attribution.missingSlugs > 0 }">{{ r.analysis.attribution.missingSlugs }}</dd></div>
                <div><dt>Missing sessions</dt><dd :class="{ flagged: r.analysis.attribution.missingSessions > 0 }">{{ r.analysis.attribution.missingSessions }}</dd></div>
                <div><dt>Unmatched destinations</dt><dd>{{ r.analysis.attribution.unmatchedDestinations }}</dd></div>
              </dl>
            </template>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>
<style scoped>
.goal-analysis {
  border-top: 1px solid var(--line);
  color: var(--fg-1);
  min-width: 0;
}

.ga-head {
  padding: 12px 14px 0;
}

.ga-head-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.ga-title {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fg-3);
}

.ga-cutoff,
.ga-note {
  font-size: 11px;
  color: var(--fg-3);
}

.ga-note {
  margin: 4px 0 0;
  line-height: 1.5;
}

.ga-tabs {
  padding: 0 8px;
  margin-top: 4px;
}

.ga-body {
  padding: 14px;
}

.ga-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  overflow-x: auto;
}

.ga-pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

h4 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.ga-subhead {
  margin-top: 12px;
}

.ga-toggle {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm, 6px);
}

.ga-toggle button {
  padding: 4px 10px;
  font-size: 11.5px;
  color: var(--fg-3);
  border-radius: calc(var(--r-sm, 6px) - 2px);
  transition: color 0.15s, background 0.15s;
}

.ga-toggle button:hover {
  color: var(--fg-1);
}

.ga-toggle button.active {
  background: var(--bg-4, var(--bg-3));
  color: var(--fg-0);
}

/* Charts render at their viewBox size so 11px labels stay 11px on wide cards. */
.ga-chart {
  display: block;
  width: 100%;
  max-width: 720px;
  height: auto;
  overflow: visible;
}

.ga-chart text {
  font-size: 11px;
  fill: var(--fg-2);
}

.ga-chart .label {
  fill: var(--fg-1);
}

.ga-chart .group {
  fill: var(--fg-0);
  font-weight: 600;
  font-size: 12px;
}

.ga-chart .tick {
  fill: var(--fg-3);
  font-size: 10.5px;
}

.ga-chart .value {
  fill: var(--fg-0);
  font-variant-numeric: tabular-nums;
}

.track,
.grid {
  stroke: color-mix(in oklch, var(--line) 55%, transparent);
  stroke-width: 1;
}

.axis {
  stroke: var(--line-2, var(--line));
  stroke-width: 1;
  fill: none;
}

.floor {
  stroke: var(--fg-3);
  stroke-dasharray: 4 4;
}

.whisker {
  stroke: var(--ga-accent);
  stroke-width: 2;
  stroke-linecap: round;
  fill: none;
}

.point,
.bar,
.cell {
  fill: var(--ga-accent);
}

.bar-track {
  fill: var(--bg-3);
}

.bubble {
  fill: var(--ga-accent);
  fill-opacity: 0.3;
  stroke: var(--ga-accent);
  stroke-width: 1.5;
}

.profile {
  fill: none;
  stroke: var(--ga-accent);
  stroke-width: 2;
}

.series-1 { --ga-accent: #b78bdd; }
.series-2 { --ga-accent: #d6a767; }
.series-1 .profile { stroke-dasharray: 7 3; }
.series-2 .profile { stroke-dasharray: 2 3; }

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

caption {
  caption-side: top;
  text-align: left;
  font-size: 11px;
  color: var(--fg-3);
  padding-bottom: 6px;
}

thead th {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fg-3);
  background: var(--bg-2);
  border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent);
  white-space: nowrap;
}

th,
td {
  padding: 8px 10px;
  text-align: left;
  vertical-align: top;
}

tbody tr {
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

tbody tr:last-child {
  border-bottom: none;
}

tbody tr.group-start {
  border-top: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

tbody th {
  font-weight: 500;
  color: var(--fg-0);
  white-space: nowrap;
}

.num {
  text-align: right;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.conclusion {
  min-width: 220px;
}

.pill {
  display: inline-block;
  padding: 1px 7px;
  margin-right: 6px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--pill);
  background: color-mix(in srgb, var(--pill) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--pill) 35%, transparent);
}

.reason {
  color: var(--fg-2);
  line-height: 1.5;
}

.ga-dq-exclusions,
.ga-dq-none {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--fg-3);
}

.ga-dq-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.ga-dq {
  padding: 12px;
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  border-radius: var(--r-sm, 6px);
  background: var(--bg-1);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ga-dq-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.ga-dq-sub {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fg-3);
  padding-top: 4px;
  border-top: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.ga-warn {
  margin: 0;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
  background: color-mix(in srgb, #f59e42 10%, var(--bg-1));
  border: 1px solid color-mix(in srgb, #f59e42 35%, transparent);
  border-radius: var(--r-sm, 6px);
}

.ga-kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin: 0;
}

.ga-kv > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.ga-kv > div.wide {
  grid-column: 1 / -1;
}

.ga-kv dt {
  color: var(--fg-3);
}

.ga-kv dd {
  margin: 0;
  color: var(--fg-0);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.ga-kv dd.flagged {
  color: #f59e42;
}
</style>
