<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const schemasGql = gql`
  query GetSubmissionFormSchemas {
    formSchemas { byType(type: SUBMISSION) { id key name } }
  }
`

const submissionsGql = gql`
  query GetFormSubmissions($id: UUID!, $limit: Int!, $offset: Long!) {
    formSchemas {
      byId(id: $id) {
        submissions(limit: $limit, offset: $offset) {
          id attributes status created modified
          profile { id name slug }
        }
      }
    }
  }
`

const setStatusGql = gql`
  mutation SetSubmissionStatus($id: UUID!, $status: FormSubmissionStatus!) {
    forms { setSubmissionStatus(id: $id, status: $status) { id status } }
  }
`

const deleteGql = gql`
  mutation DeleteSubmission($id: UUID!) {
    forms { deleteSubmission(id: $id) }
  }
`

interface FormSchema { id: string; key: string; name: string }
interface Submission {
  id: string; attributes: Record<string, unknown>; status: string; created: string; modified: string
  profile: { id: string; name: string; slug: string } | null
}

const STATUS_COLORS: Record<string, string> = {
  PENDING: '#ffb547', PROCESSED: '#34d99a', SPAM: '#ff5d6c',
  REJECTED: '#6c7388', ARCHIVED: '#6c7388',
}
const STATUSES = ['PENDING', 'PROCESSED', 'REJECTED', 'SPAM', 'ARCHIVED']

const { data: schemasData } = useAsyncQuery<{ formSchemas: { byType: FormSchema[] } }>('form-schemas', schemasGql, {})
const schemas = computed(() => schemasData.value?.formSchemas?.byType ?? [])
const activeSchemaId = ref('')

watch(schemas, (s) => { if (s.length && !activeSchemaId.value) activeSchemaId.value = s[0]!.id }, { immediate: true })

const offset = ref(0)
const limit = ref(25)

const { data: subsData, status: subsStatus, refresh } = useAsyncQuery<{
  formSchemas: { byId: { submissions: Submission[] } | null }
}>('form-submissions', submissionsGql, { id: activeSchemaId, limit, offset }, { server: false })

const submissions = computed(() => subsData.value?.formSchemas?.byId?.submissions ?? [])

const viewTarget = ref<Submission | null>(null)
const deleteTarget = ref<Submission | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'profile', label: 'Profile', width: 'minmax(180px, 2fr)' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'created', label: 'Created', width: '140px', muted: true },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
]

function getRowActions(row: Submission): OverflowMenuItem[] {
  const items: OverflowMenuItem[] = [{ id: 'view', label: 'View', icon: 'eye' }]
  for (const s of STATUSES) {
    if (s !== row.status) items.push({ id: `set-${s}`, label: `Set ${s}`, icon: 'check' })
  }
  items.push({ id: 'sep', label: '', separator: true })
  items.push({ id: 'delete', label: 'Delete', icon: 'trash', danger: true })
  return items
}

async function onRowAction(action: string, row: Submission) {
  if (action === 'view') viewTarget.value = row
  else if (action === 'delete') deleteTarget.value = row
  else if (action.startsWith('set-')) {
    const newStatus = action.replace('set-', '')
    try { await gqlMutation(setStatusGql, { id: row.id, status: newStatus }); toast.success(`Status set to ${newStatus}`); refresh() }
    catch { toast.error('Failed') }
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() }
  catch { toast.error('Failed') } finally { deleteLoading.value = false }
}

function formatDate(d: string): string { return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }

const schemaOptions = computed<SelectOption[]>(() => schemas.value.map(s => ({ value: s.id, label: s.name })))
const activeSchemaKey = computed(() => schemas.value.find(s => s.id === activeSchemaId.value)?.key ?? '')

// Reset paging when the selected form type changes.
watch(activeSchemaId, () => { offset.value = 0 })
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Forms', 'Submissions')"
        title="Form Submissions"
        :subtitle="`${submissions.length} submissions`">
        <template #actions>
          <Select
            v-model="activeSchemaId"
            :options="schemaOptions"
            :accent="accent"
            size="sm"
            placeholder="Form type" />
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Submissions">
      <GlassTable
        :columns="columns"
        :rows="submissions"
        :loading="subsStatus === 'pending' && submissions.length === 0"
        empty-text="No submissions."
        :row-actions="getRowActions"
        @row-action="({ action, row }) => onRowAction(action, row as Submission)">
        <template #col-profile="{ row }">
          <NuxtLink
            v-if="(row as Submission).profile"
            :to="`/audience/profiles/${(row as Submission).profile?.id}`"
            class="profile-link">
            {{ (row as Submission).profile?.name }}
          </NuxtLink>
          <span v-else class="profile-anon">Anonymous</span>
        </template>
        <template #col-status="{ row }"><Badge :color="STATUS_COLORS[(row as Submission).status] ?? '#6c7388'">{{ (row as Submission).status }}</Badge></template>
        <template #col-created="{ row }">{{ formatDate(row.created) }}</template>
        <template #col-modified="{ row }">{{ formatDate(row.modified) }}</template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="viewTarget"
      title="Submission Details"
      icon="form"
      :accent="accent"
      @close="viewTarget = null">
      <div class="view-meta">
        <div class="view-row">
          <span class="view-label">Profile</span>
          <NuxtLink
            v-if="viewTarget.profile"
            :to="`/audience/profiles/${viewTarget.profile?.id}`"
            class="profile-link">
            {{ viewTarget.profile?.name }}
          </NuxtLink>
          <span v-else>Anonymous</span>
        </div>
        <div class="view-row"><span class="view-label">Status</span><Badge :color="STATUS_COLORS[viewTarget.status] ?? '#6c7388'">{{ viewTarget.status }}</Badge></div>
        <div class="view-row"><span class="view-label">Created</span><span>{{ formatDate(viewTarget.created) }}</span></div>
      </div>
      <div class="submission-data">
        <BoscaForm
          v-if="activeSchemaKey"
          :schema-key="activeSchemaKey"
          :model-value="viewTarget.attributes"
          readonly />
        <SectionCard v-else title="Data">
          <pre class="mono submission-json">{{ JSON.stringify(viewTarget.attributes, null, 2) }}</pre>
        </SectionCard>
      </div>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete submission?"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<style scoped>
.view-meta { display: flex; flex-direction: column; gap: 8px; }
.view-row { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.view-label { font-size: 12px; font-weight: 550; color: var(--fg-2); min-width: 80px; }
.submission-json { font-size: 12px; color: var(--fg-1); white-space: pre-wrap; word-break: break-word; margin: 0; max-height: 300px; overflow: auto; }
.submission-data { margin-top: 14px; }
.profile-link { font-weight: 500; color: var(--fg-0); text-decoration: none; }
.profile-link:hover { text-decoration: underline; }
.profile-anon { font-weight: 500; color: var(--fg-0); }
</style>
