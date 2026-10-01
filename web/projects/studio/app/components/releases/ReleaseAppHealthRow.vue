<script setup lang="ts">
import gql from 'graphql-tag'

/**
 * Live health for one of a release's analytics applications: active sessions (`sessions.<appId>` GAUGE)
 * and open/crash issues (error groups filtered by appId). One instance per app — the composable lives in
 * this child's setup, so the parent can `v-for` over the release's apps.
 */

const props = defineProps<{ applicationId: string; projectKey: string }>()

const { useAsyncQuery } = useGraphQL()

interface CounterValue { time: string; value: number }
interface Counter { value: number; values: CounterValue[] }
interface ErrGroup { fatal: boolean; eventCount: number }

const sessionsGql = gql`
  query AppSessions($id: ID!) {
    analytics { counters { byId(id: $id) { value(window: 2) values(window: 24) { time value } } } }
  }
`
const { data: sessionsData } = useAsyncQuery<{ analytics: { counters: { byId: Counter } } }>(
  `app-sessions-${props.applicationId}`, sessionsGql, { id: `sessions.${props.applicationId}` }, { server: false })
const sessions = computed(() => sessionsData.value?.analytics?.counters?.byId)
const activeSessions = computed(() => sessions.value?.value ?? 0)
// An app with no recorded sessions still gets a graph — a flat zero baseline is the same fact as
// "0 sessions" over time, and rows with and without traffic should read alike.
const sessionSpark = computed(() => {
  const values = (sessions.value?.values ?? []).map(v => v.value)
  return values.length >= 2 ? values : Array.from({ length: 24 }, () => 0)
})

const errorsGql = gql`
  query AppErrors($appId: String!) {
    analytics { errors { groups(filter: { appId: $appId, status: OPEN }, limit: 50) { total edges { fatal eventCount } } } }
  }
`
const { data: errData } = useAsyncQuery<{ analytics: { errors: { groups: { total: number; edges: ErrGroup[] } } } }>(
  `app-errors-${props.applicationId}`, errorsGql, { appId: props.applicationId }, { server: false })
const openIssues = computed(() => errData.value?.analytics?.errors?.groups?.total ?? 0)
const crashIssues = computed(() => (errData.value?.analytics?.errors?.groups?.edges ?? []).filter(g => g.fatal).length)

function compact(n: number): string { return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : `${n}` }

/** Crash red > open-issues amber > live green > idle grey — the same at-a-glance anatomy as services. */
const dotColor = computed(() => {
  if (crashIssues.value > 0) return 'var(--err, #f87171)'
  if (openIssues.value > 0) return '#ffb547'
  if (activeSessions.value > 0) return 'var(--ok, #34d399)'
  return 'var(--fg-3)'
})
</script>

<template>
  <div class="health-block">
    <div class="hb-top">
      <span class="hb-dot" :style="{ background: dotColor }" />
      <span class="hb-name mono">{{ applicationId }}</span>
      <span class="hb-scope">{{ projectKey }}</span>
    </div>
    <div class="hb-bottom">
      <span class="hb-metrics">
        <span class="tabular">{{ compact(activeSessions) }} sessions</span>
        <span class="hb-sep">·</span>
        <span class="tabular">{{ openIssues.toLocaleString() }} open</span>
        <template v-if="crashIssues">
          <span class="hb-sep">·</span>
          <span class="tabular" style="color: var(--err, #f87171)">{{ crashIssues }} crashes</span>
        </template>
      </span>
      <Sparkline
        :values="sessionSpark"
        accent="var(--brand-2, #5ec5ff)"
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