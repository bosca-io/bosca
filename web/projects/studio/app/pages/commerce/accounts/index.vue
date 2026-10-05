<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { AccountInput, AccountType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const router = useRouter()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface AccountRow {
  id: string
  type: string
  credit: string
  created: string
  customers: { id: string; profile: { name: string } }[]
}

const listGql = gql`
  query CommerceAccounts($companyId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      accounts(companyId: $companyId, offset: $offset, limit: $limit) {
        id
        type
        credit
        created
        customers { id profile { name } }
      }
    }
  }
`
const { rows: accounts, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<AccountRow>(
  'commerce-accounts', listGql, { companyId }, d => (d as { ecom?: { accounts?: AccountRow[] } })?.ecom?.accounts,
)

// Customers in the company, for the create form's optional attachment.
const customersGql = gql`query CommerceAccountCustomers($companyId: UUID!) { ecom { customers(companyId: $companyId, offset: 0, limit: 500) { id profile { name } } } }`
const { data: custData } = useAsyncQuery<{ ecom: { customers: { id: string; profile: { name: string } }[] } }>(
  'commerce-account-customers', customersGql, { companyId },
)
const companyCustomers = computed(() => custData.value?.ecom?.customers ?? [])

const columns: GlassTableColumn[] = [
  { key: 'type', label: 'Type', width: '140px' },
  { key: 'customers', label: 'Customers', width: 'minmax(200px, 2fr)', muted: true },
  { key: 'credit', label: 'Credit', width: '110px', align: 'right' },
  { key: 'created', label: 'Created', width: '130px', muted: true },
]

const showCreate = ref(false)
const form = reactive({ type: 'CONSUMER', customerIds: [] as string[] })
const saving = ref(false)
const error = ref('')

function openCreate() {
  form.type = 'CONSUMER'
  form.customerIds = []
  error.value = ''
  showCreate.value = true
}

function toggleCustomer(id: string) {
  const i = form.customerIds.indexOf(id)
  if (i >= 0) form.customerIds.splice(i, 1)
  else form.customerIds.push(id)
}

const addGql = gql`mutation AddAccount($input: AccountInput!) { ecom { accounts { add(input: $input) { id } } } }`

async function handleCreate() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: AccountInput = { companyId: selectedId.value, type: form.type as AccountType, customerIds: form.customerIds }
    const result = await mutation<{ ecom: { accounts: { add: { id: string } } } }>(addGql, { input })
    showCreate.value = false
    const id = result?.ecom?.accounts?.add?.id
    await refresh()
    if (id) router.push(`/commerce/accounts/${id}`)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create account'
  } finally {
    saving.value = false
  }
}

function customerNames(row: AccountRow): string {
  const names = row.customers.map(c => c.profile?.name).filter(Boolean)
  return names.length ? names.join(', ') : '—'
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Accounts')"
        title="Billing Accounts"
        :subtitle="selectedId ? `${accounts.length} accounts` : 'Select a company'">
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
            @click="openCreate">New Account</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load accounts — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company to view its billing accounts.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="accounts"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No billing accounts in this company yet."
      @row-click="(row) => router.push(`/commerce/accounts/${row.id}`)">
      <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
      <template #col-customers="{ row }">{{ customerNames(row) }}</template>
      <template #col-credit="{ row }">{{ formatMoney(row.credit) }}</template>
      <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="accounts.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Billing Account"
      icon="user"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <Select v-model="form.type" label="Type" :options="ACCOUNT_TYPE_OPTIONS" />
        <div class="field">
          <span class="lbl">Attach customers (optional)</span>
          <div v-if="companyCustomers.length" class="picklist">
            <label v-for="c in companyCustomers" :key="c.id" class="pick-row">
              <input type="checkbox" :checked="form.customerIds.includes(c.id)" @change="toggleCustomer(c.id)" >
              <span>{{ c.profile?.name }}</span>
            </label>
          </div>
          <p v-else class="hint">No customers yet — you can attach them later.</p>
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
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.lbl { font-size: 12px; color: var(--fg-3); }
.picklist { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; max-height: 220px; overflow-y: auto; }
.pick-row { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-bottom: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.pick-row:last-child { border-bottom: none; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
</style>
