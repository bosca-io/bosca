<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const offset = ref(0)
const limit = ref(25)

const TYPE_COLORS: Record<string, string> = {
  STATIC: '#6c7388',
  DYNAMIC: '',
  EVERYONE: '#a78bfa',
}

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: '#34d99a',
  DRAFT: '#6c7388',
  PAUSED: '#ffb547',
  ARCHIVED: '#6c7388',
}

const segmentsGql = gql`
  query GetAllSegments($limit: Int!, $offset: Long!) {
    segments {
      all(limit: $limit, offset: $offset) {
        id name description type status memberCount created modified
      }
    }
  }
`

const addSegmentGql = gql`
  mutation AddSegment($segment: SegmentInput!) {
    segments { add(segment: $segment) { id } }
  }
`

const deleteSegmentGql = gql`
  mutation DeleteSegment($id: UUID!) {
    segments { delete(id: $id) }
  }
`

interface Segment {
  id: string
  name: string
  description: string | null
  type: string
  status: string
  memberCount: number
  created: string
  modified: string
}

const { data, status, refresh } = useAsyncQuery<{
  segments: { all: Segment[] }
}>('segments-list', segmentsGql, { limit, offset })

const segments = computed(() => data.value?.segments?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const newType = ref('STATIC')
const newStatus = ref('DRAFT')
const saving = ref(false)

const deleteTarget = ref<Segment | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '100px' },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'members', label: 'Members', width: '100px', align: 'right' },
]

const typeOptions = [
  { value: 'STATIC', label: 'Static' },
  { value: 'DYNAMIC', label: 'Dynamic' },
  { value: 'EVERYONE', label: 'Everyone' },
]

const statusOptions = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'ARCHIVED', label: 'Archived' },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View details', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Segment) {
  if (action === 'open') router.push(`/audience/segments/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'delete') deleteTarget.value = row
}

function openCreate() {
  newName.value = ''
  newDesc.value = ''
  newType.value = 'STATIC'
  newStatus.value = 'DRAFT'
  showCreate.value = true
}
useCreateFromQuery(openCreate)

async function handleCreate() {
  if (!newName.value.trim()) return
  saving.value = true
  try {
    const result = await gqlMutation<{ segments: { add: { id: string } } }>(addSegmentGql, {
      segment: {
        name: newName.value,
        description: newDesc.value || '',
        type: newType.value,
        status: newStatus.value,
      },
    })
    showCreate.value = false
    toast.success('Segment created')
    router.push(`/audience/segments/${result.segments.add.id}`)
  } catch {
    toast.error('Failed to create segment')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteSegmentGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Segment deleted')
    refresh()
  } catch {
    toast.error('Failed to delete segment')
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
        :breadcrumb="buildBreadcrumb('Audience', 'Segments')"
        title="Segments"
        :subtitle="`${segments.length} segments`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Segment</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Segments">
      <GlassTable
        :columns="columns"
        :rows="segments"
        :loading="isLoading && segments.length === 0"
        empty-text="No segments found."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/audience/segments/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Segment)"
      >
        <template #col-name="{ row }">
          <div>
            <div style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</div>
            <div v-if="row.description" style="font-size: 11.5px; color: var(--fg-3); margin-top: 1px">{{ row.description }}</div>
          </div>
        </template>
        <template #col-type="{ row }">
          <Badge :color="TYPE_COLORS[row.type] || accent">{{ row.type }}</Badge>
        </template>
        <template #col-status="{ row }">
          <Badge :color="STATUS_COLORS[row.status] ?? '#6c7388'">{{ row.status }}</Badge>
        </template>
        <template #col-members="{ row }">
          <span class="mono tabular">{{ row.memberCount?.toLocaleString() ?? 0 }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Segment"
      icon="segment"
      :accent="accent"
      @close="showCreate = false">
      <div class="segment-form">
        <TextInput
          v-model="newName"
          label="Name"
          placeholder="Segment name"
          autofocus />
        <Textarea
          v-model="newDesc"
          label="Description"
          placeholder="Optional description…"
          :rows="2" />
        <Select v-model="newType" :options="typeOptions" label="Type" />
        <Select v-model="newStatus" :options="statusOptions" label="Status" />
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.segment-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
