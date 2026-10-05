<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import ReleaseCreateModal from '~/components/releases/ReleaseCreateModal.vue'

/**
 * Releases list: the program's releases, newest activity first — name, live
 * status, planned and released dates. A row opens the release's detail page (/workops/releases/:id),
 * which owns the whole cycle view (components, native runs, environments, health).
 */

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()
const router = useRouter()

interface Program { id: string; key: string; name: string }
interface Release { id: string; name: string; description: string | null; releaseDate: string | null; releasedAt: string | null; version: number }

// ── Program selection ─────────────────────────────────────────────────────────
const programsGql = gql`query { workOps { programs { all { id key name } } } }`
const { data: programsData } = useAsyncQuery<{ workOps: { programs: { all: Program[] } } }>('release-programs', programsGql)
const programs = computed(() => programsData.value?.workOps?.programs?.all ?? [])
const programOptions = computed(() => programs.value.map(p => ({ value: p.id, label: p.name })))

// Deep links (e.g. a portfolio's program row) land on a specific program via ?programId=.
const selectedProgramId = ref(typeof useRoute().query.programId === 'string' ? useRoute().query.programId as string : '')
watch(programs, (list) => {
  if (list.length && !list.some(p => p.id === selectedProgramId.value)) selectedProgramId.value = list[0]!.id
}, { immediate: true })

// ── Releases of the selected program ──────────────────────────────────────────
const releasesGql = gql`
  query ProgramReleases($programId: UUID!) {
    workOps { crossProject { releases(programId: $programId) { id name description releaseDate releasedAt version } } }
  }
`
const { data: releasesData, status: releasesStatus, refresh: refreshReleases } = useAsyncQuery<{
  workOps: { crossProject: { releases: Release[] } }
}>('program-releases', releasesGql, { programId: computed(() => selectedProgramId.value || undefined) })

const releases = computed(() => releasesData.value?.workOps?.crossProject?.releases ?? [])

// ── Live status per row — the latest native run, fetched concurrently per release ────────────
// A program has few releases, so one small releaseRuns query per row is cheap; a released release
// needs no run lookup (releasedAt wins).
const runStatusByRelease = ref<Record<string, string | null>>({})
watch(releases, async (list) => {
  if (import.meta.server) return // enrichment only — the list itself SSRs; statuses hydrate in after
  const pending = list.filter(r => !r.releasedAt)
  const entries = await Promise.all(pending.map(async (r) => {
    try {
      const res = await query<{ workOps: { crossProject: { releaseRuns: Array<{ status: string }> } } }>(gql`
        query ReleaseListRuns($releaseId: UUID!) {
          workOps { crossProject { releaseRuns(releaseId: $releaseId) { status } } }
        }
      `, { releaseId: r.id })
      return [r.id, res?.workOps?.crossProject?.releaseRuns?.[0]?.status ?? null] as const
    }
    catch { return [r.id, null] as const }
  }))
  runStatusByRelease.value = Object.fromEntries(entries)
}, { immediate: true })

interface RowStatus { label: string; color: string | undefined }
function rowStatus(r: Release): RowStatus {
  if (r.releasedAt) return { label: 'Released', color: 'var(--ok, #34d399)' }
  switch (runStatusByRelease.value[r.id]) {
    case 'RUNNING': return { label: 'Deploying', color: 'var(--info, #38bdf8)' }
    case 'SUSPENDED': return { label: 'Waiting', color: '#ffb547' }
    case 'FAILED': return { label: 'Failed', color: 'var(--err, #f87171)' }
    case 'CANCELLED': return { label: 'Cancelled', color: 'var(--muted, #94a3b8)' }
    case 'OK': return { label: 'Deployed', color: 'var(--ok, #34d399)' }
    default: return { label: 'Draft', color: undefined }
  }
}

// ── Create ────────────────────────────────────────────────────────────────────
const showCreate = ref(false)
async function onReleaseCreated(id: string) {
  showCreate.value = false
  await router.push(`/workops/releases/${id}`)
}

function onRowClick(row: Release) {
  router.push(`/workops/releases/${row.id}`)
}

// ── Presentation ──────────────────────────────────────────────────────────────
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Release', width: '1.6fr' },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'planned', label: 'Planned', width: '130px', muted: true },
  { key: 'released', label: 'Released', width: '130px', muted: true },
]
function formatDate(d: string | null): string {
  return d ? new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Releases')"
        title="Releases"
        :subtitle="`${releases.length} release${releases.length === 1 ? '' : 's'}`">
        <template #actions>
          <div class="header-actions">
            <Select
              v-model="selectedProgramId"
              :options="programOptions"
              placeholder="Program"
              size="sm"
              :accent="accent" />
            <Button size="sm" icon="refresh" @click="refreshReleases()">Refresh</Button>
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              :disabled="!selectedProgramId"
              @click="showCreate = true">
              Build release
            </Button>
          </div>
        </template>
      </PageHeader>
    </template>

    <div v-if="!selectedProgramId" class="page-empty">Select a program to view its releases.</div>

    <SectionCard v-else title="Releases" subtitle="Open a release to see its components, native runs, environments, and health">
      <GlassTable
        :columns="columns"
        :rows="releases"
        :loading="releasesStatus === 'pending' && releases.length === 0"
        empty-text="This program has no releases yet — Build release starts one."
        row-key="id"
        arrow
        @row-click="onRowClick">
        <template #col-name="{ row }">
          <span class="rel-name">{{ (row as Release).name }}</span>
          <span v-if="(row as Release).description" class="rel-desc">{{ (row as Release).description }}</span>
        </template>
        <template #col-status="{ row }">
          <Badge :color="rowStatus(row as Release).color">{{ rowStatus(row as Release).label }}</Badge>
        </template>
        <template #col-planned="{ row }">{{ formatDate((row as Release).releaseDate) }}</template>
        <template #col-released="{ row }">{{ formatDate((row as Release).releasedAt) }}</template>
      </GlassTable>
    </SectionCard>

    <ReleaseCreateModal
      v-if="showCreate"
      :program-id="selectedProgramId"
      @close="showCreate = false"
      @created="onReleaseCreated" />
  </PageShell>
</template>

<style scoped>
.header-actions { display: flex; align-items: center; gap: 8px; }
.page-empty { padding: 40px 4px; text-align: center; font-size: 13px; color: var(--fg-3); }
.rel-name { color: var(--fg-0); font-size: 13px; font-weight: 500; }
.rel-desc { color: var(--fg-3); font-size: 11.5px; margin-left: 10px; }
</style>
