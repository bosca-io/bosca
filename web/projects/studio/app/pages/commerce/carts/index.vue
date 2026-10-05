<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { CartInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const router = useRouter()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (carts are store-scoped) ---
const storesGql = gql`query CommerceCartStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-cart-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

interface CartRow {
  id: string
  status: string[]
  quantity: number
  salesTotal: string
  due: string
  currency: string
  created: string
  modified: string
  account: { id: string; type: string } | null
  customer: { id: string; profile: { name: string } } | null
}

const storeId = computed(() => selectedStoreId.value ?? undefined)
const listGql = gql`
  query CommerceCarts($storeId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      carts(storeId: $storeId, offset: $offset, limit: $limit) {
        id
        status
        quantity
        salesTotal
        due
        currency
        created
        modified
        account { id type }
        customer { id profile { name } }
      }
    }
  }
`
const { rows: carts, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<CartRow>(
  'commerce-carts', listGql, { storeId }, d => (d as { ecom?: { carts?: CartRow[] } })?.ecom?.carts,
)

// Optional account / customer for new carts.
const accountsGql = gql`query CommerceCartAccounts($companyId: UUID!) { ecom { accounts(companyId: $companyId, offset: 0, limit: 500) { id type customers { profile { name } } } } }`
const { data: acctData } = useAsyncQuery<{ ecom: { accounts: { id: string; type: string; customers: { profile: { name: string } }[] }[] } }>(
  'commerce-cart-accounts', accountsGql, { companyId },
)
const accountOptions = computed<SelectOption[]>(() => [
  { value: '', label: 'No account' },
  ...(acctData.value?.ecom?.accounts ?? []).map((a) => {
    const names = a.customers.map(c => c.profile?.name).filter(Boolean).join(', ')
    return { value: a.id, label: `${names || a.type} · ${a.id.slice(0, 8)}` }
  }),
])

const columns: GlassTableColumn[] = [
  { key: 'id', label: 'Cart', width: '120px', muted: true },
  { key: 'status', label: 'Status', width: 'minmax(160px, 1.5fr)' },
  { key: 'buyer', label: 'Buyer', width: '1fr', muted: true },
  { key: 'qty', label: 'Items', width: '80px', align: 'right' },
  { key: 'total', label: 'Total', width: '100px', align: 'right' },
  { key: 'due', label: 'Due', width: '100px', align: 'right' },
  { key: 'updated', label: 'Updated', width: '130px', muted: true },
]

function buyer(c: CartRow): string {
  return c.customer?.profile?.name || (c.account ? `${c.account.type} account` : 'Guest')
}

// --- create cart ---
const showCreate = ref(false)
const createAccountId = ref('')
const creating = ref(false)
const createError = ref('')
const createGql = gql`mutation CreateCart($input: CartInput!) { ecom { carts { create(input: $input) { id } } } }`

function openCreate() { createAccountId.value = ''; createError.value = ''; showCreate.value = true }

async function handleCreate() {
  if (!selectedStoreId.value) { createError.value = 'Select a store first.'; return }
  creating.value = true
  createError.value = ''
  try {
    const input: CartInput = { storeId: selectedStoreId.value, accountId: createAccountId.value || null }
    const result = await mutation<{ ecom: { carts: { create: { id: string } } } }>(createGql, { input })
    showCreate.value = false
    const id = result?.ecom?.carts?.create?.id
    await refresh()
    if (id) router.push(`/commerce/carts/${id}`)
  } catch (e: unknown) {
    createError.value = e instanceof Error ? e.message : 'Failed to create cart'
  } finally {
    creating.value = false
  }
}

function fmtDate(iso: string): string { return iso ? new Date(iso).toLocaleString() : '—' }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Carts')"
        title="Carts"
        :subtitle="selectedStoreId ? `${carts.length} carts` : 'Select a store'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <div class="pick"><Select
            v-model="selectedStoreId"
            placeholder="Store…"
            :options="storeOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedStoreId"
            @click="openCreate">New Cart</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load carts — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="carts"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No carts in this store yet."
      @row-click="(row) => router.push(`/commerce/carts/${row.id}`)">
      <template #col-id="{ row }"><code class="mono">{{ row.id.slice(0, 8) }}</code></template>
      <template #col-status="{ row }">
        <span class="flags">
          <Badge v-for="f in row.status.slice(0, 3)" :key="f" :color="accent">{{ f }}</Badge>
          <span v-if="row.status.length > 3" class="more">+{{ row.status.length - 3 }}</span>
        </span>
      </template>
      <template #col-buyer="{ row }">{{ buyer(row) }}</template>
      <template #col-qty="{ row }">{{ row.quantity }}</template>
      <template #col-total="{ row }">{{ formatMoney(row.salesTotal, row.currency) }}</template>
      <template #col-due="{ row }">{{ formatMoney(row.due, row.currency) }}</template>
      <template #col-updated="{ row }">{{ fmtDate(row.modified) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedStoreId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="carts.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Cart"
      icon="inspect"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <p class="hint">Opens an empty cart in the selected store. Attach an account to enable payments, promotions, and store credit.</p>
        <Select v-model="createAccountId" label="Account (optional)" :options="accountOptions" />
        <p v-if="createError" class="form-error">{{ createError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="creating"
          @click="handleCreate">{{ creating ? 'Creating…' : 'Create & open' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.flags { display: inline-flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.more { font-size: 11px; color: var(--fg-3); }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 170px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
