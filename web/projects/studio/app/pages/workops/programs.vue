<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * Programs — the release-planning grouping between a portfolio and its projects. A flat, scannable
 * table (same shape as Projects); each row jumps to the program's environments or releases.
 */

const { accent } = useCurrentSubsystem()
const { searchProfiles } = useProfileSearch()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface Program {
  id: string
  key: string
  name: string
  description: string | null
  owner: { id: string, name: string } | null
  portfolio: { id: string, key: string, name: string }
  archivedAt: string | null
  modifiedAt: string
  version: number
}

const listGql = gql`
  query {
    workOps {
      programs {
        all {
          id key name description
          owner { id name }
          portfolio { id key name }
          archivedAt modifiedAt version
        }
      }
    }
  }
`
const { data, status, refresh } = useAsyncQuery<{
  workOps: { programs: { all: Program[] } }
}>('workops-programs', listGql)

const allPrograms = computed(() => data.value?.workOps?.programs?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const activeTab = ref('Active')
const programs = computed(() => {
  if (activeTab.value === 'Active') return allPrograms.value.filter(p => !p.archivedAt)
  if (activeTab.value === 'Archived') return allPrograms.value.filter(p => p.archivedAt)
  return allPrograms.value
})

const subtitle = computed(() => {
  const active = allPrograms.value.filter(p => !p.archivedAt).length
  const archived = allPrograms.value.filter(p => p.archivedAt).length
  return `${active} active · ${archived} archived`
})

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: '120px' },
  { key: 'name', label: 'Name', width: '1.5fr' },
  { key: 'portfolio', label: 'Portfolio', width: '1fr' },
  { key: 'owner', label: 'Owner', width: '1fr' },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
]

function keyColor(key: string): string {
  let hash = 0
  for (let i = 0; i < key.length; i++) hash = key.charCodeAt(i) + ((hash << 5) - hash)
  const hues = ['#ff7ac6', '#a78bff', '#5ec5ff', '#34d99a', '#ffb547', '#06b6d4']
  return hues[Math.abs(hash) % hues.length]!
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

// ─── Row actions ──────────────────────────────────────────────────────────────
function menuItems(p: Program) {
  return [
    { id: 'edit', label: 'Edit', icon: 'edit' },
    { id: 'environments', label: 'Environments', icon: 'globe' },
    { id: 'releases', label: 'Releases', icon: 'package' },
    { id: 'archive', label: p.archivedAt ? 'Unarchive' : 'Archive', icon: 'archive' },
  ]
}

async function onMenuSelect(id: string, p: Program) {
  if (id === 'edit') openEdit(p)
  else if (id === 'environments') await navigateTo(`/workops/releases/environments?programId=${p.id}`)
  else if (id === 'releases') await navigateTo(`/workops/releases?programId=${p.id}`)
  else if (id === 'archive') await toggleArchive(p)
}

async function toggleArchive(p: Program) {
  try {
    if (p.archivedAt) {
      await mutation(gql`
        mutation UnarchiveProgram($id: UUID!, $expectedVersion: Long!) {
          workOps { programs { unarchive(id: $id, expectedVersion: $expectedVersion) { id } } }
        }
      `, { id: p.id, expectedVersion: p.version })
    }
    else {
      await mutation(gql`
        mutation ArchiveProgram($id: UUID!, $expectedVersion: Long!) {
          workOps { programs { archive(id: $id, expectedVersion: $expectedVersion) { id } } }
        }
      `, { id: p.id, expectedVersion: p.version })
    }
    await refresh()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update the program')
  }
}

// ─── Create / Edit ────────────────────────────────────────────────────────────
const showForm = ref(false)
const editTarget = ref<Program | null>(null)
const saving = ref(false)
const error = ref('')
const form = reactive({ key: '', name: '', description: '', ownerProfileId: '', portfolioId: '' })
watch(() => form.key, (v) => { if (v !== v.toUpperCase()) form.key = v.toUpperCase() })

interface PortfolioOption { id: string, name: string, archivedAt: string | null }
const { data: portfoliosData } = useAsyncQuery<{ workOps: { portfolios: { all: PortfolioOption[] } } }>(
  'workops-programs-portfolios',
  gql`query { workOps { portfolios { all { id name archivedAt } } } }`,
)
const portfolioOptions = computed(() =>
  (portfoliosData.value?.workOps?.portfolios?.all ?? [])
    .filter(p => !p.archivedAt)
    .map(p => ({ value: p.id, label: p.name })),
)

function openCreate() {
  editTarget.value = null
  form.key = ''
  form.name = ''
  form.description = ''
  form.ownerProfileId = ''
  form.portfolioId = portfolioOptions.value.length === 1 ? portfolioOptions.value[0]!.value : ''
  error.value = ''
  showForm.value = true
}
useCreateFromQuery(openCreate)

function openEdit(p: Program) {
  editTarget.value = p
  form.key = p.key
  form.name = p.name
  form.description = p.description ?? ''
  form.ownerProfileId = p.owner?.id ?? ''
  form.portfolioId = p.portfolio.id
  error.value = ''
  showForm.value = true
}

async function handleSave() {
  if (!form.key || !form.name || !form.ownerProfileId || !form.portfolioId) {
    error.value = 'Portfolio, key, name, and owner are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const input = {
      // The key identifies the program everywhere — it doesn't change on edit.
      key: (editTarget.value?.key ?? form.key).toUpperCase(),
      name: form.name,
      description: form.description || null,
      ownerProfileId: form.ownerProfileId,
      portfolioId: form.portfolioId,
    }
    if (editTarget.value) {
      await mutation(gql`
        mutation UpdateProgram($id: UUID!, $input: WorkOpsProgramInput!, $expectedVersion: Long!) {
          workOps { programs { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
        }
      `, { id: editTarget.value.id, input, expectedVersion: editTarget.value.version })
    }
    else {
      await mutation(gql`
        mutation CreateProgram($input: WorkOpsProgramInput!) {
          workOps { programs { create(input: $input) { id } } }
        }
      `, { input })
    }
    showForm.value = false
    await refresh()
  }
  catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save program'
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Programs')"
        title="Programs"
        :subtitle="subtitle"
        :tabs="['Active', 'All', 'Archived']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Program</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="programs"
      row-key="id"
      :loading="isLoading"
      empty-text="No programs found"
    >
      <template #col-key="{ value }">
        <span class="program-key">
          <span class="key-dot" :style="{ background: keyColor(value) }" />
          <span class="mono">{{ value }}</span>
        </span>
      </template>
      <template #col-name="{ row }">
        <div class="name-cell" :class="{ archived: row.archivedAt }">
          <span class="program-name">{{ row.name }}</span>
          <span v-if="row.description" class="desc">{{ row.description }}</span>
        </div>
      </template>
      <template #col-portfolio="{ row }">
        <Badge :color="accent">{{ row.portfolio.name }}</Badge>
      </template>
      <template #col-owner="{ row }">
        <span v-if="row.owner">{{ row.owner.name }}</span>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-modified="{ row }">
        {{ formatDate(row.modifiedAt) }}
      </template>
      <template #actions="{ row }">
        <OverflowMenu :items="menuItems(row)" @select="onMenuSelect($event, row)">
          <template #default="{ toggle }">
            <button class="row-menu-btn" @click.stop="toggle">
              <Icon name="list" :size="14" color="var(--fg-3)" />
            </button>
          </template>
        </OverflowMenu>
      </template>
    </GlassTable>

    <!-- Create / Edit Modal -->
    <Modal
      v-if="showForm"
      :title="editTarget ? 'Edit Program' : 'New Program'"
      :icon="editTarget ? 'edit' : 'layers'"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <Select
          v-model="form.portfolioId"
          :options="portfolioOptions"
          label="Portfolio"
          placeholder="Select a portfolio…" />
        <TextInput
          v-model="form.key"
          label="Key"
          placeholder="PROG (2-10 uppercase)"
          :disabled="!!editTarget"
          mono />
        <TextInput v-model="form.name" label="Name" placeholder="Program name" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <Select
          v-model="form.ownerProfileId"
          :on-search="searchProfiles"
          searchable
          placeholder="Search profiles…"
          label="Owner" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">{{ editTarget ? 'Save Changes' : 'Create' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.program-key {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.key-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
}

.name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.name-cell.archived { opacity: 0.55; }

.program-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.desc {
  font-size: 11px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fg-3 { color: var(--fg-3); }

.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
}

.row-menu-btn:hover { background: var(--bg-3); }

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}
</style>
