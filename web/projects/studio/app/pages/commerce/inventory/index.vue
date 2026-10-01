<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { InventoryInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// Company-wide stock: every product's inventory across every fulfillment center, flattened into one
// list. Sourced from `products(companyId) { inventory }` so adding stock for any product just shows up
// (no product selector to hunt through). Up to 500 products — the established cap for this context.
interface ProductWithInventory {
  id: string
  manufacturerSku: string
  metadata: { name: string }
  inventory: {
    id: string
    sku: string
    quantity: number
    pending: number
    inCart: number
    available: number
    fulfillmentCenter: { id: string; name: string }
  }[]
}
const listGql = gql`
  query CommerceInventory($companyId: UUID!) {
    ecom {
      products(companyId: $companyId, offset: 0, limit: 500) {
        id
        manufacturerSku
        metadata { name }
        inventory {
          id
          sku
          quantity
          pending
          inCart
          available
          fulfillmentCenter { id name }
        }
      }
    }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { products: ProductWithInventory[] } }>(
  'commerce-inventory', listGql, { companyId },
)
const products = computed(() => data.value?.ecom?.products ?? [])
const productOptions = computed<SelectOption[]>(() => products.value.map(p => ({ value: p.id, label: p.metadata.name })))

interface InventoryRow {
  id: string
  productId: string
  productName: string
  sku: string
  quantity: number
  pending: number
  inCart: number
  available: number
  fulfillmentCenter: { id: string; name: string }
}
const allRows = computed<InventoryRow[]>(() =>
  products.value.flatMap(p =>
    p.inventory.map(inv => ({
      id: inv.id,
      productId: p.id,
      productName: p.metadata.name,
      sku: inv.sku,
      quantity: inv.quantity,
      pending: inv.pending,
      inCart: inv.inCart,
      available: inv.available,
      fulfillmentCenter: inv.fulfillmentCenter,
    })),
  ),
)

// --- filters (client-side over the flat list) ---
const centerFilter = ref('')
const search = ref('')
const rows = computed(() => {
  const q = search.value.trim().toLowerCase()
  return allRows.value.filter(r =>
    (!centerFilter.value || r.fulfillmentCenter.id === centerFilter.value)
    && (!q || r.productName.toLowerCase().includes(q) || r.sku.toLowerCase().includes(q)),
  )
})

// Fulfillment centers — for the add form and the center filter.
const centersGql = gql`query CommerceInventoryCenters($companyId: UUID!) { ecom { fulfillmentCenters(companyId: $companyId) { id name } } }`
const { data: centerData } = useAsyncQuery<{ ecom: { fulfillmentCenters: { id: string; name: string }[] } }>(
  'commerce-inventory-centers', centersGql, { companyId },
)
const centers = computed(() => centerData.value?.ecom?.fulfillmentCenters ?? [])
const centerOptions = computed<SelectOption[]>(() => centers.value.map(c => ({ value: c.id, label: c.name })))
const centerFilterOptions = computed<SelectOption[]>(() => [{ value: '', label: 'All centers' }, ...centerOptions.value])

const columns: GlassTableColumn[] = [
  { key: 'product', label: 'Product', width: 'minmax(180px, 1.5fr)' },
  { key: 'sku', label: 'SKU', width: 'minmax(140px, 1fr)' },
  { key: 'center', label: 'Fulfillment center', width: '1fr', muted: true },
  { key: 'quantity', label: 'On hand', width: '100px', align: 'right' },
  { key: 'available', label: 'Available', width: '100px', align: 'right' },
  { key: 'inCart', label: 'In cart', width: '90px', align: 'right' },
  { key: 'pending', label: 'Pending', width: '90px', align: 'right' },
]

const rowActions: OverflowMenuItem[] = [
  { id: 'adjust', label: 'Adjust quantity', icon: 'edit' },
  { id: 'ship', label: 'Ship pending', icon: 'package' },
]

// --- add inventory row ---
const showAdd = ref(false)
const addForm = reactive({ productId: '', fulfillmentCenterId: '', sku: '', quantity: 0 })
const adding = ref(false)
const addError = ref('')

// SKU defaults to the chosen product's manufacturer SKU (still editable for a per-warehouse code).
watch(() => addForm.productId, (pid) => {
  addForm.sku = products.value.find(p => p.id === pid)?.manufacturerSku ?? ''
})

function openAdd() {
  addForm.productId = ''
  addForm.fulfillmentCenterId = ''
  addForm.sku = ''
  addForm.quantity = 0
  addError.value = ''
  showAdd.value = true
}

const addGql = gql`mutation AddInventory($input: InventoryInput!) { ecom { fulfillment { addInventory(input: $input) { id } } } }`

async function handleAdd() {
  if (!addForm.productId) { addError.value = 'Product is required.'; return }
  if (!addForm.fulfillmentCenterId) { addError.value = 'Fulfillment center is required.'; return }
  if (!addForm.sku.trim()) { addError.value = 'SKU is required.'; return }
  adding.value = true
  addError.value = ''
  try {
    const input: InventoryInput = {
      productId: addForm.productId,
      fulfillmentCenterId: addForm.fulfillmentCenterId,
      sku: addForm.sku.trim(),
      quantity: Number(addForm.quantity),
    }
    await mutation(addGql, { input })
    showAdd.value = false
    await refresh()
  } catch (e: unknown) {
    addError.value = e instanceof Error ? e.message : 'Failed to add inventory'
  } finally {
    adding.value = false
  }
}

// --- adjust / ship (shared modal) ---
const showOp = ref(false)
const opMode = ref<'adjust' | 'ship'>('adjust')
const opTarget = ref<InventoryRow | null>(null)
const opQuantity = ref(0)
const opSaving = ref(false)
const opError = ref('')

function onRowAction(payload: { action: string; row: InventoryRow }) {
  opTarget.value = payload.row
  opError.value = ''
  if (payload.action === 'adjust') {
    opMode.value = 'adjust'
    opQuantity.value = payload.row.quantity
  } else {
    opMode.value = 'ship'
    opQuantity.value = Math.min(1, payload.row.pending) || payload.row.pending
  }
  showOp.value = true
}

const adjustGql = gql`mutation AdjustInventory($id: UUID!, $quantity: Int!) { ecom { fulfillment { inventory(id: $id) { adjust(quantity: $quantity) { id } } } } }`
const shipGql = gql`mutation ShipInventory($id: UUID!, $quantity: Int!) { ecom { fulfillment { inventory(id: $id) { ship(quantity: $quantity) { id } } } } }`

async function handleOp() {
  if (!opTarget.value) return
  opSaving.value = true
  opError.value = ''
  try {
    const vars = { id: opTarget.value.id, quantity: Number(opQuantity.value) }
    await mutation(opMode.value === 'adjust' ? adjustGql : shipGql, vars)
    showOp.value = false
    await refresh()
  } catch (e: unknown) {
    opError.value = e instanceof Error ? e.message : 'Operation failed'
  } finally {
    opSaving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Inventory')"
        title="Inventory"
        :subtitle="selectedId ? `${rows.length} stock rows` : 'Select a company'">
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
            :disabled="!products.length"
            @click="openAdd">Add Stock</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load inventory — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!products.length" class="state">This company has no products — create one first.</div>
    <template v-else>
      <div class="filters">
        <SearchInput v-model="search" placeholder="Filter by product or SKU…" />
        <div class="pick"><Select v-model="centerFilter" :options="centerFilterOptions" size="sm" /></div>
      </div>
      <GlassTable
        :columns="columns"
        :rows="rows"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No stock yet. Use Add Stock to put a product's units at a fulfillment center."
        :row-actions="rowActions"
        @row-action="onRowAction">
        <template #col-product="{ row }">{{ row.productName }}</template>
        <template #col-sku="{ row }"><code class="mono">{{ row.sku }}</code></template>
        <template #col-center="{ row }">{{ row.fulfillmentCenter?.name }}</template>
        <template #col-quantity="{ row }">{{ row.quantity }}</template>
        <template #col-available="{ row }"><strong>{{ row.available }}</strong></template>
        <template #col-inCart="{ row }">{{ row.inCart }}</template>
        <template #col-pending="{ row }">{{ row.pending }}</template>
      </GlassTable>
    </template>

    <Modal
      v-if="showAdd"
      title="Add Stock"
      icon="archive"
      :accent="accent"
      @close="showAdd = false">
      <div class="form-stack">
        <Select
          v-model="addForm.productId"
          label="Product"
          placeholder="Select…"
          :options="productOptions" />
        <Select
          v-model="addForm.fulfillmentCenterId"
          label="Fulfillment center"
          placeholder="Select…"
          :options="centerOptions" />
        <div class="form-row">
          <TextInput
            v-model="addForm.sku"
            label="SKU"
            placeholder="SKU-001"
            mono />
          <NumberInput v-model="addForm.quantity" label="On-hand quantity" :min="0" />
        </div>
        <p v-if="!centerOptions.length" class="hint">This company has no fulfillment centers — create one first.</p>
        <p v-if="addError" class="form-error">{{ addError }}</p>
      </div>
      <template #footer>
        <Button @click="showAdd = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="adding"
          @click="handleAdd">{{ adding ? 'Adding…' : 'Add stock' }}</Button>
      </template>
    </Modal>

    <Modal
      v-if="showOp"
      :title="opMode === 'adjust' ? 'Adjust Quantity' : 'Ship Pending Units'"
      icon="archive"
      :accent="accent"
      @close="showOp = false">
      <div class="form-stack">
        <p v-if="opMode === 'adjust'" class="hint">
          Set the on-hand quantity for <strong>{{ opTarget?.sku }}</strong>. Cannot drop below pending ({{ opTarget?.pending }}).
        </p>
        <p v-else class="hint">
          Ships pending units for <strong>{{ opTarget?.sku }}</strong> — both pending and on-hand decrement.
        </p>
        <NumberInput
          v-model="opQuantity"
          :label="opMode === 'adjust' ? 'On-hand quantity' : 'Units to ship'"
          :min="0"
          :max="opMode === 'ship' ? opTarget?.pending : undefined" />
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showOp = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="opSaving"
          @click="handleOp">
          {{ opSaving ? 'Working…' : opMode === 'adjust' ? 'Adjust' : 'Ship' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.filters { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.filters > :first-child { flex: 1; max-width: 360px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
