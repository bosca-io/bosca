<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const router = useRouter()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (orders are store-scoped) ---
const storesGql = gql`query CommerceOrderStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-order-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

interface OrderRow {
  id: string
  status: string[]
  quantity: number
  salesTotal: string
  currency: string
  created: string
  modified: string
  account: { id: string; type: string } | null
  customer: { id: string; profile: { name: string } } | null
}

// Status filter — PAID matches every order (all orders are at least paid); the others narrow to a
// fulfillment/lifecycle state via the backend `cartsByStatus` bitmask query.
const STATUS_FILTER_OPTIONS: SelectOption[] = [
  { value: 'PAID', label: 'All orders' },
  { value: 'PREPARING', label: 'Preparing' },
  { value: 'SHIPPING', label: 'Shipping' },
  { value: 'SHIPPED', label: 'Shipped' },
  { value: 'COMPLETE', label: 'Complete' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: 'REFUNDED', label: 'Refunded' },
  { value: 'LOCKED', label: 'Locked' },
]
const statusFilter = ref('PAID')

const storeId = computed(() => selectedStoreId.value ?? undefined)
const listGql = gql`
  query CommerceOrders($storeId: UUID!, $status: CartStatusFlag!, $offset: Int!, $limit: Int!) {
    ecom {
      cartsByStatus(storeId: $storeId, status: $status, offset: $offset, limit: $limit) {
        id
        status
        quantity
        salesTotal
        currency
        created
        modified
        account { id type }
        customer { id profile { name } }
      }
    }
  }
`
const { rows: orders, hasMore, offset, pageSize, status, refresh: _refresh, error: loadError } = usePagedList<OrderRow>(
  'commerce-orders', listGql, { storeId, status: statusFilter }, d => (d as { ecom?: { cartsByStatus?: OrderRow[] } })?.ecom?.cartsByStatus,
)

const columns: GlassTableColumn[] = [
  { key: 'id', label: 'Order', width: '120px', muted: true },
  { key: 'fulfillment', label: 'Fulfillment', width: 'minmax(160px, 1.5fr)' },
  { key: 'buyer', label: 'Buyer', width: '1fr', muted: true },
  { key: 'qty', label: 'Items', width: '80px', align: 'right' },
  { key: 'total', label: 'Total', width: '100px', align: 'right' },
  { key: 'placed', label: 'Placed', width: '130px', muted: true },
]

function buyer(o: OrderRow): string {
  return o.customer?.profile?.name || (o.account ? `${o.account.type} account` : 'Guest')
}

function fmtDate(iso: string): string { return iso ? new Date(iso).toLocaleString() : '—' }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Orders')"
        title="Orders"
        :subtitle="selectedStoreId ? `${orders.length} orders` : 'Select a store'">
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
          <div class="pick"><Select v-model="statusFilter" :options="STATUS_FILTER_OPTIONS" size="sm" /></div>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load orders — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="orders"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No paid orders in this store yet."
      @row-click="(row) => router.push(`/commerce/orders/${row.id}`)">
      <template #col-id="{ row }"><code class="mono">{{ row.id.slice(0, 8) }}</code></template>
      <template #col-fulfillment="{ row }">
        <Badge :color="accent">{{ fulfillmentStage(row.status) }}</Badge>
      </template>
      <template #col-buyer="{ row }">{{ buyer(row) }}</template>
      <template #col-qty="{ row }">{{ row.quantity }}</template>
      <template #col-total="{ row }">{{ formatMoney(row.salesTotal, row.currency) }}</template>
      <template #col-placed="{ row }">{{ fmtDate(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedStoreId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="orders.length"
      :has-more="hasMore" />
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.pick { width: 170px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
