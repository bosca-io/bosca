<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const schedulerGql = gql`
  query GetScheduledJobs {
    scheduler { jobs { id name description cronExpression enabled lastRunAt nextRunAt } }
  }
`
const deleteGql = gql`mutation DeleteScheduledJob($id: UUID!) { scheduler { delete(id: $id) } }`
const enableGql = gql`mutation EnableScheduledJob($id: UUID!) { scheduler { enable(id: $id) { id enabled } } }`
const disableGql = gql`mutation DisableScheduledJob($id: UUID!) { scheduler { disable(id: $id) { id enabled } } }`
const triggerGql = gql`mutation TriggerScheduledJob($id: UUID!) { scheduler { trigger(id: $id) { id } } }`

interface ScheduledJob {
  id: string
  name: string
  description: string | null
  cronExpression: string | null
  enabled: boolean
  lastRunAt: string | null
  nextRunAt: string | null
}

const { data, status, refresh } = useAsyncQuery<{ scheduler: { jobs: ScheduledJob[] } }>('scheduled-jobs', schedulerGql, {})
const jobs = computed(() => data.value?.scheduler?.jobs ?? [])

const deleteTarget = ref<ScheduledJob | null>(null)
const deleteLoading = ref(false)
const rowBusy = ref<string | null>(null)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'cron', label: 'Schedule', width: '160px', muted: true },
  { key: 'enabled', label: 'Enabled', width: '80px' },
  { key: 'lastRun', label: 'Last Run', width: '140px', muted: true },
  { key: 'nextRun', label: 'Next Run', width: '140px', muted: true },
]

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Deleted')
    deleteTarget.value = null
    refresh()
  } catch (e) {
    toast.error(`Failed to delete: ${errorMessage(e)}`)
  } finally {
    deleteLoading.value = false
  }
}

async function toggleEnabled(job: ScheduledJob) {
  rowBusy.value = job.id
  try {
    await gqlMutation(job.enabled ? disableGql : enableGql, { id: job.id })
    toast.success(job.enabled ? 'Disabled' : 'Enabled')
    refresh()
  } catch (e) {
    toast.error(`Failed: ${errorMessage(e)}`)
  } finally {
    rowBusy.value = null
  }
}

async function triggerNow(job: ScheduledJob) {
  rowBusy.value = job.id
  try {
    await gqlMutation(triggerGql, { id: job.id })
    toast.success(`Triggered '${job.name}'`)
    refresh()
  } catch (e) {
    toast.error(`Failed to trigger: ${errorMessage(e)}`)
  } finally {
    rowBusy.value = null
  }
}

function onRowAction({ action, row }: { action: string; row: ScheduledJob }) {
  if (action === 'open') router.push(`/system/scheduler/${row.id}`)
  else if (action === 'trigger') triggerNow(row)
  else if (action === 'toggle') toggleEnabled(row)
  else if (action === 'delete') deleteTarget.value = row
}

function rowActions(row: ScheduledJob) {
  return [
    { id: 'open', label: 'Edit', icon: 'eye' },
    { id: 'trigger', label: 'Trigger now', icon: 'pulse' },
    { id: 'toggle', label: row.enabled ? 'Disable' : 'Enable', icon: row.enabled ? 'eye-off' : 'check' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Scheduler')"
        title="Job Scheduler"
        :subtitle="`${jobs.length} scheduled jobs`">
        <template #actions>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/system/scheduler/new')">New Job</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Scheduled Jobs">
      <GlassTable
        :columns="columns"
        :rows="jobs"
        :loading="status === 'pending' && jobs.length === 0"
        empty-text="No scheduled jobs."
        :row-actions="rowActions"
        arrow
        @row-click="(r: any) => router.push(`/system/scheduler/${r.id}`)"
        @row-action="onRowAction">
        <template #col-name="{ row }">
          <div class="name-cell">
            <span class="name">{{ row.name }}</span>
            <span v-if="row.description" class="desc">{{ row.description }}</span>
          </div>
        </template>
        <template #col-cron="{ row }"><span class="mono">{{ row.cronExpression ?? '—' }}</span></template>
        <template #col-enabled="{ row }"><span :class="['dot', row.enabled ? 'ok' : 'err']" /></template>
        <template #col-lastRun="{ row }">{{ formatDate(row.lastRunAt) }}</template>
        <template #col-nextRun="{ row }">{{ formatDate(row.nextRunAt) }}</template>
      </GlassTable>
    </SectionCard>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<style scoped>
.name-cell { display: flex; flex-direction: column; gap: 2px; }
.name { font-weight: 500; color: var(--fg-0); }
.desc { font-size: 12px; color: var(--fg-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 360px; }
</style>
