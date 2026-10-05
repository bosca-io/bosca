<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

definePageMeta({ middleware: 'feeds-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
const router = useRouter()

interface SourceLite {
  id: string
  name: string
  enabled: boolean
  url: string
  ownerProfileId: string | null
  configuration: { type?: string } | null
  created: string
}
interface OverviewData { feeds: { sources: SourceLite[] } }

const overviewGql = gql`
  query FeedsOverview {
    feeds {
      sources(offset: 0, limit: 100) {
        id name enabled url ownerProfileId configuration created
      }
    }
  }
`
const { data, status, error } = useAsyncQuery<OverviewData>('feeds-overview', overviewGql)

const sources = computed(() => data.value?.feeds?.sources ?? [])
const enabledCount = computed(() => sources.value.filter(s => s.enabled).length)
const managedCount = computed(() => sources.value.filter(s => !s.ownerProfileId).length)
const userOwnedCount = computed(() => sources.value.filter(s => s.ownerProfileId).length)

const byType = computed(() => {
  const counts = new Map<string, number>()
  for (const s of sources.value) {
    const t = s.configuration?.type ?? 'UNKNOWN'
    counts.set(t, (counts.get(t) ?? 0) + 1)
  }
  return [...counts.entries()].map(([type, count]) => ({ type, count }))
})

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Source', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'enabled', label: 'Status', width: '110px' },
  { key: 'owner', label: 'Owner', width: '120px', muted: true },
  { key: 'url', label: 'URL', width: '1fr', muted: true },
]

function typeLabel(type: string): string {
  return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Feeds', 'Overview')"
        title="Feeds"
        subtitle="Ingest external content feeds — sources, schedules, and a live preview of what they bring in">
        <template #actions>
          <Button
            icon="book-open"
            size="sm"
            :accent="accent"
            @click="router.push('/feeds/preview')">Preview feed</Button>
          <Button
            icon="globe"
            size="sm"
            primary
            :accent="accent"
            @click="router.push('/feeds/sources')">Manage sources</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load feed data — {{ error.message }}</div>

    <div class="stat-grid">
      <div class="stat" :style="{ '--tile-accent': accent }">
        <span class="stat-value">{{ sources.length }}</span>
        <span class="stat-label">Sources</span>
        <span class="stat-sub">{{ enabledCount }} fetching</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ managedCount }}</span>
        <span class="stat-label">Managed</span>
        <span class="stat-sub">global sources</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ userOwnedCount }}</span>
        <span class="stat-label">User-owned</span>
        <span class="stat-sub">private sources</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ sources.length - enabledCount }}</span>
        <span class="stat-label">Disabled</span>
        <span class="stat-sub">not fetching</span>
      </div>
    </div>

    <SectionCard title="Feed types" subtitle="Sources by wire protocol" padded>
      <div v-if="byType.length" class="type-mix">
        <div v-for="t in byType" :key="t.type" class="type-chip">
          <Badge :color="accent">{{ typeLabel(t.type) }}</Badge>
          <span class="type-count">{{ t.count }}</span>
        </div>
      </div>
      <p v-else class="muted">No feed sources configured yet.</p>
    </SectionCard>

    <SectionCard title="Sources" padded>
      <template #right>
        <Button
          size="xs"
          icon="plus"
          :accent="accent"
          @click="router.push('/feeds/sources')">Manage</Button>
      </template>
      <GlassTable
        :columns="columns"
        :rows="sources"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No feed sources yet — add one to start ingesting content."
        arrow
        @row-click="(row) => router.push(`/feeds/sources/${row.id}`)">
        <template #col-name="{ row }">{{ row.name }}</template>
        <template #col-type="{ row }"><Badge :color="accent">{{ typeLabel(row.configuration?.type ?? 'Unknown') }}</Badge></template>
        <template #col-enabled="{ row }">
          <Badge :color="row.enabled ? '#10b981' : '#64748b'">{{ row.enabled ? 'Fetching' : 'Disabled' }}</Badge>
        </template>
        <template #col-owner="{ row }">{{ row.ownerProfileId ? 'User' : 'Managed' }}</template>
        <template #col-url="{ row }">{{ row.url }}</template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--fg-2);
  border-radius: 4px;
  margin-bottom: 12px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 16px;
  background: var(--bg-2);
  border: 1px solid var(--border-1, rgba(255, 255, 255, 0.06));
  border-radius: 10px;
}
.stat-value { font-size: 26px; font-weight: 600; color: var(--tile-accent, var(--fg-1)); line-height: 1.1; }
.stat-label { font-size: 13px; color: var(--fg-1); }
.stat-sub { font-size: 11.5px; color: var(--fg-3); }
.type-mix { display: flex; flex-wrap: wrap; gap: 12px; }
.type-chip { display: inline-flex; align-items: center; gap: 6px; }
.type-count { font-size: 13px; color: var(--fg-2); font-variant-numeric: tabular-nums; }
.muted { color: var(--fg-3); font-size: 13px; margin: 0; }
</style>
