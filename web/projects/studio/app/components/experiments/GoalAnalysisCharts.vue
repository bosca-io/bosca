<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GoalActivation, GoalPopulationAnalysis, MetricInterval, PlannedComparison } from '~/utils/goalActivation'
const props = defineProps<{
  rows: { variationKey: string; assignments: number; goalAnalysis?: GoalPopulationAnalysis | null }[]
  comparisons?: PlannedComparison[]
  metricType: string
  activation?: GoalActivation | null
}>()
const segment = ref<'overall' | 'activated' | 'notActivated'>('overall')
const funnelMode = ref<'counts' | 'percentages'>('counts')
const rows = computed(() => props.rows.filter(r => r.goalAnalysis).map(r => ({ ...r, analysis: r.goalAnalysis! })))
const activeRows = computed(() => rows.value.filter(r => r.analysis.activationConfigured))
const largestEligible = computed(() => Math.max(1, ...rows.value.map(r => r.analysis.overall.subjects)))
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
const display = (v: number | null | undefined) => v == null ? 'Not available' : v.toLocaleString(undefined, { maximumFractionDigits: 3 })
const percent = (v: number | null | undefined) => v == null ? 'Not available' : `${(v * 100).toFixed(2)}%`
const interval = (v: MetricInterval | null | undefined) => v ? `${display(v.lower)} to ${display(v.upper)}` : 'Not available'
const rate = (n: number | null, d: number | null) => n == null || d == null ? 'Not applicable' : d > 0 ? `${percent(n / d)} (${n} / ${d})` : `Not available (${n} / ${d})`
const populationLabel = (a: GoalPopulationAnalysis) => a.notApplicable ? 'Not applicable' : a.activationConfigured ? 'Configured' : 'Not configured'
const stages = computed(() => rows.value.flatMap(r => {
  const a = r.analysis
  const engaged = a.attribution?.engagedSubjects ?? (props.metricType === 'UNIQUE_CONVERSION' ? a.activated?.conversions ?? null : null)
  return [
    { arm: r.variationKey, label: 'Assigned', n: r.assignments, d: r.assignments },
    { arm: r.variationKey, label: 'Eligible', n: a.overall.subjects, d: r.assignments },
    { arm: r.variationKey, label: 'Observed / exposed', n: a.activated?.subjects ?? null, d: a.overall.subjects },
    { arm: r.variationKey, label: a.attribution ? 'Engaged' : 'Outcome after exposure', n: engaged, d: a.activated?.subjects ?? null },
    { arm: r.variationKey, label: 'Attributed destination', n: a.attribution?.attributedSubjects ?? null, d: engaged },
  ]
}))
const funnelMax = computed(() => Math.max(1, ...stages.value.map(s => s.n ?? 0)))
const width = (n: number | null, d: number | null) => n == null ? 0 : funnelMode.value === 'counts' ? 400 * n / funnelMax.value : d ? 400 * n / d : 0
const estimateRange = computed(() => {
  const values = rows.value.flatMap(r => [r.analysis.overall.estimate ?? 0, r.analysis.overall.interval?.lower ?? 0, r.analysis.overall.interval?.upper ?? 0])
  return { min: Math.min(0, ...values), max: Math.max(0.001, ...values) }
})
const estimateX = (v: number) => 180 + 430 * (v - estimateRange.value.min) / (estimateRange.value.max - estimateRange.value.min)
const differenceRange = computed(() => {
  const values = (props.comparisons ?? []).flatMap(c => [c.difference ?? 0, c.interval?.lower ?? 0, c.interval?.upper ?? 0])
  return { min: Math.min(-0.001, ...values), max: Math.max(0.001, ...values) }
})
const differenceX = (v: number) => 200 + 410 * (v - differenceRange.value.min) / (differenceRange.value.max - differenceRange.value.min)
const populationNames = { overall: 'Overall', activated: 'Exposed · descriptive', notActivated: 'Not exposed · descriptive' } as const
const populationRows = computed(() => rows.value.flatMap(r => (['overall', 'activated', 'notActivated'] as const).map(key => ({
  variationKey: r.variationKey, label: populationNames[key], population: r.analysis[key], analysis: r.analysis,
}))))
const percentiles = ['p50', 'p75', 'p90', 'p95', 'p99'] as const
const distributionRows = computed(() => rows.value.map(r => ({ key: r.variationKey, population: r.analysis[segment.value], analysis: r.analysis })))
const distributionMax = computed(() => Math.max(props.metricType === 'SESSION_DURATION' ? 300 : 1, ...distributionRows.value.map(r => r.population?.distribution?.p99 ?? 0)))
const dy = (v: number) => 240 - 210 * v / distributionMax.value
const placementColumns = ['expectedOnly', 'unexpectedOnly', 'both', 'neither'] as const
const placementNames = ['Expected only', 'Unexpected only', 'Both', 'Neither']
const intensityMax = computed(() => Math.max(1, ...activeRows.value.map(r => r.analysis.exposureIntensity?.p99 ?? 0)))
const usefulness = (a: GoalPopulationAnalysis) => a.activated?.subjects ? a.activated.conversions / a.activated.subjects : 0
</script>
<template>
  <section v-if="rows.length" class="goal-analysis-charts" aria-label="Goal population analysis">
    <p class="notice">Overall outcomes use all eligible subjects. Exposed and not-exposed outcomes are descriptive: reaching a placement happens after assignment.</p>
    <p class="timestamp">Aggregation cutoff: {{ rows[0]?.analysis.cutoff }}</p>
    <section aria-label="Overall outcome comparison">
      <h4>Overall outcome · all eligible</h4>
      <svg :viewBox="`0 0 660 ${rows.length * 40 + 65}`" role="img" aria-label="Overall estimates and 95 percent intervals">
        <g v-for="(r, i) in rows" :key="r.variationKey" :transform="`translate(0, ${35 + i * 40})`">
          <text x="5" y="4">{{ r.variationKey }}</text>
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
            r="5" />
        </g>
        <text :x="estimateX(estimateRange.min)" :y="rows.length * 40 + 40">{{ metricType === 'UNIQUE_CONVERSION' ? percent(estimateRange.min) : display(estimateRange.min) }}</text>
        <text :x="estimateX(estimateRange.max)" :y="rows.length * 40 + 40" text-anchor="end">{{ metricType === 'UNIQUE_CONVERSION' ? percent(estimateRange.max) : display(estimateRange.max) }}</text>
      </svg>
      <table><caption>Overall estimates and 95% intervals</caption><thead><tr><th>Variation</th><th>Eligible</th><th>Conversions</th><th>Observations</th><th>Estimate</th><th>95% interval</th></tr></thead><tbody><tr v-for="r in rows" :key="r.variationKey"><th>{{ r.variationKey }}</th><td>{{ r.analysis.overall.subjects }}</td><td>{{ r.analysis.overall.conversions }}</td><td>{{ r.analysis.overall.observationCount }}</td><td>{{ display(r.analysis.overall.estimate) }}</td><td>{{ interval(r.analysis.overall.interval) }}</td></tr></tbody></table>
      <svg
        v-if="comparisons?.length"
        :viewBox="`0 0 660 ${comparisons.length * 40 + 65}`"
        role="img"
        aria-label="Planned pairwise differences with comparison intervals">
        <line
          :x1="differenceX(0)"
          :x2="differenceX(0)"
          y1="10"
          :y2="comparisons.length * 40 + 20"
          class="floor" />
        <g v-for="(c, i) in comparisons" :key="`${c.leftVariationKey}:${c.rightVariationKey}`" :transform="`translate(0, ${35 + i * 40})`">
          <text x="5" y="4">{{ c.leftVariationKey }} − {{ c.rightVariationKey }}</text>
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
            r="5" />
          <title>{{ c.conclusion.replaceAll('_', ' ') }}: {{ c.reason }}</title>
        </g>
        <text :x="differenceX(differenceRange.min)" :y="comparisons.length * 40 + 40">{{ display(differenceRange.min) }}</text>
        <text :x="differenceX(0)" :y="comparisons.length * 40 + 40" text-anchor="middle">0</text>
        <text :x="differenceX(differenceRange.max)" :y="comparisons.length * 40 + 40" text-anchor="end">{{ display(differenceRange.max) }}</text>
      </svg>
      <table v-if="comparisons?.length"><caption>Planned overall differences · corrected comparison family</caption><thead><tr><th>Comparison</th><th>Samples</th><th>Difference</th><th>Interval</th><th>Evidence</th><th>Threshold</th><th>Conclusion</th></tr></thead><tbody><tr v-for="c in comparisons" :key="`${c.leftVariationKey}:${c.rightVariationKey}`"><th>{{ c.leftVariationKey }} − {{ c.rightVariationKey }}</th><td>{{ c.leftSubjects }} / {{ c.rightSubjects }}</td><td>{{ display(c.difference) }}</td><td>{{ interval(c.interval) }}</td><td>{{ c.evidenceLabel }} {{ percent(c.evidence) }}</td><td>{{ percent(c.threshold) }}</td><td :title="c.reason">{{ c.conclusion.replaceAll('_', ' ') }}: {{ c.reason }}</td></tr></tbody></table>
    </section>
    <template v-if="rows.some(r => r.analysis.activationConfigured || r.analysis.notApplicable)">
      <section aria-label="Population outcomes">
        <h4>Overall and descriptive population outcomes</h4>
        <table><caption>Outcomes from eligibility, after exposure, and among subjects never exposed</caption><thead><tr><th>Variation</th><th>Population</th><th>Subjects</th><th>Conversions</th><th>Observations</th><th>Estimate</th><th>95% interval</th></tr></thead><tbody><tr v-for="r in populationRows" :key="`${r.variationKey}:${r.label}`"><th>{{ r.variationKey }}</th><td>{{ r.label }}</td><td>{{ r.population?.subjects ?? populationLabel(r.analysis) }}</td><td>{{ r.population?.conversions ?? 'Not applicable' }}</td><td>{{ r.population?.observationCount ?? 'Not applicable' }}</td><td>{{ r.population ? display(r.population.estimate) : populationLabel(r.analysis) }}</td><td>{{ r.population ? interval(r.population.interval) : populationLabel(r.analysis) }}</td></tr></tbody></table>
      </section>
      <section aria-label="Variation funnel">
        <h4>Variation funnel</h4><label>Display <select v-model="funnelMode"><option value="counts">Counts</option><option value="percentages">Percentages</option></select></label>
        <svg :viewBox="`0 0 800 ${stages.length * 30 + 15}`" role="img" aria-label="Assigned, eligible, observed, engaged, and attributed destination counts">
          <g v-for="(s, i) in stages" :key="`${s.arm}:${s.label}`" :transform="`translate(0, ${i * 30 + 20})`"><text x="0" y="4">{{ s.arm }} · {{ s.label }}</text><rect
            x="265"
            y="-9"
            :width="width(s.n, s.d)"
            height="16" /><text x="680" y="4">{{ s.n === null ? 'N/A' : funnelMode === 'counts' ? s.n : percent(s.d ? s.n / s.d : null) }}</text><title>{{ s.label }}: {{ rate(s.n, s.d) }}. {{ eventDescription(s.arm) }}</title></g>
        </svg>
        <table><caption>Funnel counts, rates, and stage denominators</caption><thead><tr><th>Variation</th><th>Stage</th><th>Count</th><th>Rate (numerator / denominator)</th></tr></thead><tbody><tr v-for="s in stages" :key="`${s.arm}:${s.label}`"><th>{{ s.arm }}</th><td>{{ s.label }}</td><td>{{ s.n ?? 'Not applicable' }}</td><td>{{ rate(s.n, s.d) }}</td></tr></tbody></table>
      </section>
      <section v-if="metricType === 'UNIQUE_CONVERSION'" aria-label="Reach versus usefulness">
        <h4>Reach versus usefulness · descriptive</h4>
        <svg viewBox="0 0 660 300" role="img" aria-label="Exposure rate versus outcome rate among exposed subjects, with 95 percent interval whiskers">
          <path d="M60 20 V250 H620" class="axis" /><text x="200" y="290">Exposure / eligible →</text><text x="65" y="15">Outcome / exposed ↑</text>
          <g v-for="(r, i) in activeRows" :key="r.variationKey">
            <line
              v-if="r.analysis.reachInterval"
              :x1="60 + r.analysis.reachInterval.lower * 550"
              :x2="60 + r.analysis.reachInterval.upper * 550"
              :y1="250 - usefulness(r.analysis) * 220"
              :y2="250 - usefulness(r.analysis) * 220"
              class="whisker" />
            <line
              v-if="r.analysis.activated?.interval"
              :x1="60 + (r.analysis.reach ?? 0) * 550"
              :x2="60 + (r.analysis.reach ?? 0) * 550"
              :y1="250 - r.analysis.activated.interval.lower * 220"
              :y2="250 - r.analysis.activated.interval.upper * 220"
              class="whisker" />
            <circle
              :cx="60 + (r.analysis.reach ?? 0) * 550"
              :cy="250 - usefulness(r.analysis) * 220"
              :r="4 + 16 * Math.sqrt(r.analysis.overall.subjects / largestEligible)"
              :class="`series-${i % 3}`" /><text :x="70 + (r.analysis.reach ?? 0) * 550" :y="240 - usefulness(r.analysis) * 220">{{ r.variationKey }}</text>
          </g>
        </svg>
        <table><caption>Reach and exposed outcome rates</caption><thead><tr><th>Variation</th><th>Reach</th><th>Reach 95% interval</th><th>Outcome / exposed</th><th>Outcome 95% interval</th></tr></thead><tbody><tr v-for="r in rows" :key="r.variationKey"><th>{{ r.variationKey }}</th><td>{{ r.analysis.activated ? rate(r.analysis.activated.subjects, r.analysis.overall.subjects) : populationLabel(r.analysis) }}</td><td>{{ interval(r.analysis.reachInterval) }}</td><td>{{ r.analysis.activated ? rate(r.analysis.activated.conversions, r.analysis.activated.subjects) : populationLabel(r.analysis) }}</td><td>{{ interval(r.analysis.activated?.interval) }}</td></tr></tbody></table>
      </section>
      <section aria-label="Placement integrity">
        <h4>Assignment by observed placement</h4>
        <svg :viewBox="`0 0 760 ${rows.length * 50 + 50}`" role="img" aria-label="Expected only, unexpected only, both, and neither observed placement">
          <text
            v-for="(name, i) in placementNames"
            :key="name"
            :x="180 + i * 140"
            y="20">{{ name }}</text>
          <g v-for="(r, i) in rows" :key="r.variationKey" :transform="`translate(0, ${40 + i * 50})`"><text x="0" y="25">{{ r.variationKey }}</text><g v-for="(key, j) in placementColumns" :key="key"><rect
            :x="170 + j * 140"
            y="0"
            width="130"
            height="38"
            :fill-opacity=".1 + .7 * (r.analysis.placement?.[key] ?? 0) / Math.max(1, r.analysis.overall.subjects)" /><text :x="180 + j * 140" y="25">{{ r.analysis.placement?.[key] ?? 'N/A' }}</text></g></g>
        </svg>
        <table><caption>Placement integrity counts within each eligible arm</caption><thead><tr><th>Variation</th><th>Eligible</th><th v-for="name in placementNames" :key="name">{{ name }}</th></tr></thead><tbody><tr v-for="r in rows" :key="r.variationKey"><th>{{ r.variationKey }}</th><td>{{ r.analysis.overall.subjects }}</td><td v-for="key in placementColumns" :key="key">{{ r.analysis.placement?.[key] ?? 'Not applicable' }}</td></tr></tbody></table>
      </section>
      <section aria-label="Exposure intensity">
        <h4>Exposure intensity · impressions per exposed subject</h4>
        <svg :viewBox="`0 0 660 ${activeRows.length * 45 + 40}`" role="img" aria-label="Exposure intensity median, p95, and p99">
          <g v-for="(r, i) in activeRows" :key="r.variationKey" :transform="`translate(0, ${35 + i * 45})`"><text x="0" y="4">{{ r.variationKey }}</text><line
            x1="150"
            :x2="150 + 440 * (r.analysis.exposureIntensity?.p99 ?? 0) / intensityMax"
            y1="0"
            y2="0"
            class="whisker" /><circle :cx="150 + 440 * (r.analysis.exposureIntensity?.p50 ?? 0) / intensityMax" r="5" /><rect
              :x="146 + 440 * (r.analysis.exposureIntensity?.p95 ?? 0) / intensityMax"
              y="-4"
              width="8"
              height="8" /><text x="600" y="4">p99 {{ r.analysis.exposureIntensity?.p99 ?? '—' }}</text></g>
        </svg>
        <table><caption>Logical exposure events · circle p50, square p95, line end p99</caption><thead><tr><th>Variation</th><th>Exposed subjects</th><th>Events</th><th>Mean</th><th>p50</th><th>p95</th><th>p99</th></tr></thead><tbody><tr v-for="r in rows" :key="r.variationKey"><th>{{ r.variationKey }}</th><td>{{ r.analysis.activated?.subjects ?? populationLabel(r.analysis) }}</td><td>{{ r.analysis.exposureEvents ?? 'Not applicable' }}</td><td>{{ display(r.analysis.exposureIntensity?.mean) }}</td><td>{{ display(r.analysis.exposureIntensity?.p50) }}</td><td>{{ display(r.analysis.exposureIntensity?.p95) }}</td><td>{{ display(r.analysis.exposureIntensity?.p99) }}</td></tr></tbody></table>
      </section>
    </template>
    <section v-if="metricType !== 'UNIQUE_CONVERSION'" aria-label="Outcome distributions">
      <h4>{{ metricType === 'SESSION_DURATION' ? 'Session Length' : 'Event count' }} distribution</h4>
      <label>Population <select v-model="segment"><option value="overall">Overall</option><option value="activated">Exposed (descriptive)</option><option value="notActivated">Not exposed (descriptive)</option></select></label>
      <svg viewBox="0 0 660 285" role="img" aria-label="Percentile profiles p50, p75, p90, p95, and p99">
        <line
          v-if="metricType === 'SESSION_DURATION'"
          x1="70"
          x2="600"
          :y1="dy(300)"
          :y2="dy(300)"
          class="floor" /><text v-if="metricType === 'SESSION_DURATION'" x="72" :y="dy(300) - 5">300s inactivity floor</text>
        <g v-for="(r, i) in distributionRows" :key="r.key"><polyline v-if="r.population?.distribution" :points="percentiles.map((p, j) => `${80 + j * 125},${dy(r.population!.distribution![p])}`).join(' ')" :class="`profile series-${i % 3}`" /><text :x="80 + i * 180" y="275">{{ i + 1 }} · {{ r.key }}</text><g v-for="(p, j) in percentiles" :key="p"><text v-if="r.population?.distribution" :x="80 + j * 125" :y="dy(r.population.distribution[p]) - 5">{{ i + 1 }}</text></g></g>
        <text
          v-for="(p, j) in percentiles"
          :key="p"
          :x="80 + j * 125"
          y="255">{{ p }}</text>
      </svg>
      <table><caption>Exact subject-level distribution values</caption><thead><tr><th>Variation</th><th>Subjects</th><th>Observations</th><th>Mean</th><th>SD</th><th>95% interval</th><th v-for="p in percentiles" :key="p">{{ p }}</th><th>Maximum</th></tr></thead><tbody><tr v-for="r in distributionRows" :key="r.key"><th>{{ r.key }}</th><td>{{ r.population?.subjects ?? populationLabel(r.analysis) }}</td><td>{{ r.population?.observationCount ?? 'Not applicable' }}</td><td>{{ r.population ? display(r.population.estimate) : populationLabel(r.analysis) }}</td><td>{{ display(r.population?.distribution?.standardDeviation) }}</td><td>{{ r.population ? interval(r.population.interval) : populationLabel(r.analysis) }}</td><td v-for="p in percentiles" :key="p">{{ display(r.population?.distribution?.[p]) }}</td><td>{{ display(r.population?.distribution?.maximum) }}</td></tr></tbody></table>
    </section>
    <section aria-label="Data quality diagnostics">
      <h4>Data quality and destination attribution</h4>
      <div v-for="r in rows" :key="r.variationKey" class="diagnostics">
        <strong>{{ r.variationKey }}</strong>
        <p v-if="r.analysis.placement && (r.analysis.placement.unexpectedOnly || r.analysis.placement.both)" role="status">Unexpected exposure: {{ r.analysis.placement.unexpectedOnly }} unexpected only; {{ r.analysis.placement.both }} observed both. These subjects remain in the assigned arm.</p>
        <p>Excluded principals: {{ r.analysis.diagnostics.excludedPrincipals }}; linked installations: {{ r.analysis.diagnostics.excludedInstallations }}. Duplicate deliveries: {{ r.analysis.diagnostics.duplicateDeliveries }}. Outside application scope: {{ r.analysis.diagnostics.outsideApplication }}; missing application: {{ r.analysis.diagnostics.missingApplication }}.</p>
        <p v-if="r.analysis.attribution">Verified destination: {{ rate(r.analysis.attribution.attributedSubjects, r.analysis.attribution.engagedSubjects) }} engaged subjects; 95% interval {{ interval(r.analysis.attribution.interval) }}. Logical interactions: {{ r.analysis.attribution.interactions }}; matched: {{ r.analysis.attribution.attributedInteractions }}. Unresolved targets: {{ r.analysis.attribution.unresolvedTargets }}; unsupported content types: {{ r.analysis.attribution.unsupportedTypes }}; missing route slugs: {{ r.analysis.attribution.missingSlugs }}; missing sessions: {{ r.analysis.attribution.missingSessions }}; unmatched destinations: {{ r.analysis.attribution.unmatchedDestinations }}.</p>
      </div>
    </section>
  </section>
</template>
<style scoped>
.goal-analysis-charts { display: grid; gap: 24px; margin: 16px 0; color: var(--fg-1); }
section { min-width: 0; overflow-x: auto; }
h4 { margin: 0 0 12px; font-size: 14px; }
p, label { font-size: 12px; line-height: 1.6; }
.notice { border-left: 3px solid var(--brand-2); padding: 8px 12px; background: var(--bg-2); }
.timestamp { color: var(--fg-3); }
svg { width: 100%; max-height: 520px; fill: var(--brand-2, #6b9fd4); }
svg text { fill: var(--fg-1); font-size: 12px; }
.whisker, .axis { stroke: var(--fg-2); stroke-width: 2; fill: none; }
.floor { stroke: var(--fg-3); stroke-dasharray: 5 5; }
.profile { fill: none; stroke: var(--brand-2, #6b9fd4); stroke-width: 2; }
.series-1 { stroke-dasharray: 7 3; stroke: #b78bdd; fill: #b78bdd; }
.series-2 { stroke-dasharray: 2 3; stroke: #d6a767; fill: #d6a767; }
.profile.series-1, .profile.series-2 { fill: none; }
table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 12px; }
caption { text-align: left; color: var(--fg-3); padding: 8px 0; }
th, td { border-bottom: 1px solid var(--border); padding: 8px; text-align: left; vertical-align: top; }
select { background: var(--bg-2); color: var(--fg-1); padding: 6px; border: 1px solid var(--border); border-radius: 5px; }
.diagnostics { padding: 10px 0; border-bottom: 1px solid var(--border); }
</style>
