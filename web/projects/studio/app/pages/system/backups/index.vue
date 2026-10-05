<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const backupsGql = gql`
  query ListBackups($offset: Int!, $limit: Int!) {
    system { backups { list(offset: $offset, limit: $limit) { id status path error includeFiles created modified } } }
  }
`
const createGql = gql`
  mutation CreateBackup($includeFiles: Boolean!) { system { backups { create(includeFiles: $includeFiles) { id } } } }
`
const restoreGql = gql`
  mutation RestoreBackup($backupId: UUID!, $conflictStrategy: ConflictStrategy!) {
    system { backups { restore(backupId: $backupId, conflictStrategy: $conflictStrategy) } }
  }
`
const deleteGql = gql`
  mutation DeleteBackup($id: UUID!) { system { backups { delete(id: $id) } } }
`

interface Backup { id: string; status: string; path: string | null; error: string | null; includeFiles: boolean; created: string; modified: string }

const STATUS_COLORS: Record<string, string> = { COMPLETED: '#34d99a', RUNNING: '#5ec5ff', FAILED: '#ff5d6c', PENDING: '#ffb547' }

const { data, status, refresh } = useAsyncQuery<{
  system: { backups: { list: Backup[] } }
}>('backups-list', backupsGql, { offset: 0, limit: 25 })

const backups = computed(() => data.value?.system?.backups?.list ?? [])

const showCreate = ref(false)
const includeFiles = ref(false)
const creating = ref(false)

const restoreTarget = ref<Backup | null>(null)
const conflictStrategy = ref('SKIP')
const restoring = ref(false)

const deleteTarget = ref<Backup | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'files', label: 'Files', width: '80px' },
  { key: 'created', label: 'Created', width: '140px' },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
  { key: 'id', label: 'ID', width: '100px', muted: true },
]

function getRowActions(row: Backup): OverflowMenuItem[] {
  const items: OverflowMenuItem[] = []
  if (row.status === 'COMPLETED') items.push({ id: 'restore', label: 'Restore', icon: 'reply' })
  items.push({ id: 'sep', label: '', separator: true })
  items.push({ id: 'delete', label: 'Delete', icon: 'trash', danger: true })
  return items
}

function onRowAction(action: string, row: Backup) {
  if (action === 'restore') restoreTarget.value = row
  else if (action === 'delete') deleteTarget.value = row
}

async function handleCreate() {
  creating.value = true
  try { await gqlMutation(createGql, { includeFiles: includeFiles.value }); showCreate.value = false; toast.success('Backup started'); refresh() }
  catch { toast.error('Failed') } finally { creating.value = false }
}

async function handleRestore() {
  if (!restoreTarget.value) return
  restoring.value = true
  try { await gqlMutation(restoreGql, { backupId: restoreTarget.value.id, conflictStrategy: conflictStrategy.value }); restoreTarget.value = null; toast.success('Restore started'); refresh() }
  catch { toast.error('Failed') } finally { restoring.value = false }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() }
  catch { toast.error('Failed') } finally { deleteLoading.value = false }
}

function formatDate(d: string): string { return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Backups')"
        title="Backups"
        :subtitle="`${backups.length} backups`">
        <template #actions>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">Create Backup</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Backups">
      <GlassTable
        :columns="columns"
        :rows="backups"
        :loading="status === 'pending' && backups.length === 0"
        empty-text="No backups."
        :row-actions="getRowActions"
        @row-action="({ action, row }) => onRowAction(action, row as Backup)">
        <template #col-status="{ row }">
          <Badge :color="STATUS_COLORS[(row as Backup).status] ?? '#6c7388'">{{ (row as Backup).status }}</Badge>
        </template>
        <template #col-files="{ row }"><span :class="['dot', (row as Backup).includeFiles ? 'ok' : 'err']" /></template>
        <template #col-created="{ row }">{{ formatDate(row.created) }}</template>
        <template #col-modified="{ row }">{{ formatDate(row.modified) }}</template>
        <template #col-id="{ row }"><span class="mono" style="font-size: 11px">{{ row.id.slice(0, 8) }}</span></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="Create Backup"
      icon="database"
      :accent="accent"
      @close="showCreate = false">
      <Switch v-model="includeFiles" label="Include files from object storage" :accent="accent" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="creating"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>

    <Modal
      v-if="restoreTarget"
      title="Restore Backup"
      icon="reply"
      :accent="accent"
      @close="restoreTarget = null">
      <Select
        v-model="conflictStrategy"
        :options="[
          { value: 'SKIP', label: 'Skip — keep existing data' },
          { value: 'OVERWRITE', label: 'Overwrite — replace with backup' },
          { value: 'FAIL', label: 'Fail — abort on conflict' },
        ]"
        label="Conflict Strategy" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="restoreTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="restoring"
          @click="handleRestore">Restore</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete backup?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>
