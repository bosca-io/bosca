<script setup lang="ts">
import gql from 'graphql-tag'

/**
 * Live API health for one of a release's analytics services: response-code mix and error rate from the
 * `http.<service>.<2xx|4xx|5xx>` COUNTERs over the last hour. One instance per service so the parent can
 * `v-for` over the release's services.
 */

const props = defineProps<{ service: string; projectKey: string }>()

const { useAsyncQuery } = useGraphQL()

interface CounterValue { time: string; value: number }
interface Counter { value: number; values: CounterValue[] }

const httpGql = gql`
  query SvcHttp($id2: ID!, $id4: ID!, $id5: ID!) {
    analytics {
      counters {
        c2: byId(id: $id2) { value(window: 60) values(window: 60) { time value } }
        c4: byId(id: $id4) { value(window: 60) values(window: 60) { time value } }
        c5: byId(id: $id5) { value(window: 60) values(window: 60) { time value } }
      }
    }
  }
`
const { data } = useAsyncQuery<{ analytics: { counters: { c2: Counter; c4: Counter; c5: Counter } } }>(
  `svc-http-${props.service}`, httpGql, {
    id2: `http.${props.service}.2xx`, id4: `http.${props.service}.4xx`, id5: `http.${props.service}.5xx`,
  }, { server: false })
const counters = computed(() => data.value?.analytics?.counters)
const ok2xx = computed(() => counters.value?.c2?.value ?? 0)
const client4xx = computed(() => counters.value?.c4?.value ?? 0)
const server5xx = computed(() => counters.value?.c5?.value ?? 0)
const total = computed(() => ok2xx.value + client4xx.value + server5xx.value)
const errorRate = computed(() => (total.value ? ((client4xx.value + server5xx.value) / total.value) * 100 : 0))

const errorRateSpark = computed(() => {
  const s2 = counters.value?.c2?.values ?? []
  const s4 = counters.value?.c4?.values ?? []
  const s5 = counters.value?.c5?.values ?? []
  const n = Math.max(s2.length, s4.length, s5.length)
  const out: number[] = []
  for (let i = 0; i < n; i++) {
    const good = s2[i]?.value ?? 0
    const bad = (s4[i]?.value ?? 0) + (s5[i]?.value ?? 0)
    const t = good + bad
    out.push(t ? (bad / t) * 100 : 0)
  }
  // A service with no traffic history still gets a graph — a flat zero error-rate baseline reads the
  // same as its 0.00% figure, and rows with and without traffic should look alike.
  return out.length >= 2 ? out : Array.from({ length: 24 }, () => 0)
})

function rateColor(pct: number): string {
  if (pct >= 5) return 'var(--err, #f87171)'
  if (pct >= 1) return '#ffb547'
  return 'var(--ok, #34d399)'
}
function compact(n: number): string { return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : `${n}` }
</script>

<template>
  <div class="health-block">
    <div class="hb-top">
      <span class="hb-dot" :style="{ background: rateColor(errorRate) }" />
      <span class="hb-name mono">{{ service }}</span>
      <span class="hb-scope">{{ projectKey }}</span>
    </div>
    <div class="hb-bottom">
      <span class="hb-metrics">
        <span class="tabular" :style="{ color: rateColor(errorRate) }">{{ errorRate.toFixed(2) }}%</span>
        <span class="hb-sep">·</span>
        <span class="tabular">{{ compact(ok2xx) }} ok</span>
        <template v-if="client4xx + server5xx">
          <span class="hb-sep">·</span>
          <span class="tabular" style="color: var(--err, #f87171)">{{ compact(client4xx + server5xx) }} errors</span>
        </template>
      </span>
      <Sparkline
        :values="errorRateSpark"
        :accent="rateColor(errorRate)"
        :width="56"
        :height="16" />
    </div>
  </div>
</template>

<style scoped>
.health-block { display: flex; flex-direction: column; gap: 3px; padding: 7px 0; min-width: 0; }
.health-block + .health-block { border-top: 1px solid color-mix(in oklch, var(--line) 40%, transparent); }
.hb-top { display: flex; align-items: center; gap: 7px; min-width: 0; }
.hb-dot { width: 7px; height: 7px; border-radius: 50%; flex: 0 0 auto; }
.hb-name { font-size: 12.5px; color: var(--fg-0); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hb-scope { margin-left: auto; font-size: 10px; color: var(--fg-3); flex: 0 0 auto; }
.hb-bottom { display: flex; align-items: center; gap: 8px; padding-left: 14px; min-height: 16px; }
.hb-metrics { display: flex; gap: 6px; align-items: baseline; font-size: 11.5px; color: var(--fg-2); min-width: 0; }
.hb-sep { color: var(--fg-3); }
.hb-bottom > :last-child { margin-left: auto; }
</style>