<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()

const page = ref(1)
const pageSize = 50
const statusFilter = ref('')
const severityFilter = ref('')
const searchQuery = ref('')

const errorsGql = gql`
  query ErrorGroups($filter: ErrorGroupFilter, $offset: Long, $limit: Int) {
    analytics {
      errors {
        groups(filter: $filter, offset: $offset, limit: $limit) {
          total
          edges {
            fingerprint appId type message fatal status
            firstSeen lastSeen eventCount sampleEventId
          }
        }
      }
    }
  }
`

interface ErrorGroup {
  fingerprint: string; appId: string | null; type: string; message: string
  fatal: boolean; status: string; firstSeen: string; lastSeen: string
  eventCount: number; sampleEventId: string | null
}

const filter = computed(() => ({
  status: statusFilter.value || null,
  fatal: severityFilter.value === 'fatal' ? true : severityFilter.value === 'nonfatal' ? false : null,
  search: searchQuery.value || null,
}))
const offset = computed(() => (page.value - 1) * pageSize)
const limit = ref(pageSize)

const { data, status, refresh } = useAsyncQuery<{
  analytics: { errors: { groups: { total: number; edges: ErrorGroup[] } } }
}>('error-groups', errorsGql, { filter, offset, limit })

const errors = computed(() => data.value?.analytics?.errors?.groups?.edges ?? [])
const totalGroups = computed(() => data.value?.analytics?.errors?.groups?.total ?? 0)
const totalPages = computed(() => Math.max(1, Math.ceil(totalGroups.value / pageSize)))

watch([statusFilter, severityFilter, searchQuery], () => { page.value = 1 })

const STATUS_COLORS: Record<string, string> = { OPEN: '#ff5d6c', RESOLVED: '#34d99a', IGNORED: '#6c7388' }

const columns: GlassTableColumn[] = [
  { key: 'status', label: 'Status', width: '90px' },
  { key: 'type', label: 'Type', width: 'minmax(150px, 1fr)' },
  { key: 'message', label: 'Message', width: '2fr', muted: true },
  { key: 'fatal', label: 'Fatal', width: '60px' },
  { key: 'events', label: 'Events', width: '80px', align: 'right' },
  { key: 'lastSeen', label: 'Last Seen', width: '100px', muted: true },
]

function formatRelative(d: string): string {
  const ms = Date.now() - new Date(d).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Errors')"
        title="Error Tracking"
        :subtitle="`${totalGroups.toLocaleString()} error groups`">
        <template #actions><Button size="sm" icon="pulse" @click="refresh()">Refresh</Button></template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <SearchInput v-model="searchQuery" placeholder="Search by type or message…" />
      <Select
        v-model="statusFilter"
        :options="[{ value: '', label: 'All' }, { value: 'OPEN', label: 'Open' }, { value: 'RESOLVED', label: 'Resolved' }, { value: 'IGNORED', label: 'Ignored' }]"
        placeholder="Status"
        size="sm" />
      <Select
        v-model="severityFilter"
        :options="[{ value: '', label: 'Any' }, { value: 'fatal', label: 'Fatal only' }, { value: 'nonfatal', label: 'Non-fatal' }]"
        placeholder="Severity"
        size="sm" />
    </div>

    <SectionCard title="Error Groups">
      <GlassTable
        :columns="columns"
        :rows="errors"
        :loading="status === 'pending' && errors.length === 0"
        empty-text="No errors found."
        arrow
        @row-click="(r: any) => router.push(`/analytics/errors/${r.fingerprint}`)">
        <template #col-status="{ row }"><Badge :color="STATUS_COLORS[(row as ErrorGroup).status] ?? '#6c7388'">{{ (row as ErrorGroup).status }}</Badge></template>
        <template #col-type="{ row }"><span style="font-weight: 500; color: var(--fg-0); font-size: 12.5px">{{ row.type }}</span></template>
        <template #col-message="{ row }"><span style="font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block">{{ row.message }}</span></template>
        <template #col-fatal="{ row }"><Badge v-if="(row as ErrorGroup).fatal" color="var(--err)">Fatal</Badge></template>
        <template #col-events="{ row }"><span class="mono tabular">{{ (row as ErrorGroup).eventCount.toLocaleString() }}</span></template>
        <template #col-lastSeen="{ row }">{{ formatRelative((row as ErrorGroup).lastSeen) }}</template>
      </GlassTable>
    </SectionCard>

    <div class="pagination">
      <Button size="sm" :disabled="page <= 1" @click="page--">Previous</Button>
      <span class="page-info">Page {{ page }} of {{ totalPages }}</span>
      <Button size="sm" :disabled="page >= totalPages" @click="page++">Next</Button>
    </div>
  </PageShell>
</template>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 14px; }
.page-info { font-size: 12px; color: var(--fg-3); }
</style>
