<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()
const router = useRouter()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (returns are store-scoped) ---
const storesGql = gql`query CommerceReturnStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-return-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

const STATUS_OPTIONS: SelectOption[] = [
  { value: '', label: 'All states' },
  { value: 'REQUESTED', label: 'Requested' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'RECEIVED', label: 'Received' },
  { value: 'REFUNDED', label: 'Refunded' },
  { value: 'REJECTED', label: 'Rejected' },
]
const STATUS_LABELS: Record<string, string> = Object.fromEntries(STATUS_OPTIONS.filter(o => o.value).map(o => [o.value, o.label]))
function statusLabel(s: string): string { return STATUS_LABELS[s] ?? s }
const statusFilter = ref('')

interface ReturnRow {
  id: string
  cartId: string
  status: string
  reason: string | null
  tender: string
  refundedAmount: string
  lines: { itemId: string; quantity: number }[]
  created: string
}

const storeId = computed(() => selectedStoreId.value ?? undefined)
const statusVar = computed(() => statusFilter.value || null)
const listGql = gql`
  query CommerceReturns($storeId: UUID!, $status: ReturnStatus, $offset: Int!, $limit: Int!) {
    ecom {
      returns(storeId: $storeId, status: $status, offset: $offset, limit: $limit) {
        id cartId status reason tender refundedAmount created
        lines { itemId quantity }
      }
    }
  }
`
const { rows: returns, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<ReturnRow>(
  'commerce-returns', listGql, { storeId, status: statusVar }, d => (d as { ecom?: { returns?: ReturnRow[] } })?.ecom?.returns,
)

const columns: GlassTableColumn[] = [
  { key: 'status', label: 'State', width: 'minmax(130px, 1fr)' },
  { key: 'order', label: 'Order', width: '120px', muted: true },
  { key: 'items', label: 'Items', width: '80px', align: 'right' },
  { key: 'refunded', label: 'Refunded', width: '120px', align: 'right' },
  { key: 'reason', label: 'Reason', width: '1fr', muted: true },
  { key: 'created', label: 'When', width: '150px', muted: true },
]

function rowActions(row: ReturnRow): OverflowMenuItem[] {
  const actions: OverflowMenuItem[] = [{ id: 'open', label: 'Open order', icon: 'external-link' }]
  if (row.status === 'REQUESTED') actions.push({ id: 'approve', label: 'Approve', icon: 'check' }, { id: 'reject', label: 'Reject', icon: 'x', danger: true })
  if (row.status === 'APPROVED') actions.push({ id: 'receive', label: 'Mark received', icon: 'package' }, { id: 'reject', label: 'Reject', icon: 'x', danger: true })
  if (row.status === 'RECEIVED') actions.push({ id: 'refund', label: 'Restock + refund', icon: 'undo' })
  return actions
}

function itemCount(row: ReturnRow): number { return row.lines.reduce((n, l) => n + l.quantity, 0) }
function fmtDate(iso: string): string { return iso ? new Date(iso).toLocaleString() : '—' }

const busy = ref(false)
const opError = ref('')
const approveGql = gql`mutation ApproveReturn($id: UUID!) { ecom { returns { approve(id: $id) { id status } } } }`
const receiveGql = gql`mutation ReceiveReturn($id: UUID!) { ecom { returns { receive(id: $id) { id status } } } }`
const refundGql = gql`mutation RefundReturn($id: UUID!) { ecom { returns { refund(id: $id) { id status } } } }`
const rejectGql = gql`mutation RejectReturn($id: UUID!, $reason: String) { ecom { returns { reject(id: $id, reason: $reason) { id status } } } }`

async function onRowAction(payload: { action: string; row: ReturnRow }) {
  if (payload.action === 'open') { router.push(`/commerce/orders/${payload.row.cartId}`); return }
  busy.value = true
  opError.value = ''
  try {
    if (payload.action === 'approve') await mutation(approveGql, { id: payload.row.id })
    else if (payload.action === 'receive') await mutation(receiveGql, { id: payload.row.id })
    else if (payload.action === 'refund') await mutation(refundGql, { id: payload.row.id })
    else if (payload.action === 'reject') await mutation(rejectGql, { id: payload.row.id, reason: null })
    await refresh()
  } catch (e: unknown) {
    opError.value = e instanceof Error ? e.message : 'Operation failed'
  } finally {
    busy.value = false
  }
}

// --- create a return from an order ---
const TENDER_OPTIONS: SelectOption[] = [
  { value: 'ORIGINAL', label: 'Original payment method' },
  { value: 'ACCOUNT_CREDIT', label: 'Store credit' },
  { value: 'CHECK', label: 'Check' },
]
const showCreate = ref(false)
const createOrderId = ref('')
const createReason = ref('')
const createTender = ref('ORIGINAL')
const createCheckNumber = ref('')
const createError = ref('')
const creating = ref(false)
interface OrderItem { id: string; catalogProductId: string; quantity: number; type: string }
const orderItems = ref<OrderItem[]>([])
const returnQty = reactive<Record<string, number>>({})

const orderGql = gql`query CommerceReturnOrder($id: UUID!) { ecom { cart(id: $id) { id items { id catalogProductId quantity type } } } }`
const requestGql = gql`mutation RequestReturn($input: ReturnInput!) { ecom { returns { request(input: $input) { id } } } }`

function openCreate() {
  createOrderId.value = ''
  createReason.value = ''
  createTender.value = 'ORIGINAL'
  createCheckNumber.value = ''
  createError.value = ''
  orderItems.value = []
  showCreate.value = true
}

async function loadOrder() {
  createError.value = ''
  if (!createOrderId.value.trim()) { createError.value = 'Enter an order id.'; return }
  try {
    const result = await query<{ ecom: { cart: { items: OrderItem[] } | null } }>(orderGql, { id: createOrderId.value.trim() })
    const items = (result.ecom?.cart?.items ?? []).filter(i => i.type === 'PHYSICAL')
    if (!items.length) { createError.value = 'That order has no returnable (physical) items.'; orderItems.value = []; return }
    orderItems.value = items
    items.forEach(i => { returnQty[i.id] = 0 })
  } catch (e: unknown) {
    createError.value = e instanceof Error ? e.message : 'Could not load the order.'
  }
}

async function submitCreate() {
  const lines = orderItems.value
    .map(i => ({ itemId: i.id, quantity: Number(returnQty[i.id] ?? 0) }))
    .filter(l => l.quantity > 0)
  if (!lines.length) { createError.value = 'Select at least one item and quantity.'; return }
  creating.value = true
  createError.value = ''
  try {
    await mutation(requestGql, {
      input: {
        cartId: createOrderId.value.trim(),
        reason: createReason.value.trim() || null,
        tender: createTender.value,
        checkNumber: createTender.value === 'CHECK' ? (createCheckNumber.value.trim() || null) : null,
        lines,
      },
    })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    createError.value = e instanceof Error ? e.message : 'Could not create the return.'
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Returns')"
        title="Returns"
        :subtitle="selectedStoreId ? `${returns.length} returns` : 'Select a store'">
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
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedStoreId"
            @click="openCreate">New Return</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load returns — {{ loadError.message }}</div>
    <div v-if="opError" class="query-error">{{ opError }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores.</div>
    <template v-else>
      <p class="hint">Process RMAs: approve a request, mark the goods received, then restock + refund — which reverses the order lines through the existing item-refund path.</p>
      <GlassTable
        :columns="columns"
        :rows="returns"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No returns match."
        :row-actions="rowActions"
        @row-action="onRowAction"
        @row-click="(row) => router.push(`/commerce/orders/${row.cartId}`)">
        <template #col-status="{ row }">
          <Badge :color="row.status === 'REJECTED' ? 'var(--err, #ff5c5c)' : row.status === 'REFUNDED' ? 'var(--ok, #4ade80)' : accent">{{ statusLabel(row.status) }}</Badge>
        </template>
        <template #col-order="{ row }"><code class="mono">{{ row.cartId.slice(0, 8) }}</code></template>
        <template #col-items="{ row }">{{ itemCount(row) }}</template>
        <template #col-refunded="{ row }">{{ Number(row.refundedAmount) > 0 ? formatMoney(row.refundedAmount) : '—' }}</template>
        <template #col-reason="{ row }">{{ row.reason || '—' }}</template>
        <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
      </GlassTable>
      <ListPager
        v-if="selectedStoreId"
        v-model:offset="offset"
        :page-size="pageSize"
        :count="returns.length"
        :has-more="hasMore" />
    </template>

    <Modal
      v-if="showCreate"
      title="New Return"
      icon="undo"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <div class="order-row">
          <TextInput
            v-model="createOrderId"
            label="Order id"
            placeholder="cart UUID"
            mono />
          <Button size="sm" :disabled="!createOrderId" @click="loadOrder">Load order</Button>
        </div>
        <div v-if="orderItems.length" class="lines">
          <div v-for="it in orderItems" :key="it.id" class="line">
            <code class="mono">{{ it.catalogProductId.slice(0, 8) }}</code>
            <span class="muted">ordered {{ it.quantity }}</span>
            <NumberInput v-model="returnQty[it.id]" :min="0" :max="it.quantity" />
          </div>
        </div>
        <Select v-model="createTender" label="Refund to" :options="TENDER_OPTIONS" />
        <TextInput
          v-if="createTender === 'CHECK'"
          v-model="createCheckNumber"
          label="Check number"
          mono />
        <TextInput v-model="createReason" label="Reason (optional)" />
        <p v-if="createError" class="form-error">{{ createError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="creating || !orderItems.length"
          @click="submitCreate">{{ creating ? 'Working…' : 'Request return' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 12px; }
.pick { width: 170px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.order-row { display: flex; gap: 8px; align-items: flex-end; }
.lines { display: flex; flex-direction: column; gap: 8px; max-height: 240px; overflow-y: auto; }
.line { display: grid; grid-template-columns: 100px 1fr 90px; gap: 8px; align-items: center; }
.muted { color: var(--fg-3); font-size: 12.5px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
</style>
