<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const router = useRouter()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (shipments are store-scoped) ---
const storesGql = gql`query CommerceShipmentStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-shipment-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

// --- status filter ('' = all states) ---
const STATUS_OPTIONS: SelectOption[] = [
  { value: '', label: 'All states' },
  { value: 'UNABLE_TO_PACKAGE', label: 'Unable to package' },
  { value: 'AWAITING', label: 'Awaiting dispatch' },
  { value: 'SHIPPED', label: 'Shipped' },
  { value: 'IN_TRANSIT', label: 'In transit' },
  { value: 'OUT_FOR_DELIVERY', label: 'Out for delivery' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'RETURNED', label: 'Returned' },
  { value: 'FAILURE', label: 'Delivery failed' },
  { value: 'CANCELLED', label: 'Cancelled' },
]
const STATUS_LABELS: Record<string, string> = Object.fromEntries(STATUS_OPTIONS.filter(o => o.value).map(o => [o.value, o.label]))
function statusLabel(s: string): string { return STATUS_LABELS[s] ?? s }
const statusFilter = ref('')

interface ShipmentLine { sku: string; quantity: number }
interface ShipmentParcel { containerId: string | null; lines: ShipmentLine[] }
interface ShipmentRow {
  id: string
  cartId: string
  status: string
  carrier: string | null
  tracking: string | null
  carrierStatus: string | null
  shipped: string | null
  delivered: string | null
  fulfillmentCenter: { id: string; name: string }
  parcels: ShipmentParcel[]
  unpacked: ShipmentLine[]
}

const storeId = computed(() => selectedStoreId.value ?? undefined)
// The backend treats no `status` as "all"; pass null when the filter is empty so it returns every state.
const statusVar = computed(() => statusFilter.value || null)
const listGql = gql`
  query CommerceShipments($storeId: UUID!, $status: ShipmentStatus, $offset: Int!, $limit: Int!) {
    ecom {
      shipmentsByStore(storeId: $storeId, status: $status, offset: $offset, limit: $limit) {
        id cartId status carrier tracking carrierStatus shipped delivered
        fulfillmentCenter { id name }
        parcels { containerId lines { sku quantity } }
        unpacked { sku quantity }
      }
    }
  }
`
const { rows: shipments, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<ShipmentRow>(
  'commerce-shipments', listGql, { storeId, status: statusVar }, d => (d as { ecom?: { shipmentsByStore?: ShipmentRow[] } })?.ecom?.shipmentsByStore,
)

const columns: GlassTableColumn[] = [
  { key: 'status', label: 'State', width: 'minmax(150px, 1fr)' },
  { key: 'boxes', label: 'Boxes', width: '80px', align: 'right' },
  { key: 'center', label: 'Fulfillment center', width: '1fr', muted: true },
  { key: 'carrier', label: 'Carrier · tracking', width: 'minmax(180px, 1.5fr)', muted: true },
  { key: 'carrierStatus', label: 'Carrier status', width: '1fr', muted: true },
  { key: 'when', label: 'Shipped / delivered', width: '160px', muted: true },
]

const inFlight = ['SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY']
function rowActions(row: ShipmentRow): OverflowMenuItem[] {
  const actions: OverflowMenuItem[] = [{ id: 'open', label: 'Open order', icon: 'external-link' }]
  // A new/edited box can make an unpackable shipment packable, so Re-pack is offered in both states.
  if (row.status === 'AWAITING' || row.status === 'UNABLE_TO_PACKAGE') {
    actions.push({ id: 'repack', label: 'Re-pack', icon: 'refresh' })
  }
  if (row.status === 'AWAITING') actions.push({ id: 'ship', label: 'Mark shipped', icon: 'package' })
  if (inFlight.includes(row.status)) actions.push({ id: 'refresh', label: 'Refresh tracking', icon: 'refresh' })
  return actions
}

function fmtDate(iso: string | null): string { return iso ? new Date(iso).toLocaleString() : '' }
function boxes(row: ShipmentRow): number { return row.parcels.length }

const busy = ref(false)
const opError = ref('')
const shipGql = gql`mutation ShipFromList($id: UUID!) { ecom { fulfillment { shipment(id: $id) { ship { id status } } } } }`
const refreshGql = gql`mutation RefreshFromList($id: UUID!) { ecom { fulfillment { shipment(id: $id) { refreshTracking { id status } } } } }`
const repackGql = gql`mutation RepackFromList($id: UUID!) { ecom { fulfillment { shipment(id: $id) { repack { id } } } } }`

async function onRowAction(payload: { action: string; row: ShipmentRow }) {
  if (payload.action === 'open') { router.push(`/commerce/orders/${payload.row.cartId}`); return }
  busy.value = true
  opError.value = ''
  try {
    if (payload.action === 'ship') await mutation(shipGql, { id: payload.row.id })
    else if (payload.action === 'refresh') await mutation(refreshGql, { id: payload.row.id })
    else if (payload.action === 'repack') await mutation(repackGql, { id: payload.row.id })
    await refresh()
  } catch (e: unknown) {
    opError.value = e instanceof Error ? e.message : 'Operation failed'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Shipments')"
        title="Shipments"
        :subtitle="selectedStoreId ? `${shipments.length} shipments` : 'Select a store'">
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
          <div class="pick"><Select v-model="statusFilter" :options="STATUS_OPTIONS" size="sm" /></div>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load shipments — {{ loadError.message }}</div>
    <div v-if="opError" class="query-error">{{ opError }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores.</div>
    <template v-else>
      <p class="hint">Monitor fulfillment across the store. Tracking advances automatically (a sweep every 15 min) or on demand via Refresh tracking; Mark shipped dispatches an awaiting shipment.</p>
      <GlassTable
        :columns="columns"
        :rows="shipments"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No shipments match."
        :row-actions="rowActions"
        @row-action="onRowAction"
        @row-click="(row) => router.push(`/commerce/orders/${row.cartId}`)">
        <template #col-status="{ row }">
          <Badge :color="row.status === 'UNABLE_TO_PACKAGE' ? 'var(--err, #ff5c5c)' : row.status === 'DELIVERED' ? 'var(--ok, #4ade80)' : accent">{{ statusLabel(row.status) }}</Badge>
        </template>
        <template #col-boxes="{ row }">{{ row.status === 'UNABLE_TO_PACKAGE' ? '—' : boxes(row) }}</template>
        <template #col-center="{ row }">{{ row.fulfillmentCenter?.name }}</template>
        <template #col-carrier="{ row }">
          <span v-if="row.tracking">{{ row.carrier }} · <code class="mono">{{ row.tracking }}</code></span>
          <span v-else-if="row.carrier">{{ row.carrier }}</span>
          <span v-else>—</span>
        </template>
        <template #col-carrierStatus="{ row }">{{ row.carrierStatus || '—' }}</template>
        <template #col-when="{ row }">{{ fmtDate(row.delivered) || fmtDate(row.shipped) || '—' }}</template>
      </GlassTable>
      <ListPager
        v-if="selectedStoreId"
        v-model:offset="offset"
        :page-size="pageSize"
        :count="shipments.length"
        :has-more="hasMore" />
    </template>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 12px; }
.pick { width: 170px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
