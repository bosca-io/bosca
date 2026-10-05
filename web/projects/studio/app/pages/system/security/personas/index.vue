<script setup lang="ts">
import gql from 'graphql-tag'
import { SUBSYSTEMS } from '~/composables/useSubsystems'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const personasGql = gql`
  query GetPersonas {
    profiles {
      studioPersonas {
        all { id name description subsystemIds enabled created modified }
      }
    }
  }
`

const createGql = gql`
  mutation CreatePersona($input: StudioPersonaInput!) {
    profiles {
      studioPersonas {
        create(input: $input) { id }
      }
    }
  }
`

const deleteGql = gql`
  mutation DeletePersona($id: UUID!) {
    profiles { studioPersonas { delete(id: $id) } }
  }
`

interface Persona {
  id: string
  name: string
  description: string | null
  subsystemIds: string[]
  enabled: boolean
}

const { data, status, refresh } = useAsyncQuery<{
  profiles: { studioPersonas: { all: Persona[] } }
}>('personas-list', personasGql, {})

const personas = computed(() => data.value?.profiles?.studioPersonas?.all ?? [])

const subsystemLabels = Object.fromEntries(SUBSYSTEMS.map(s => [s.id, s.label]))

function formatSubsystems(ids: string[]): string {
  if (ids.length === SUBSYSTEMS.length) return 'All'
  return ids.map(id => subsystemLabels[id] ?? id).join(', ')
}

const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const selectedSubs = ref(new Set<string>())
const saving = ref(false)
const deleteTarget = ref<Persona | null>(null)
const deleteLoading = ref(false)

function toggleSub(id: string) {
  const next = new Set(selectedSubs.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedSubs.value = next
}

function selectAllSubs() {
  selectedSubs.value = new Set(SUBSYSTEMS.map(s => s.id))
}

function resetForm() {
  newName.value = ''
  newDesc.value = ''
  selectedSubs.value = new Set()
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
  { key: 'subsystems', label: 'Subsystems', width: 'minmax(200px, 2fr)', muted: true },
  { key: 'enabled', label: 'Status', width: '80px' },
]

async function handleCreate() {
  saving.value = true
  try {
    await gqlMutation(createGql, {
      input: {
        name: newName.value,
        description: newDesc.value || null,
        subsystemIds: Array.from(selectedSubs.value),
      },
    })
    showCreate.value = false
    resetForm()
    toast.success('Persona created')
    refresh()
  } catch {
    toast.error('Failed to create persona')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Persona deleted')
    refresh()
  } catch {
    toast.error('Failed to delete persona')
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'Personas')"
        title="Personas"
        :subtitle="`${personas.length} personas`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">
            New Persona
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Personas">
      <GlassTable
        :columns="columns"
        :rows="personas"
        :loading="status === 'pending' && personas.length === 0"
        empty-text="No personas defined yet."
        clickable
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'edit' },
          { id: 'sep', label: '', separator: true },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: any) => navigateTo(`/system/security/personas/${row.id}`)"
        @row-action="({ action, row }: any) => {
          if (action === 'edit') navigateTo(`/system/security/personas/${row.id}`)
          else if (action === 'delete') deleteTarget = row
        }"
      >
        <template #col-name="{ row }">
          <span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
        </template>
        <template #col-subsystems="{ row }">
          {{ formatSubsystems(row.subsystemIds) }}
        </template>
        <template #col-enabled="{ row }">
          <Badge :color="row.enabled ? '#34d99a' : 'var(--fg-3)'">
            {{ row.enabled ? 'Active' : 'Disabled' }}
          </Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Persona"
      icon="shield"
      :accent="accent"
      @close="showCreate = false"
    >
      <TextInput
        v-model="newName"
        label="Name"
        placeholder="e.g. Content Manager"
        autofocus />
      <Textarea
        v-model="newDesc"
        label="Description"
        placeholder="What does this persona grant access to?"
        :rows="2" />

      <div class="sub-picker">
        <div class="sub-picker-header">
          <span class="sub-picker-label">Subsystems</span>
          <button class="sub-picker-all" @click="selectAllSubs">Select all</button>
        </div>
        <div class="sub-picker-grid">
          <label
            v-for="s in SUBSYSTEMS"
            :key="s.id"
            class="sub-chip"
            :class="{ active: selectedSubs.has(s.id) }"
            :style="{ '--chip-accent': s.accent }"
          >
            <input
              type="checkbox"
              :checked="selectedSubs.has(s.id)"
              class="sr-only"
              @change="toggleSub(s.id)"
            >
            <Icon :name="s.icon" :size="12" :color="selectedSubs.has(s.id) ? s.accent : 'var(--fg-3)'" />
            {{ s.label }}
          </label>
        </div>
      </div>

      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || selectedSubs.size === 0 || saving"
          @click="handleCreate"
        >
          Create
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      message="All profile assignments for this persona will be removed."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.sub-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
}

.sub-picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sub-picker-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2);
}

.sub-picker-all {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--brand-accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
}

.sub-picker-all:hover {
  text-decoration: underline;
}

.sub-picker-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sub-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  border: 1px solid color-mix(in oklch, var(--fg-3) 20%, transparent);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
  user-select: none;
}

.sub-chip.active {
  color: var(--fg-0);
  background: color-mix(in oklch, var(--chip-accent) 12%, transparent);
  border-color: color-mix(in oklch, var(--chip-accent) 32%, transparent);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}
</style>
