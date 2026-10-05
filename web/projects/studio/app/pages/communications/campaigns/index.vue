<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const offset = ref(0)
const limit = ref(25)

const STATUS_COLORS: Record<string, string> = {
  DRAFT: '#6c7388',
  SCHEDULED: '#5ec5ff',
  SENDING: '#a78bff',
  SENT: '#34d99a',
  ACTIVE: '#34d99a',
  FAILED: '#ff5d6c',
  CANCELLED: '#6c7388',
}

const campaignsGql = gql`
  query GetAllCampaigns($limit: Int!, $offset: Long!) {
    campaigns {
      all(limit: $limit, offset: $offset) {
        id name channel status scheduledAt sentAt sentCount created modified
      }
    }
  }
`

const deleteGql = gql`
  mutation DeleteCampaign($id: UUID!) {
    campaigns { delete(id: $id) }
  }
`

interface Campaign {
  id: string; name: string; channel: string; status: string
  scheduledAt: string | null; sentAt: string | null; sentCount: number
  created: string; modified: string
}

const { data, status, refresh } = useAsyncQuery<{
  campaigns: { all: Campaign[] }
}>('campaigns-list', campaignsGql, { limit, offset })

const campaigns = computed(() => data.value?.campaigns?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const deleteTarget = ref<Campaign | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'channel', label: 'Channel', width: '100px' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'sent', label: 'Sent', width: '80px', align: 'right' },
  { key: 'scheduled', label: 'Scheduled', width: '120px', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View details', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Campaign) {
  if (action === 'open') router.push(`/communications/campaigns/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'delete') deleteTarget.value = row
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Campaign deleted')
    refresh()
  } catch {
    toast.error('Failed to delete')
  } finally {
    deleteLoading.value = false
  }
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Communications', 'Campaigns')"
        title="Campaigns"
        :subtitle="`${campaigns.length} campaigns`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/communications/campaigns/new')">New Campaign</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Campaigns">
      <GlassTable
        :columns="columns"
        :rows="campaigns"
        :loading="isLoading && campaigns.length === 0"
        empty-text="No campaigns found."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/communications/campaigns/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Campaign)"
      >
        <template #col-name="{ row }">
          <span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
        </template>
        <template #col-channel="{ row }">
          <Badge :color="accent">{{ row.channel }}</Badge>
        </template>
        <template #col-status="{ row }">
          <Badge :color="STATUS_COLORS[row.status] ?? '#6c7388'">{{ row.status }}</Badge>
        </template>
        <template #col-sent="{ row }">
          <span class="mono tabular">{{ row.sentCount?.toLocaleString() ?? 0 }}</span>
        </template>
        <template #col-scheduled="{ row }">
          {{ formatDate(row.scheduledAt) }}
        </template>
      </GlassTable>
    </SectionCard>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>
