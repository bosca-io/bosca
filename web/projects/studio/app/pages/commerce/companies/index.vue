<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import type { CompanyInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const router = useRouter()

interface CompanyRow {
  id: string
  organization: { id: string; name: string }
  profile: { id: string; name: string }
  created: string
}

const listGql = gql`
  query CommerceCompaniesList($offset: Int!, $limit: Int!) {
    ecom {
      companies(offset: $offset, limit: $limit) {
        id
        organization { id name }
        profile { id name }
        created
      }
    }
  }
`

const { rows: companies, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<CompanyRow>(
  'commerce-companies-list', listGql, {}, d => (d as { ecom?: { companies?: CompanyRow[] } })?.ecom?.companies,
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Company', width: 'minmax(220px, 2fr)' },
  { key: 'profile', label: 'Profile', width: '1fr', muted: true },
  { key: 'created', label: 'Created', width: '150px', muted: true },
]

function fmt(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const showCreate = ref(false)
const newName = ref('')
const saving = ref(false)
const error = ref('')

const addGql = gql`
  mutation AddCompany($input: CompanyInput!) {
    ecom { companies { add(input: $input) { id } } }
  }
`

async function handleCreate() {
  if (!newName.value.trim()) { error.value = 'Name is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: CompanyInput = { name: newName.value.trim() }
    await mutation(addGql, { input })
    showCreate.value = false
    newName.value = ''
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create company'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Companies')"
        title="Companies"
        :subtitle="`${companies.length} ${companies.length === 1 ? 'company' : 'companies'}`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Company</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load companies — {{ loadError.message }}</div>
    <GlassTable
      :columns="columns"
      :rows="companies"
      row-key="id"
      :loading="status === 'pending' && companies.length === 0"
      empty-text="No companies yet. Create one to start configuring commerce."
      @row-click="(row) => router.push(`/commerce/companies/${row.id}`)">
      <template #col-name="{ row }">{{ row.organization?.name }}</template>
      <template #col-profile="{ row }">{{ row.profile?.name }}</template>
      <template #col-created="{ row }">{{ fmt(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-model:offset="offset"
      :page-size="pageSize"
      :count="companies.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Company"
      icon="building"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <p class="hint">Creates the company plus its organization &amp; profile in the profiles domain.</p>
        <TextInput v-model="newName" label="Name" placeholder="Acme, Inc." />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">{{ saving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
</style>
