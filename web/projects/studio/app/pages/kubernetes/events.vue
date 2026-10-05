<script setup lang="ts">
import { computed, ref } from 'vue'
import type { EventLevel, K8sEvent } from '~/composables/useK8sTypes'
import type { K8sEventStreamItem } from '~/composables/useK8sMetricsStream'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const search = ref('')
const level = ref<'all' | 'info' | 'warn' | 'error'>('all')

// Seed from the on-demand query for history, then ride the live
// `k8sEvents` watch stream — new events prepend without polling,
// same merge the overview's ticker uses.
const { data: events, status, refresh } = useK8sEvents({
  cluster: () => current.value?.id,
})
const { samples: liveEventSamples } = useK8sEventsStream({
  cluster: () => current.value?.id,
})
const EVENT_LEVEL_MAP: Record<string, EventLevel> = { INFO: 'info', WARN: 'warn', ERROR: 'error' }
function mapStreamEvent(e: K8sEventStreamItem, idx: number): K8sEvent {
  const numericId = Number.parseInt(e.id.replace(/\D/g, '').slice(0, 9), 10)
  return {
    id: Number.isFinite(numericId) && numericId > 0 ? numericId : idx + 1,
    lvl: EVENT_LEVEL_MAP[e.level] ?? 'info',
    when: e.when,
    ns: e.namespace,
    obj: e.involvedObject,
    msg: e.message,
    reason: e.reason,
  }
}
const all = computed<K8sEvent[]>(() => {
  // Stream buffer appends newest-last; reverse so live events lead.
  const streamed = [...(liveEventSamples.value ?? [])].reverse().map(mapStreamEvent)
  const seed = events.value ?? []
  const seen = new Set<string>()
  const merged: K8sEvent[] = []
  for (const e of [...streamed, ...seed]) {
    const key = `${e.when}|${e.ns}|${e.obj}|${e.reason}|${e.msg}`
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(e)
  }
  return merged
})
const filtered = computed(() => all.value.filter((e) => {
  if (!nsFilter.matches(e.ns)) return false
  if (level.value !== 'all' && e.lvl !== level.value) return false
  if (search.value && !`${e.obj} ${e.msg} ${e.reason}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))
const needAttention = computed(() => all.value.filter(e => e.lvl !== 'info').length)
const isLoading = computed(() => status.value === 'pending')
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Events')"
        title="Events"
        :subtitle="`${all.length} events · ${needAttention} need attention`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter events…" class="search" />
      <K8sNamespaceFilter />
      <div class="chips">
        <button
          v-for="l in (['all', 'info', 'warn', 'error'] as const)"
          :key="l"
          class="chip"
          :class="{ on: level === l }"
          @click="level = l"
        >{{ l }}</button>
      </div>
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ all.length }}</span>
    </div>

    <SectionCard padded glass>
      <div v-if="isLoading" class="empty">Loading events…</div>
      <div v-else-if="filtered.length === 0" class="empty">No events match the current filters.</div>
      <K8sEventRow
        v-for="e in filtered"
        v-else
        :key="e.id"
        :event="e" />
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.muted { color: var(--fg-3); font-size: 12px; }

.chips {
  display: inline-flex;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 2px;
}
.chip {
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--fg-3);
  cursor: pointer;
  text-transform: capitalize;
}
.chip.on { background: var(--bg-3); color: var(--fg-0); }

.empty { padding: 40px; text-align: center; color: var(--fg-3); }
</style>
