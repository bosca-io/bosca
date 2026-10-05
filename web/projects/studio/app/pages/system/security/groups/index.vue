<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const offset = ref(0)
const limit = ref(25)
const searchQuery = ref('')

const groupsGql = gql`
  query GetGroups($limit: Int!, $offset: Long!) {
    security { groups { all(limit: $limit, offset: $offset) { id name description type } } }
  }
`

const addGql = gql`
  mutation AddGroup($name: String!, $description: String!, $groupType: GroupType!) {
    security { groups { addGroup(name: $name, description: $description, groupType: $groupType) { id } } }
  }
`

const deleteGql = gql`
  mutation DeleteGroup($id: UUID!) { security { groups { deleteGroup(id: $id) } } }
`

interface Group { id: string; name: string; description: string | null; type: string }

function copyToClipboard(text: string) { void globalThis.navigator?.clipboard?.writeText(text) }

const { data, status, refresh } = useAsyncQuery<{
  security: { groups: { all: Group[] } }
}>('groups-list', groupsGql, { limit, offset })

const groups = computed(() => {
  const all = data.value?.security?.groups?.all ?? []
  if (!searchQuery.value) return all
  const q = searchQuery.value.toLowerCase()
  return all.filter(g => g.name.toLowerCase().includes(q) || g.description?.toLowerCase().includes(q))
})

const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const newType = ref('PRINCIPAL')
const saving = ref(false)
const deleteTarget = ref<Group | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
  { key: 'type', label: 'Type', width: '100px' },
]

async function handleCreate() {
  saving.value = true
  try {
    await gqlMutation(addGql, { name: newName.value, description: newDesc.value, groupType: newType.value })
    showCreate.value = false; newName.value = ''; newDesc.value = ''
    toast.success('Group created'); refresh()
  } catch { toast.error('Failed') } finally { saving.value = false }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() }
  catch { toast.error('Failed') } finally { deleteLoading.value = false }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'Groups')"
        title="Groups"
        :subtitle="`${groups.length} groups`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="showCreate = true">New Group</Button></template>
      </PageHeader>
    </template>

    <div class="filter-bar"><SearchInput v-model="searchQuery" placeholder="Search groups…" /></div>

    <SectionCard title="Groups">
      <GlassTable
        :columns="columns"
        :rows="groups"
        :loading="status === 'pending' && groups.length === 0"
        empty-text="No groups."
        :row-actions="() => [{ id: 'copy', label: 'Copy ID', icon: 'copy' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        @row-action="({ action, row }: any) => action === 'copy' ? (copyToClipboard(row.id), toast.success('Copied')) : action === 'delete' ? deleteTarget = row : null">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-type="{ row }"><Badge :color="row.type === 'SYSTEM' ? accent : 'var(--fg-3)'">{{ row.type }}</Badge></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Group"
      icon="users"
      :accent="accent"
      @close="showCreate = false">
      <TextInput v-model="newName" label="Name" autofocus />
      <Textarea v-model="newDesc" label="Description" :rows="2" />
      <Select v-model="newType" :options="[{ value: 'PRINCIPAL', label: 'Principal' }, { value: 'SYSTEM', label: 'System' }]" label="Type" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<style scoped>
.filter-bar { margin-bottom: 14px; }
</style>
