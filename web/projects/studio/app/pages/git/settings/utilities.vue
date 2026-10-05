<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import { useAuth } from '@bosca/auth-client-browser'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()
const toast = useToast()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

const { save: saveLastOwner, load: loadLastOwner } = useLastGitOwner()
const savedOwner = loadLastOwner()
const selectedOwner = ref(savedOwner?.id ?? '')
const knownProfiles = ref<Map<string, string>>(new Map())
if (savedOwner) knownProfiles.value.set(savedOwner.id, savedOwner.label)

const ownerOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = []
  const p = profile.value
  if (p?.id) {
    const label = p.name || p.slug || p.id
    opts.push({ value: p.id, label })
    knownProfiles.value.set(p.id, label)
  }
  if (savedOwner && savedOwner.id !== p?.id) opts.push({ value: savedOwner.id, label: savedOwner.label })
  return opts
})

async function searchProfilesAndTrack(q: string) {
  const results = await searchProfiles(q)
  for (const opt of results) knownProfiles.value.set(opt.value, opt.label)
  return results
}

watch(() => profile.value?.id, (id) => {
  if (id && !selectedOwner.value) selectedOwner.value = id
}, { immediate: true })

watch(selectedOwner, (id) => {
  if (!id) return
  const label = knownProfiles.value.get(id) ?? id
  saveLastOwner({ id, label })
  selectedRepoId.value = undefined
})

interface UtilRepo { id: string; name: string; diskSizeBytes: number }

const selectedRepoId = ref<string | undefined>(undefined)

const reposGql = gql`
  query UtilitiesRepos($ownerId: UUID!) {
    git {
      repositories(ownerId: $ownerId, includeArchived: true) {
        id name diskSizeBytes
      }
    }
  }
`

const { data: reposData, status: reposStatus, refresh: refreshRepos } = useAsyncQuery<{
  git: { repositories: UtilRepo[] }
}>('git-utilities-repos', reposGql, { ownerId: computed(() => selectedOwner.value || '') }, { server: false })

const repos = computed<UtilRepo[]>(() => reposData.value?.git?.repositories ?? [])
const reposLoading = computed(() => reposStatus.value === 'pending')

const repoOptions = computed<SelectOption[]>(() =>
  repos.value.map(r => ({ value: r.id, label: r.name })),
)

const selectedRepo = computed<UtilRepo | undefined>(() =>
  repos.value.find(r => r.id === selectedRepoId.value),
)

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = bytes
  let i = 0
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n < 10 && i > 0 ? 2 : n < 100 ? 1 : 0)} ${units[i]}`
}

const gcRunning = ref(false)
const repairRunning = ref(false)
const confirmAction = ref<'gc' | 'repair' | null>(null)

async function runGc() {
  if (!selectedRepoId.value) return
  gcRunning.value = true
  try {
    await gqlMutation(gql`
      mutation RunRepoGc($id: UUID!) {
        git { runRepositoryGc(id: $id) { id diskSizeBytes } }
      }
    `, { id: selectedRepoId.value })
    toast.success('Garbage collection complete')
    await refreshRepos()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to run garbage collection')
  } finally {
    gcRunning.value = false
  }
}

async function runRepair() {
  if (!selectedRepoId.value) return
  repairRunning.value = true
  try {
    await gqlMutation(gql`
      mutation RunRepoRepair($id: UUID!) {
        git { runRepositoryRepair(id: $id) { id diskSizeBytes } }
      }
    `, { id: selectedRepoId.value })
    toast.success('Repair complete')
    await refreshRepos()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to repair repository')
  } finally {
    repairRunning.value = false
  }
}

function confirmRun() {
  const action = confirmAction.value
  confirmAction.value = null
  if (action === 'gc') runGc()
  else if (action === 'repair') runRepair()
}

const confirmTitle = computed(() => {
  if (confirmAction.value === 'gc') return 'Run Garbage Collection'
  if (confirmAction.value === 'repair') return 'Run Repair'
  return ''
})

const confirmSubtitle = computed(() => {
  const name = selectedRepo.value?.name ?? 'repository'
  if (confirmAction.value === 'gc') return `Compact packfiles on ${name}`
  if (confirmAction.value === 'repair') return `Rebuild packs on ${name} without bitmaps`
  return ''
})

const confirmIcon = computed(() => confirmAction.value === 'repair' ? 'wrench' : 'archive')

const confirmAccent = computed(() => confirmAction.value === 'repair' ? '#ffb547' : accent.value)

const confirmDescription = computed(() => {
  if (confirmAction.value === 'gc') {
    return 'Compacts loose objects into a single packfile and removes unreachable objects. Safe to run on a live repository, but may take time on large repos.'
  }
  if (confirmAction.value === 'repair') {
    return 'Runs GC with bitmap generation disabled and pack index v2. Use this when standard GC fails or when bitmaps have become corrupt. Slower than a normal GC.'
  }
  return ''
})

const confirmRunning = computed(() => gcRunning.value || repairRunning.value)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Settings', 'Utilities')"
        title="Utilities"
        subtitle="Run maintenance operations on a repository"
      >
        <template #actions>
          <Select
            v-model="selectedOwner"
            :options="ownerOptions"
            placeholder="Owner"
            searchable
            :search-fn="searchProfilesAndTrack"
            :accent="accent"
            size="sm"
          />
          <Select
            v-if="repoOptions.length"
            v-model="selectedRepoId"
            placeholder="Select repository..."
            :options="repoOptions"
            :accent="accent"
            size="sm"
          />
        </template>
      </PageHeader>
    </template>

    <div v-if="!selectedOwner" class="empty-centered">Select an owner to browse repositories.</div>
    <div v-else-if="reposLoading" class="empty-centered">Loading repositories...</div>
    <div v-else-if="!repoOptions.length" class="empty-centered">No repositories found for this owner.</div>
    <div v-else-if="!selectedRepoId" class="empty-centered">Select a repository to run utilities.</div>

    <div v-else class="utility-list">
      <SectionCard title="Repository" subtitle="Currently selected" padded>
        <div class="repo-info">
          <div class="repo-info-line">
            <Icon name="folder" :size="14" color="var(--fg-3)" />
            <span class="repo-info-name">{{ selectedRepo?.name }}</span>
          </div>
          <div class="repo-info-meta">
            <span class="meta-label">Disk size</span>
            <span class="mono meta-value">{{ formatBytes(selectedRepo?.diskSizeBytes ?? 0) }}</span>
          </div>
        </div>
      </SectionCard>

      <div class="action-card">
        <div class="action-icon" :style="{ background: `color-mix(in oklch, ${accent} 14%, transparent)` }">
          <Icon name="archive" :size="18" :color="accent" />
        </div>
        <div class="action-body">
          <div class="action-title">Garbage Collection</div>
          <p class="action-desc">
            Compacts loose objects into a single self-contained packfile and removes unreachable
            objects. Safe to run on a live repository.
          </p>
        </div>
        <Button
          primary
          icon="archive"
          size="sm"
          :accent="accent"
          :disabled="gcRunning || repairRunning"
          @click="confirmAction = 'gc'"
        >
          {{ gcRunning ? 'Running...' : 'Run GC' }}
        </Button>
      </div>

      <div class="action-card">
        <div class="action-icon" :style="{ background: `color-mix(in oklch, #ffb547 14%, transparent)` }">
          <Icon name="wrench" :size="18" color="#ffb547" />
        </div>
        <div class="action-body">
          <div class="action-title">Repair Repository</div>
          <p class="action-desc">
            Runs GC with bitmap generation disabled and pack index v2. Use this when standard GC
            fails or when bitmaps have become corrupt.
          </p>
        </div>
        <Button
          icon="wrench"
          size="sm"
          :disabled="gcRunning || repairRunning"
          @click="confirmAction = 'repair'"
        >
          {{ repairRunning ? 'Running...' : 'Run Repair' }}
        </Button>
      </div>
    </div>

    <Modal
      v-if="confirmAction"
      :title="confirmTitle"
      :subtitle="confirmSubtitle"
      :icon="confirmIcon"
      :accent="confirmAccent"
      @close="confirmAction = null"
    >
      <p class="confirm-desc">{{ confirmDescription }}</p>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="confirmAction = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="confirmAccent"
          :disabled="confirmRunning"
          @click="confirmRun"
        >
          {{ confirmRunning ? 'Running...' : 'Run' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.empty-centered {
  padding: 64px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}

.utility-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.repo-info {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.repo-info-line { display: flex; align-items: center; gap: 8px; }
.repo-info-name { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.repo-info-meta { display: flex; align-items: center; gap: 10px; }
.meta-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-3); font-weight: 600; }
.meta-value { font-size: 13px; color: var(--fg-1); }

.action-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
}

.action-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.action-body { flex: 1; min-width: 0; }
.action-title { font-size: 14px; font-weight: 600; color: var(--fg-0); margin-bottom: 4px; }
.action-desc { font-size: 12.5px; color: var(--fg-3); margin: 0; line-height: 1.45; }

.confirm-desc { font-size: 13px; color: var(--fg-2); margin: 0; line-height: 1.5; }
.spacer { flex: 1; }
</style>
