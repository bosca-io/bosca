<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * Portfolios — the top-level grouping, and nothing else. Programs have their own page (Plan →
 * Programs); this one just lists, creates, and archives portfolios.
 */

const { accent } = useCurrentSubsystem()
const { searchProfiles } = useProfileSearch()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface Portfolio {
  id: string
  key: string
  name: string
  description: string | null
  owner: { id: string, name: string } | null
  archivedAt: string | null
  programs: { id: string }[]
  version: number
}

const listGql = gql`
  query {
    workOps {
      portfolios {
        all {
          id key name description
          owner { id name }
          archivedAt
          programs { id }
          version
        }
      }
    }
  }
`
const { data, status, refresh } = useAsyncQuery<{
  workOps: { portfolios: { all: Portfolio[] } }
}>('workops-portfolios', listGql)

const allPortfolios = computed(() => data.value?.workOps?.portfolios?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const activeTab = ref('Active')
const portfolios = computed(() => {
  if (activeTab.value === 'Active') return allPortfolios.value.filter(p => !p.archivedAt)
  if (activeTab.value === 'Archived') return allPortfolios.value.filter(p => p.archivedAt)
  return allPortfolios.value
})

const subtitle = computed(() => {
  const active = allPortfolios.value.filter(p => !p.archivedAt).length
  const archived = allPortfolios.value.filter(p => p.archivedAt).length
  return `${active} active · ${archived} archived`
})

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: '120px' },
  { key: 'name', label: 'Name', width: '2fr' },
  { key: 'owner', label: 'Owner', width: '1fr' },
  { key: 'programs', label: 'Programs', width: '100px' },
]

function keyColor(key: string): string {
  let hash = 0
  for (let i = 0; i < key.length; i++) hash = key.charCodeAt(i) + ((hash << 5) - hash)
  const hues = ['#ff7ac6', '#a78bff', '#5ec5ff', '#34d99a', '#ffb547', '#06b6d4']
  return hues[Math.abs(hash) % hues.length]!
}

// ─── Archive ──────────────────────────────────────────────────────────────────
async function toggleArchive(p: Portfolio) {
  try {
    if (p.archivedAt) {
      await mutation(gql`
        mutation UnarchivePortfolio($id: UUID!, $expectedVersion: Long!) {
          workOps { portfolios { unarchive(id: $id, version: $expectedVersion) { id } } }
        }
      `, { id: p.id, expectedVersion: p.version })
    }
    else {
      await mutation(gql`
        mutation ArchivePortfolio($id: UUID!, $expectedVersion: Long!) {
          workOps { portfolios { archive(id: $id, version: $expectedVersion) { id } } }
        }
      `, { id: p.id, expectedVersion: p.version })
    }
    await refresh()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update the portfolio')
  }
}

function onMenuSelect(id: string, p: Portfolio) {
  if (id === 'archive') toggleArchive(p)
  else if (id === 'edit') openEdit(p)
}

// ─── Create / Edit ────────────────────────────────────────────────────────────
const showForm = ref(false)
const editTarget = ref<Portfolio | null>(null)
const saving = ref(false)
const error = ref('')
const form = reactive({ key: '', name: '', description: '', ownerProfileId: '' })
watch(() => form.key, (v) => { if (v !== v.toUpperCase()) form.key = v.toUpperCase() })

function openCreate() {
  editTarget.value = null
  form.key = ''
  form.name = ''
  form.description = ''
  form.ownerProfileId = ''
  error.value = ''
  showForm.value = true
}

function openEdit(p: Portfolio) {
  editTarget.value = p
  form.key = p.key
  form.name = p.name
  form.description = p.description ?? ''
  form.ownerProfileId = p.owner?.id ?? ''
  error.value = ''
  showForm.value = true
}

async function handleSave() {
  if (!form.key || !form.name || !form.ownerProfileId) {
    error.value = 'Key, name, and owner are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const input = {
      // The key identifies the portfolio everywhere (tasks, URLs) — it doesn't change on edit.
      key: (editTarget.value?.key ?? form.key).toUpperCase(),
      name: form.name,
      description: form.description || null,
      ownerProfileId: form.ownerProfileId,
    }
    if (editTarget.value) {
      await mutation(gql`
        mutation UpdatePortfolio($id: UUID!, $input: WorkOpsPortfolioInput!, $expectedVersion: Long!) {
          workOps { portfolios { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
        }
      `, { id: editTarget.value.id, input, expectedVersion: editTarget.value.version })
    }
    else {
      await mutation(gql`
        mutation CreatePortfolio($input: WorkOpsPortfolioInput!) {
          workOps { portfolios { create(input: $input) { id } } }
        }
      `, { input })
    }
    showForm.value = false
    await refresh()
  }
  catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save portfolio'
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
        :breadcrumb="buildBreadcrumb('Work Ops', 'Portfolios')"
        title="Portfolios"
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
            @click="openCreate">New Portfolio</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="portfolios"
      row-key="id"
      :loading="isLoading"
      empty-text="No portfolios found"
    >
      <template #col-key="{ value }">
        <span class="portfolio-key">
          <span class="key-dot" :style="{ background: keyColor(value) }" />
          <span class="mono">{{ value }}</span>
        </span>
      </template>
      <template #col-name="{ row }">
        <div class="name-cell" :class="{ archived: row.archivedAt }">
          <span class="portfolio-name">{{ row.name }}</span>
          <span v-if="row.description" class="desc">{{ row.description }}</span>
        </div>
      </template>
      <template #col-owner="{ row }">
        <span v-if="row.owner">{{ row.owner.name }}</span>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-programs="{ row }">
        <span class="count">{{ row.programs.length }}</span>
      </template>
      <template #actions="{ row }">
        <OverflowMenu
          :items="[
            { id: 'edit', label: 'Edit', icon: 'edit' },
            { id: 'archive', label: row.archivedAt ? 'Unarchive' : 'Archive', icon: 'archive' },
          ]"
          @select="onMenuSelect($event, row)"
        >
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
      :title="editTarget ? 'Edit Portfolio' : 'New Portfolio'"
      :icon="editTarget ? 'edit' : 'building'"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput
          v-model="form.key"
          label="Key"
          placeholder="PORTF (2-10 uppercase)"
          :disabled="!!editTarget"
          mono />
        <TextInput v-model="form.name" label="Name" placeholder="Portfolio name" />
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
.portfolio-key {
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

.portfolio-name {
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

.count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--fg-1);
}

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
