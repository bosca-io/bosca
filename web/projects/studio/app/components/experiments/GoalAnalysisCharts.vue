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
  variationNames?: Record<string, string>
}>(), { comparisons: () => [], activation: null, accent: 'var(--brand-2)', cuped: false, variationNames: () => ({}) })

const segment = ref<'overall' | 'activated'>('overall')
const funnelMode = ref<'counts' | 'percentages'>('counts')
const rows = computed(() => props.rows.flatMap(r => r.goalAnalysis ? [{ ...r, analysis: r.goalAnalysis }] : []))
const activeRows = computed(() => rows.value.filter(r => r.analysis.activationConfigured))
const hasActivation = computed(() => activeRows.value.length > 0)
/** Every variation with an activation event recorded nobody reaching it. */
const noneReached = computed(() => hasActivation.value && activeRows.value.every(r => (r.analysis.activated?.subjects ?? 0) === 0))
const reachedViews = computed(() => hasActivation.value && !noneReached.value)
/** Matching activation events rejected by application scope. */
const rejectedOtherApplication = computed(() => activeRows.value.reduce((sum, r) => sum + r.analysis.diagnostics.outsideApplication, 0))
/** Whether any activation rule restricts applications; without one, rejections come from an earlier aggregation. */
const applicationsConfigured = computed(() => !!props.activation && (props.activation.applicationIds.length > 0
  || !!props.activation.defaultFilter?.applicationIds?.length
  || props.activation.variationRules.some(r => !!r.filter?.applicationIds?.length)))
const largestEligible = computed(() => Math.max(1, ...rows.value.map(r => r.analysis.overall.subjects)))
const cutoff = computed(() => rows.value[0]?.analysis.cutoff ?? null)
const cutoffLabel = computed(() => {
  const date = cutoff.value ? new Date(cutoff.value) : null
  return date && !Number.isNaN(date.getTime()) ? date.toLocaleString() : cutoff.value
})
const name = (key: string) => props.variationNames[key] ?? key
const listNames = (keys: string[]) => {
  const names = keys.map(name)
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

const views = computed(() => [
  'Comparison',
  ...hasActivation.value ? ['Populations', 'Funnel'] : [],
  ...reachedViews.value && props.metricType === 'UNIQUE_CONVERSION' ? ['Reach'] : [],
  ...reachedViews.value ? ['Placement', 'Exposure'] : [],
  ...props.metricType !== 'UNIQUE_CONVERSION' ? ['Distribution'] : [],
  'Data quality',
])
const view = ref('Comparison')
watch(views, (available) => {
  if (!available.includes(view.value)) view.value = 'Comparison'
})

function eventDescription(key: string): string {
  const rule = props.activation?.variationRules.find(r => r.variationKey === key)
  if (rule?.mode === 'NOT_APPLICABLE') return 'No activation stage for this variation.'
  const f = rule?.mode === 'EVENT' ? rule.filter : props.activation?.defaultFilter
  if (!f) return 'Activation follows the configured goal activation event.'
  return [f.eventType, f.elementType, f.elementId, f.elementIdContains && `ID contains ${f.elementIdContains}`,
    f.pagePath, ...f.pagePathPrefixes ?? [], f.itemExtraKey && `${f.itemExtraKey}: ${f.itemExtraValue ?? 'present'}`,
    f.recommendationCard && 'card with content target, at least 50% visible for one second',
    `applications: ${(f.applicationIds?.length ? f.applicationIds : props.activation?.applicationIds)?.join(', ') || 'any'}`].filter(Boolean).join('; ')
}

const binary = computed(() => props.metricType === 'UNIQUE_CONVERSION')
const count = (v: number | null | undefined) => v == null ? '—' : v.toLocaleString()
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
const rate = (n: number | null, d: number | null) => n == null || d == null ? 'Not measured' : d > 0 ? `${percent(n / d)} (${count(n)} / ${count(d)})` : `— (${count(n)} / ${count(d)})`
/**
 * Outcome counts differ by metric: converting subjects for binary goals and subjects with at
 * least one matching event for event counts. Session-duration aggregation records observations only.
 */
const outcomeCountLabel = computed(() => props.metricType === 'EVENT_COUNT' ? 'Subjects with events' : props.metricType === 'SESSION_DURATION' ? null : 'Conversions')
const populationNoteSpan = computed(() => 4 + (outcomeCountLabel.value ? 1 : 0) + (binary.value ? 0 : 1))
const noActivationReason = (a: GoalPopulationAnalysis) => a.notApplicable
  ? 'Activation is marked not applicable for this variation.'
  : 'This variation has no activation event.'
const exclusions = computed(() => rows.value[0]?.analysis.diagnostics ?? null)
const conclusionColor = (c: string) => c === 'CONCLUSIVE' ? '#34d99a' : c === 'EQUIVALENT' ? '#5ec5ff' : c === 'DATA_QUALITY_BLOCKED' ? '#e5484d' : '#6c7388'

// ── Positions as percentages of each chart track ──
const clampPct = (v: number, min: number, max: number) => Math.min(100, Math.max(0, (v - min) / (max - min) * 100))
const pct = (v: number, min: number, max: number) => `${clampPct(v, min, max)}%`
const span = (lower: number, upper: number, min: number, max: number) => ({
  left: pct(lower, min, max),
  width: `${Math.max(0, clampPct(upper, min, max) - clampPct(lower, min, max))}%`,
})

const estimateRange = computed(() => {
  const values = rows.value.flatMap(r => [r.analysis.overall.estimate ?? 0, r.analysis.overall.interval?.lower ?? 0, r.analysis.overall.interval?.upper ?? 0])
  return { min: Math.min(0, ...values), max: Math.max(0.001, ...values) }
})
/** Symmetric around zero so the zero line sits at the center of the track. */
const differenceRange = computed(() => {
  const values = props.comparisons.flatMap(c => [c.difference ?? 0, c.interval?.lower ?? 0, c.interval?.upper ?? 0])
  const bound = Math.max(0.001, ...values.map(Math.abs))
  return { min: -bound, max: bound }
})

const populationRows = computed(() => rows.value.map(r => ({
  key: r.variationKey,
  analysis: r.analysis,
  populations: [
    { label: 'All eligible', population: r.analysis.overall },
    ...r.analysis.activated ? [{ label: 'Reached activation event', population: r.analysis.activated }] : [],
  ],
})))

const funnelGroups = computed(() => rows.value.map((r) => {
  const a = r.analysis
  const engaged = a.attribution?.engagedSubjects ?? (binary.value ? a.activated?.conversions ?? null : null)
  return {
    arm: r.variationKey,
    reason: a.activationConfigured ? null : noActivationReason(a),
    stages: [
      { key: 'assigned', label: 'Assigned', n: r.assignments as number | null, d: r.assignments as number | null },
      { key: 'eligible', label: 'Eligible', n: a.overall.subjects, d: r.assignments },
      { key: 'reached', label: 'Reached activation event', n: a.activated?.subjects ?? null, d: a.overall.subjects },
      { key: 'engaged', label: a.attribution ? 'Engaged' : 'Outcome after activation', n: engaged, d: a.activated?.subjects ?? null },
      { key: 'attributed', label: 'Attributed destination', n: a.attribution?.attributedSubjects ?? null, d: engaged },
    ],
  }
}).map((g, _, all) => ({
  ...g,
  // A stage no variation measures is omitted, and a variation without activation stops at eligibility.
  stages: g.stages.filter(s => s.n != null || (g.reason == null && all.some(o => o.stages.find(x => x.key === s.key)?.n != null))),
})))
const stages = computed(() => funnelGroups.value.flatMap(g => g.stages.map(s => ({ arm: g.arm, ...s }))))
const funnelMax = computed(() => Math.max(1, ...stages.value.map(s => s.n ?? 0)))
const barWidth = (n: number | null, d: number | null) => `${n == null ? 0 : funnelMode.value === 'counts' ? 100 * n / funnelMax.value : d ? 100 * n / d : 0}%`
const stageValue = (s: { n: number | null; d: number | null }) => s.n == null ? 'Not measured' : funnelMode.value === 'counts' ? count(s.n) : percent(s.d ? s.n / s.d : null)

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
const placementTint = (r: { analysis: GoalPopulationAnalysis }, key: typeof placementColumns[number]) =>
  `color-mix(in srgb, var(--ga-accent) ${Math.round(4 + 40 * placementShare(r, key))}%, transparent)`

const intensityMax = computed(() => Math.max(1, ...activeRows.value.map(r => r.analysis.exposureIntensity?.p99 ?? 0)))

const percentiles = ['p50', 'p75', 'p90', 'p95', 'p99'] as const
const distributionRows = computed(() => rows.value.map(r => ({ key: r.variationKey, population: r.analysis[segment.value], analysis: r.analysis })))
const distributionMax = computed(() => Math.max(props.metricType === 'SESSION_DURATION' ? 300 : 1, ...distributionRows.value.map(r => r.population?.distribution?.p99 ?? 0)))
const percentileX = (j: number) => 90 + j * 140
const dy = (v: number) => 240 - 210 * v / distributionMax.value

const segmentOptions = computed(() => [
  { value: 'overall' as const, label: 'All eligible' },
  ...reachedViews.value ? [{ value: 'activated' as const, label: 'Reached activation event' }] : [],
])
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
      <p class="ga-note">
        Only <strong>All eligible</strong> is a randomized comparison.<template v-if="hasActivation"> Subjects reach the activation event after assignment, so use that group to understand behavior, not to judge a variation.</template>
      </p>
    </header>

    <div v-if="noneReached" class="ga-alert" role="alert">
      <strong>No subject reached the activation event</strong> in {{ listNames(activeRows.map(r => r.variationKey)) }}.
      <template v-if="rejectedOtherApplication > 0 && applicationsConfigured">
        {{ count(rejectedOtherApplication) }} matching events came from applications outside the configured Application IDs.
      </template>
      <template v-else-if="rejectedOtherApplication > 0">
        {{ count(rejectedOtherApplication) }} matching events were rejected by an application rule that no longer applies when no Application IDs are configured. Aggregate again to include them.
      </template>
      <template v-else>
        No matching event occurred after eligibility{{ activation?.sameSession ? ' in the same session' : '' }}. Check the goal's activation filter.
      </template>
      Reach, placement, and exposure views stay hidden until activation matches.
    </div>

    <Tabs
      v-model="view"
      class="ga-tabs"
      :tabs="views"
      :accent="accent" />

    <div class="ga-body">
      <section v-if="view === 'Comparison'" aria-label="Overall outcome comparison" class="ga-pane">
        <h4>Overall outcome · all eligible</h4>
        <div class="ga-rows" role="img" aria-label="Overall estimates and 95 percent intervals">
          <div v-for="r in rows" :key="r.variationKey" class="ga-row">
            <span class="ga-row-label">{{ name(r.variationKey) }}</span>
            <span class="ga-track ga-track--line">
              <span
                v-if="r.analysis.overall.interval"
                class="ga-whisker"
                :style="span(r.analysis.overall.interval.lower, r.analysis.overall.interval.upper, estimateRange.min, estimateRange.max)" />
              <span
                v-if="r.analysis.overall.estimate != null"
                class="ga-dot"
                :style="{ left: pct(r.analysis.overall.estimate, estimateRange.min, estimateRange.max) }" />
            </span>
            <span class="ga-row-value">{{ estimate(r.analysis.overall.estimate) }}</span>
          </div>
          <div class="ga-row ga-row--axis">
            <span />
            <span class="ga-axis"><span>{{ estimate(estimateRange.min) }}</span><span>{{ estimate(estimateRange.max) }}</span></span>
            <span />
          </div>
        </div>
        <details class="ga-exact">
          <summary>Exact values</summary>
          <table>
            <caption>Overall estimates and 95% intervals</caption>
            <thead><tr><th>Variation</th><th class="num">Eligible</th><th v-if="outcomeCountLabel" class="num">{{ outcomeCountLabel }}</th><th v-if="!binary" class="num">Observations</th><th class="num">Estimate</th><th class="num">95% interval</th></tr></thead>
            <tbody>
              <tr v-for="r in rows" :key="r.variationKey">
                <th>{{ name(r.variationKey) }}</th>
                <td class="num">{{ count(r.analysis.overall.subjects) }}</td>
                <td v-if="outcomeCountLabel" class="num">{{ count(r.analysis.overall.conversions) }}</td>
                <td v-if="!binary" class="num">{{ count(r.analysis.overall.observationCount) }}</td>
                <td class="num">{{ estimate(r.analysis.overall.estimate) }}</td>
                <td class="num">{{ estimateInterval(r.analysis.overall.interval) }}</td>
              </tr>
            </tbody>
          </table>
        </details>

        <template v-if="comparisons.length">
          <h4 class="ga-subhead">Planned differences</h4>
          <p class="ga-caption">
            Left minus right on all eligible subjects{{ binary ? ', in percentage points' : '' }}{{ cuped ? '; CUPED-adjusted means where the covariate applied' : '' }}.
            Evidence must reach the corrected threshold.
          </p>
          <div class="ga-rows" role="img" aria-label="Planned pairwise differences with comparison intervals">
            <div
              v-for="c in comparisons"
              :key="`${c.leftVariationKey}:${c.rightVariationKey}`"
              class="ga-row"
              :title="`${c.conclusion.replaceAll('_', ' ')}: ${c.reason}`">
              <span class="ga-row-label">{{ name(c.leftVariationKey) }} − {{ name(c.rightVariationKey) }}</span>
              <span class="ga-track ga-track--line">
                <span class="ga-zero" />
                <span
                  v-if="c.interval"
                  class="ga-whisker"
                  :style="span(c.interval.lower, c.interval.upper, differenceRange.min, differenceRange.max)" />
                <span
                  v-if="c.difference != null"
                  class="ga-dot"
                  :style="{ left: pct(c.difference, differenceRange.min, differenceRange.max) }" />
              </span>
              <span class="ga-row-value">{{ difference(c.difference) }}</span>
            </div>
            <div class="ga-row ga-row--axis">
              <span />
              <span class="ga-axis"><span>{{ difference(differenceRange.min) }}</span><span>0</span><span>{{ difference(differenceRange.max) }}</span></span>
              <span />
            </div>
          </div>
          <table>
            <caption>Planned overall differences · corrected comparison family</caption>
            <thead><tr><th>Comparison</th><th class="num">Samples</th><th class="num">Difference</th><th class="num">Interval</th><th class="num">Evidence</th><th class="num">Threshold</th><th>Conclusion</th></tr></thead>
            <tbody>
              <tr v-for="c in comparisons" :key="`${c.leftVariationKey}:${c.rightVariationKey}`">
                <th>{{ name(c.leftVariationKey) }} − {{ name(c.rightVariationKey) }}</th>
                <td class="num">{{ count(c.leftSubjects) }} / {{ count(c.rightSubjects) }}</td>
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
        <h4>Outcomes by population</h4>
        <table>
          <caption>Outcomes for all eligible subjects and for subjects who reached the activation event</caption>
          <thead><tr><th>Variation</th><th>Population</th><th class="num">Subjects</th><th v-if="outcomeCountLabel" class="num">{{ outcomeCountLabel }}</th><th v-if="!binary" class="num">Observations</th><th class="num">Estimate</th><th class="num">95% interval</th></tr></thead>
          <tbody v-for="g in populationRows" :key="g.key" class="ga-group">
            <tr v-for="(p, i) in g.populations" :key="p.label">
              <th>{{ i === 0 ? name(g.key) : '' }}</th>
              <td>{{ p.label }}</td>
              <td class="num">{{ count(p.population.subjects) }}</td>
              <td v-if="outcomeCountLabel" class="num">{{ count(p.population.conversions) }}</td>
              <td v-if="!binary" class="num">{{ count(p.population.observationCount) }}</td>
              <td class="num">{{ estimate(p.population.estimate) }}</td>
              <td class="num">{{ estimateInterval(p.population.interval) }}</td>
            </tr>
            <tr v-if="!g.analysis.activationConfigured" class="ga-note-row">
              <th />
              <td :colspan="populationNoteSpan">{{ noActivationReason(g.analysis) }}</td>
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
        <div class="ga-funnel" role="img" aria-label="Assigned, eligible, reached, engaged, and attributed destination counts">
          <div v-for="g in funnelGroups" :key="g.arm" class="ga-funnel-group">
            <div class="ga-funnel-name">{{ name(g.arm) }}</div>
            <div
              v-for="s in g.stages"
              :key="s.key"
              class="ga-row"
              :title="`${s.label}: ${rate(s.n, s.d)}. ${eventDescription(g.arm)}`">
              <span class="ga-row-label ga-row-label--stage">{{ s.label }}</span>
              <span class="ga-track"><span class="ga-bar" :style="{ width: barWidth(s.n, s.d) }" /></span>
              <span class="ga-row-value">{{ stageValue(s) }}</span>
            </div>
            <p v-if="g.reason" class="ga-funnel-note">{{ g.reason }}</p>
          </div>
        </div>
        <details class="ga-exact">
          <summary>Exact values</summary>
          <table>
            <caption>Funnel counts, rates, and stage denominators</caption>
            <thead><tr><th>Variation</th><th>Stage</th><th class="num">Count</th><th class="num">Rate (numerator / denominator)</th></tr></thead>
            <tbody v-for="g in funnelGroups" :key="g.arm" class="ga-group">
              <tr v-for="(s, i) in g.stages" :key="s.key">
                <th>{{ i === 0 ? name(g.arm) : '' }}</th>
                <td>{{ s.label }}</td>
                <td class="num">{{ count(s.n) }}</td>
                <td class="num">{{ rate(s.n, s.d) }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <section v-else-if="view === 'Reach'" aria-label="Reach versus usefulness" class="ga-pane">
        <h4>Reach versus outcome after activation</h4>
        <svg
          class="ga-chart"
          viewBox="0 0 720 290"
          role="img"
          aria-label="Activation rate versus outcome rate among subjects who reached it, with 95 percent interval whiskers">
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
            class="label">Reached activation / eligible →</text>
          <text :x="REACH_X + 6" y="14" class="label">Outcome / reached ↑</text>
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
            <text :x="reachX(r.analysis.reach ?? 0) + 10" :y="reachY(usefulness(r.analysis)) - 10" class="label">{{ name(r.variationKey) }}</text>
          </g>
        </svg>
        <details class="ga-exact">
          <summary>Exact values</summary>
          <table>
            <caption>Reach and outcome rates after activation</caption>
            <thead><tr><th>Variation</th><th class="num">Reach</th><th class="num">Reach 95% interval</th><th class="num">Outcome / reached</th><th class="num">Outcome 95% interval</th></tr></thead>
            <tbody>
              <tr v-for="r in activeRows" :key="r.variationKey">
                <th>{{ name(r.variationKey) }}</th>
                <td class="num">{{ r.analysis.activated ? rate(r.analysis.activated.subjects, r.analysis.overall.subjects) : '—' }}</td>
                <td class="num">{{ rateInterval(r.analysis.reachInterval) }}</td>
                <td class="num">{{ r.analysis.activated ? rate(r.analysis.activated.conversions, r.analysis.activated.subjects) : '—' }}</td>
                <td class="num">{{ rateInterval(r.analysis.activated?.interval) }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <section v-else-if="view === 'Placement'" aria-label="Placement integrity" class="ga-pane">
        <h4>Assignment by observed placement</h4>
        <p class="ga-caption">Which activation events each variation's eligible subjects produced. An unexpected placement matches another variation's activation event; those subjects stay in their assigned variation.</p>
        <table class="ga-heat">
          <caption>Placement integrity counts within each eligible variation</caption>
          <thead><tr><th>Variation</th><th class="num">Eligible</th><th v-for="n in placementNames" :key="n" class="num">{{ n }}</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.variationKey">
              <th>{{ name(r.variationKey) }}</th>
              <td class="num">{{ count(r.analysis.overall.subjects) }}</td>
              <td
                v-for="key in placementColumns"
                :key="key"
                class="num"
                :class="{ flagged: (key === 'unexpectedOnly' || key === 'both') && (r.analysis.placement?.[key] ?? 0) > 0 }"
                :style="{ background: placementTint(r, key) }">{{ r.analysis.placement ? count(r.analysis.placement[key]) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="view === 'Exposure'" aria-label="Exposure intensity" class="ga-pane">
        <h4>Activation events per subject who reached it</h4>
        <p class="ga-caption">Dot p50 · square p95 · line end p99.</p>
        <div class="ga-rows" role="img" aria-label="Activation event median, p95, and p99 per subject">
          <div v-for="r in activeRows" :key="r.variationKey" class="ga-row">
            <span class="ga-row-label">{{ name(r.variationKey) }}</span>
            <span class="ga-track ga-track--line">
              <span class="ga-whisker" :style="span(0, r.analysis.exposureIntensity?.p99 ?? 0, 0, intensityMax)" />
              <span class="ga-dot" :style="{ left: pct(r.analysis.exposureIntensity?.p50 ?? 0, 0, intensityMax) }" />
              <span class="ga-square" :style="{ left: pct(r.analysis.exposureIntensity?.p95 ?? 0, 0, intensityMax) }" />
            </span>
            <span class="ga-row-value">p99 {{ display(r.analysis.exposureIntensity?.p99) }}</span>
          </div>
        </div>
        <details class="ga-exact">
          <summary>Exact values</summary>
          <table>
            <caption>Logical activation events per subject who reached the activation event</caption>
            <thead><tr><th>Variation</th><th class="num">Reached subjects</th><th class="num">Events</th><th class="num">Mean</th><th class="num">p50</th><th class="num">p95</th><th class="num">p99</th></tr></thead>
            <tbody>
              <tr v-for="r in activeRows" :key="r.variationKey">
                <th>{{ name(r.variationKey) }}</th>
                <td class="num">{{ count(r.analysis.activated?.subjects) }}</td>
                <td class="num">{{ count(r.analysis.exposureEvents) }}</td>
                <td class="num">{{ display(r.analysis.exposureIntensity?.mean) }}</td>
                <td class="num">{{ display(r.analysis.exposureIntensity?.p50) }}</td>
                <td class="num">{{ display(r.analysis.exposureIntensity?.p95) }}</td>
                <td class="num">{{ display(r.analysis.exposureIntensity?.p99) }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <section v-else-if="view === 'Distribution'" aria-label="Outcome distributions" class="ga-pane">
        <div class="ga-pane-head">
          <h4>{{ metricType === 'SESSION_DURATION' ? 'Session length' : 'Event count' }} distribution</h4>
          <div
            v-if="segmentOptions.length > 1"
            class="ga-toggle"
            role="group"
            aria-label="Population">
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
            <text :x="120 + i * 180" y="280" class="label">{{ name(r.key) }}</text>
          </g>
        </svg>
        <details class="ga-exact">
          <summary>Exact values</summary>
          <table>
            <caption>Exact subject-level distribution values</caption>
            <thead><tr><th>Variation</th><th class="num">Subjects</th><th class="num">Observations</th><th class="num">Mean</th><th class="num">SD</th><th class="num">95% interval</th><th v-for="p in percentiles" :key="p" class="num">{{ p }}</th><th class="num">Maximum</th></tr></thead>
            <tbody>
              <tr v-for="r in distributionRows" :key="r.key">
                <th>{{ name(r.key) }}</th>
                <template v-if="r.population">
                  <td class="num">{{ count(r.population.subjects) }}</td>
                  <td class="num">{{ count(r.population.observationCount) }}</td>
                  <td class="num">{{ display(r.population.estimate) }}</td>
                  <td class="num">{{ display(r.population.distribution?.standardDeviation) }}</td>
                  <td class="num">{{ interval(r.population.interval) }}</td>
                  <td v-for="p in percentiles" :key="p" class="num">{{ display(r.population.distribution?.[p]) }}</td>
                  <td class="num">{{ display(r.population.distribution?.maximum) }}</td>
                </template>
                <td v-else :colspan="11" class="ga-muted">{{ noActivationReason(r.analysis) }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <section v-else-if="view === 'Data quality'" aria-label="Data quality diagnostics" class="ga-pane">
        <h4>Data quality and destination attribution</h4>
        <p v-if="exclusions" class="ga-caption">
          Configured exclusions: {{ count(exclusions.excludedPrincipals) }} principals and {{ count(exclusions.excludedInstallations) }} linked installations, removed from every variation.
        </p>
        <dl v-if="hasActivation" class="ga-legend">
          <div><dt>Duplicate deliveries</dt><dd>The same activation event delivered more than once; counted once.</dd></div>
          <div><dt>Other application</dt><dd>Matching activation events from an application outside the configured Application IDs.</dd></div>
          <div><dt>No application</dt><dd>Matching activation events with no application recorded.</dd></div>
        </dl>
        <div class="ga-dq-grid">
          <div v-for="r in rows" :key="r.variationKey" class="ga-dq">
            <div class="ga-dq-name">{{ name(r.variationKey) }}</div>
            <p v-if="r.analysis.placement && (r.analysis.placement.unexpectedOnly || r.analysis.placement.both)" role="status" class="ga-warn">
              Unexpected placement: {{ count(r.analysis.placement.unexpectedOnly) }} unexpected only; {{ count(r.analysis.placement.both) }} observed both. These subjects remain in the assigned variation.
            </p>
            <p v-if="!r.analysis.activationConfigured" class="ga-muted">
              {{ noActivationReason(r.analysis) }} Activation event checks do not apply.
            </p>
            <template v-else>
              <div class="ga-dq-sub">Activation event checks</div>
              <dl class="ga-kv">
                <div><dt>Duplicate deliveries</dt><dd :class="{ flagged: r.analysis.diagnostics.duplicateDeliveries > 0 }">{{ count(r.analysis.diagnostics.duplicateDeliveries) }}</dd></div>
                <div><dt>Other application</dt><dd :class="{ flagged: r.analysis.diagnostics.outsideApplication > 0 }">{{ count(r.analysis.diagnostics.outsideApplication) }}</dd></div>
                <div><dt>No application</dt><dd :class="{ flagged: r.analysis.diagnostics.missingApplication > 0 }">{{ count(r.analysis.diagnostics.missingApplication) }}</dd></div>
              </dl>
              <template v-if="r.analysis.notActivated">
                <div class="ga-dq-sub">Did not reach the activation event</div>
                <dl class="ga-kv">
                  <div><dt>Subjects</dt><dd>{{ count(r.analysis.notActivated.subjects) }}</dd></div>
                  <div v-if="outcomeCountLabel"><dt>{{ outcomeCountLabel }}</dt><dd>{{ count(r.analysis.notActivated.conversions) }}</dd></div>
                  <div v-else><dt>Observations</dt><dd>{{ count(r.analysis.notActivated.observationCount) }}</dd></div>
                  <div><dt>Estimate</dt><dd>{{ estimate(r.analysis.notActivated.estimate) }}</dd></div>
                </dl>
                <p class="ga-muted">Outcomes from subjects who never reached the activation event. For a goal tied to the placement, a nonzero count means the goal also matches events elsewhere.</p>
              </template>
            </template>
            <template v-if="r.analysis.attribution">
              <div class="ga-dq-sub">Destination attribution</div>
              <dl class="ga-kv">
                <div class="wide"><dt>Verified destination</dt><dd>{{ rate(r.analysis.attribution.attributedSubjects, r.analysis.attribution.engagedSubjects) }} engaged subjects</dd></div>
                <div class="wide"><dt>95% interval</dt><dd>{{ rateInterval(r.analysis.attribution.interval) }}</dd></div>
                <div><dt>Logical interactions</dt><dd>{{ count(r.analysis.attribution.interactions) }}</dd></div>
                <div><dt>Matched interactions</dt><dd>{{ count(r.analysis.attribution.attributedInteractions) }}</dd></div>
                <div><dt>Unresolved targets</dt><dd :class="{ flagged: r.analysis.attribution.unresolvedTargets > 0 }">{{ count(r.analysis.attribution.unresolvedTargets) }}</dd></div>
                <div><dt>Unsupported content types</dt><dd :class="{ flagged: r.analysis.attribution.unsupportedTypes > 0 }">{{ count(r.analysis.attribution.unsupportedTypes) }}</dd></div>
                <div><dt>Missing route slugs</dt><dd :class="{ flagged: r.analysis.attribution.missingSlugs > 0 }">{{ count(r.analysis.attribution.missingSlugs) }}</dd></div>
                <div><dt>Missing sessions</dt><dd :class="{ flagged: r.analysis.attribution.missingSessions > 0 }">{{ count(r.analysis.attribution.missingSessions) }}</dd></div>
                <div><dt>Unmatched destinations</dt><dd>{{ count(r.analysis.attribution.unmatchedDestinations) }}</dd></div>
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
.ga-note,
.ga-caption,
.ga-muted {
  font-size: 11.5px;
  color: var(--fg-3);
  line-height: 1.5;
}

.ga-note,
.ga-caption,
.ga-muted {
  margin: 0;
}

.ga-note {
  margin-top: 4px;
}

.ga-note strong {
  color: var(--fg-1);
  font-weight: 500;
}

.ga-alert {
  margin: 10px 14px 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.5;
  background: color-mix(in srgb, #f59e42 10%, var(--bg-1));
  border: 1px solid color-mix(in srgb, #f59e42 35%, transparent);
  border-radius: var(--r-sm, 6px);
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

/* Bar rows mirror the results bar chart: fixed label and value columns around a fluid track. */
.ga-rows,
.ga-funnel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ga-row {
  display: grid;
  grid-template-columns: 180px 1fr 110px;
  align-items: center;
  gap: 10px;
}

.ga-row-label {
  font-size: 12px;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ga-row-label--stage {
  padding-left: 10px;
  color: var(--fg-2);
}

.ga-row-value {
  font-size: 12px;
  color: var(--fg-0);
  text-align: right;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ga-track {
  position: relative;
  height: 8px;
  background: var(--bg-3);
  border-radius: 4px;
  overflow: hidden;
}

.ga-track--line {
  height: 14px;
  background: transparent;
  overflow: visible;
}

.ga-track--line::before {
  content: '';
  position: absolute;
  inset: 50% 0 auto;
  height: 1px;
  background: color-mix(in oklch, var(--line) 70%, transparent);
}

.ga-bar {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--ga-accent);
  border-radius: 4px;
  transition: width 0.2s ease;
}

.ga-whisker {
  position: absolute;
  top: 50%;
  height: 2px;
  margin-top: -1px;
  background: var(--ga-accent);
  border-radius: 1px;
}

.ga-dot,
.ga-square {
  position: absolute;
  top: 50%;
  width: 10px;
  height: 10px;
  margin: -5px 0 0 -5px;
  background: var(--ga-accent);
  border: 2px solid var(--bg-1);
  border-radius: 50%;
}

.ga-square {
  width: 8px;
  height: 8px;
  margin: -4px 0 0 -4px;
  border-radius: 1px;
  border-width: 0;
}

.ga-zero {
  position: absolute;
  left: 50%;
  top: -3px;
  bottom: -3px;
  border-left: 1px dashed var(--fg-3);
}

.ga-row--axis {
  margin-top: -2px;
}

.ga-axis {
  display: flex;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--fg-3);
  font-family: var(--font-mono, ui-monospace, monospace);
}

.ga-funnel {
  gap: 14px;
}

.ga-funnel-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ga-funnel-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.ga-funnel-note {
  margin: 0;
  padding-left: 10px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.ga-exact > summary {
  cursor: pointer;
  width: fit-content;
  font-size: 11.5px;
  color: var(--fg-3);
  user-select: none;
}

.ga-exact > summary:hover {
  color: var(--fg-1);
}

.ga-exact[open] > summary {
  margin-bottom: 8px;
}

/* Remaining SVG charts render at their viewBox size so labels stay 11px. */
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

.ga-chart .tick {
  fill: var(--fg-3);
  font-size: 10.5px;
}

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

.point {
  fill: var(--ga-accent);
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
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12px;
}

/* Section headings and summaries carry the visible description; captions stay for assistive technology. */
caption {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
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

tbody td,
tbody th {
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

tbody:last-child tr:last-child td,
tbody:last-child tr:last-child th {
  border-bottom: none;
}

tbody.ga-group + tbody.ga-group tr:first-child > * {
  border-top: 1px solid color-mix(in oklch, var(--line) 50%, transparent);
}

tbody th {
  font-weight: 500;
  color: var(--fg-0);
  white-space: nowrap;
}

.ga-note-row td {
  color: var(--fg-3);
  font-size: 11.5px;
}

.num {
  text-align: right;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

td.flagged {
  color: #f59e42;
  font-weight: 600;
}

.ga-heat td.num {
  border-left: 2px solid var(--bg-1);
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

.ga-legend {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 6px 16px;
  margin: 0;
}

.ga-legend > div {
  font-size: 11.5px;
  line-height: 1.5;
}

.ga-legend dt {
  display: inline;
  color: var(--fg-1);
  font-weight: 500;
}

.ga-legend dt::after {
  content: ': ';
}

.ga-legend dd {
  display: inline;
  margin: 0;
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
  padding-top: 8px;
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
