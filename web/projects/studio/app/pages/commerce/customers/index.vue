<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { CustomerInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const { searchProfiles } = useProfileSearch()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface CustomerRow {
  id: string
  created: string
  profile: { id: string; name: string }
  defaultAccount: { id: string; type: string } | null
  accounts: { id: string; type: string }[]
}

const listGql = gql`
  query CommerceCustomers($companyId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      customers(companyId: $companyId, offset: $offset, limit: $limit) {
        id
        created
        profile { id name }
        defaultAccount { id type }
        accounts { id type }
      }
    }
  }
`
const { rows: customers, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<CustomerRow>(
  'commerce-customers', listGql, { companyId }, d => (d as { ecom?: { customers?: CustomerRow[] } })?.ecom?.customers,
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Customer', width: 'minmax(200px, 2fr)' },
  { key: 'default', label: 'Default account', width: '1fr', muted: true },
  { key: 'accounts', label: 'Accounts', width: '100px', align: 'right' },
  { key: 'created', label: 'Joined', width: '130px', muted: true },
]

const rowActions = (row: CustomerRow): OverflowMenuItem[] => [
  ...(row.accounts.length ? [{ id: 'default', label: 'Set default account', icon: 'star' }] : []),
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

// --- create (profile typeahead) ---
const showCreate = ref(false)
const profileQuery = ref('')
const profileResults = ref<SelectOption[]>([])
const selectedProfile = ref<{ id: string; name: string } | null>(null)
const saving = ref(false)
const error = ref('')
let searchToken = 0

watch(profileQuery, async (q) => {
  const term = q.trim()
  if (!term || (selectedProfile.value && term === selectedProfile.value.name)) { profileResults.value = []; return }
  const token = ++searchToken
  const res = await searchProfiles(term)
  if (token === searchToken) profileResults.value = res
})

function pickProfile(opt: SelectOption) {
  selectedProfile.value = { id: opt.value, name: opt.label }
  profileQuery.value = opt.label
  profileResults.value = []
}

function openCreate() {
  profileQuery.value = ''
  profileResults.value = []
  selectedProfile.value = null
  error.value = ''
  showCreate.value = true
}

const addGql = gql`mutation AddCustomer($input: CustomerInput!) { ecom { customers { add(input: $input) { id } } } }`

async function handleCreate() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!selectedProfile.value) { error.value = 'Choose a profile to link.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: CustomerInput = { companyId: selectedId.value, profileId: selectedProfile.value.id }
    await mutation(addGql, { input })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create customer'
  } finally {
    saving.value = false
  }
}

// --- set default account ---
const showDefault = ref(false)
const defaultTarget = ref<CustomerRow | null>(null)
const defaultAccountId = ref<string | null>(null)
const defaultSaving = ref(false)
const defaultAccountOptions = computed<SelectOption[]>(() =>
  (defaultTarget.value?.accounts ?? []).map(a => ({ value: a.id, label: `${a.type} · ${a.id.slice(0, 8)}` })),
)
const setDefaultGql = gql`mutation SetDefaultAccount($id: UUID!, $accountId: UUID!) { ecom { customers { customer(id: $id) { setDefaultAccount(accountId: $accountId) { id } } } } }`

// --- delete ---
const showDelete = ref(false)
const deleteTarget = ref<CustomerRow | null>(null)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteCustomer($id: UUID!) { ecom { customers { customer(id: $id) { delete } } } }`

function onRowAction(payload: { action: string; row: CustomerRow }) {
  if (payload.action === 'default') {
    defaultTarget.value = payload.row
    defaultAccountId.value = payload.row.defaultAccount?.id ?? payload.row.accounts[0]?.id ?? null
    showDefault.value = true
  } else if (payload.action === 'delete') {
    deleteTarget.value = payload.row
    showDelete.value = true
  }
}

async function handleSetDefault() {
  if (!defaultTarget.value || !defaultAccountId.value) return
  defaultSaving.value = true
  try {
    await mutation(setDefaultGql, { id: defaultTarget.value.id, accountId: defaultAccountId.value })
    showDefault.value = false
    await refresh()
  } finally {
    defaultSaving.value = false
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: deleteTarget.value.id })
    showDelete.value = false
    await refresh()
  } finally {
    deleting.value = false
  }
}

function fmtDate(iso: string): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Customers')"
        title="Customers"
        :subtitle="selectedId ? `${customers.length} customers` : 'Select a company'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedId"
            @click="openCreate">New Customer</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load customers — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company to view its customers.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="customers"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No customers in this company yet."
      :row-actions="rowActions"
      @row-action="onRowAction">
      <template #col-name="{ row }">{{ row.profile?.name }}</template>
      <template #col-default="{ row }">
        <span v-if="row.defaultAccount">{{ row.defaultAccount.type }}</span><span v-else>—</span>
      </template>
      <template #col-accounts="{ row }">{{ row.accounts.length }}</template>
      <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="customers.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Customer"
      icon="user"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <p class="hint">A customer is a shopper in this company, backed by a profile.</p>
        <div class="field">
          <span class="lbl">Profile</span>
          <SearchInput v-model="profileQuery" placeholder="Search profiles by name…" max-width="100%" />
          <div v-if="profileResults.length" class="results">
            <button
              v-for="r in profileResults"
              :key="r.value"
              type="button"
              class="result"
              @click="pickProfile(r)">
              {{ r.label }}
            </button>
          </div>
          <p v-if="selectedProfile" class="hint">Linking <strong>{{ selectedProfile.name }}</strong>.</p>
        </div>
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

    <Modal
      v-if="showDefault"
      title="Set Default Account"
      icon="star"
      :accent="accent"
      @close="showDefault = false">
      <div class="form-stack">
        <p class="hint">The default account is used for <strong>{{ defaultTarget?.profile.name }}</strong>'s new carts and orders.</p>
        <Select v-model="defaultAccountId" label="Default account" :options="defaultAccountOptions" />
      </div>
      <template #footer>
        <Button @click="showDefault = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="defaultSaving"
          @click="handleSetDefault">{{ defaultSaving ? 'Saving…' : 'Save' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Customer"
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ deleteTarget?.profile.name }}</strong>? Their billing accounts are not removed.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.lbl { font-size: 12px; color: var(--fg-3); }
.results { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; max-height: 220px; overflow-y: auto; }
.result { text-align: left; padding: 8px 12px; background: transparent; border: none; border-bottom: 1px solid var(--line); cursor: pointer; color: inherit; font: inherit; font-size: 13px; }
.result:last-child { border-bottom: none; }
.result:hover { background: var(--bg-2); }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
</style>
