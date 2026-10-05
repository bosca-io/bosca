<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { CatalogProductInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- catalog selector (within the active company) ---
const catalogsGql = gql`query CommercePricingCatalogs($companyId: UUID!) { ecom { catalogs(companyId: $companyId) { id name currency } } }`
const { data: catData } = useAsyncQuery<{ ecom: { catalogs: { id: string; name: string; currency: string }[] } }>(
  'commerce-pricing-catalogs', catalogsGql, { companyId },
)
const catalogs = computed(() => catData.value?.ecom?.catalogs ?? [])
const catalogOptions = computed<SelectOption[]>(() => catalogs.value.map(c => ({ value: c.id, label: c.name })))
const selectedCatalogId = ref<string | null>(null)
// Catalog prices are denominated in the catalog's currency (it is the price book).
const catalogCurrency = computed(() => catalogs.value.find(c => c.id === selectedCatalogId.value)?.currency)
watch(catalogs, (list) => {
  if (!list.length) { selectedCatalogId.value = null; return }
  if (!selectedCatalogId.value || !list.some(c => c.id === selectedCatalogId.value)) selectedCatalogId.value = list[0]!.id
}, { immediate: true })

// --- products in the company (for the create form's product select) ---
const productsGql = gql`query CommercePricingProducts($companyId: UUID!) { ecom { products(companyId: $companyId, offset: 0, limit: 500) { id metadata { id name } } } }`
const { data: prodData } = useAsyncQuery<{ ecom: { products: { id: string; metadata: { id: string; name: string } }[] } }>(
  'commerce-pricing-products', productsGql, { companyId },
)
const productOptions = computed<SelectOption[]>(() =>
  (prodData.value?.ecom?.products ?? []).map(p => ({ value: p.id, label: p.metadata.name })),
)

// --- the catalog-products list ---
interface CatalogProductRow {
  id: string
  type: string
  price: string
  taxable: boolean
  starts: string
  ends: string
  promotions: string[]
  metadata: { id: string; name: string }
  product: { id: string }
  extras: { __typename: string; min?: number | null; max?: number | null }
}

const listGql = gql`
  query CommerceCatalogProducts($catalogId: UUID!, $type: ProductType, $activeOnly: Boolean!, $offset: Int!, $limit: Int!) {
    ecom {
      catalogProducts(catalogId: $catalogId, type: $type, activeOnly: $activeOnly, offset: $offset, limit: $limit) {
        id
        type
        price
        taxable
        starts
        ends
        promotions
        metadata { id name }
        product { id }
        extras { __typename ... on QuantityRequirements { min max } }
      }
    }
  }
`

const typeFilter = ref('')
const activeOnly = ref(false)
const catalogId = computed(() => selectedCatalogId.value ?? undefined)
const typeArg = computed(() => typeFilter.value || null)
const { rows: entries, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<CatalogProductRow>(
  'commerce-catalog-products', listGql, { catalogId, type: typeArg, activeOnly },
  d => (d as { ecom?: { catalogProducts?: CatalogProductRow[] } })?.ecom?.catalogProducts,
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Product', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'price', label: 'Price', width: '110px', align: 'right' },
  { key: 'active', label: 'Window', width: '120px' },
  { key: 'taxable', label: 'Taxable', width: '90px' },
  { key: 'promotions', label: 'Promotions', width: '1fr', muted: true },
]

function isActive(cp: CatalogProductRow): boolean {
  const now = Date.now()
  return new Date(cp.starts).getTime() <= now && now < new Date(cp.ends).getTime()
}

const rowActions: OverflowMenuItem[] = [
  { id: 'edit', label: 'Edit', icon: 'edit' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

// --- create / edit ---
const showForm = ref(false)
const editId = ref<string | null>(null)
const editProductName = ref('')
const form = reactive({ productId: '', price: '', taxable: true, starts: '', ends: '' })
const extrasDraft = reactive(emptyCatalogExtrasDraft())
const saving = ref(false)
const error = ref('')

function openCreate() {
  editId.value = null
  editProductName.value = ''
  form.productId = ''
  form.price = ''
  form.taxable = true
  form.starts = ''
  form.ends = ''
  Object.assign(extrasDraft, emptyCatalogExtrasDraft())
  error.value = ''
  showForm.value = true
}

function openEdit(row: CatalogProductRow) {
  editId.value = row.id
  editProductName.value = row.metadata.name
  form.productId = row.product.id
  form.price = Number(row.price).toString()
  form.taxable = row.taxable
  form.starts = isoToLocalInput(row.starts)
  form.ends = isoToLocalInput(row.ends)
  Object.assign(extrasDraft, parseCatalogExtras(row.extras))
  error.value = ''
  showForm.value = true
}

const addGql = gql`mutation AddCatalogProduct($input: CatalogProductInput!) { ecom { catalogProducts { add(input: $input) { id } } } }`
const editGql = gql`mutation EditCatalogProduct($id: UUID!, $input: CatalogProductInput!) { ecom { catalogProducts { catalogProduct(id: $id) { edit(input: $input) { id } } } } }`

async function handleSave() {
  if (!selectedCatalogId.value) { error.value = 'Select a catalog first.'; return }
  if (!form.productId) { error.value = 'Product is required.'; return }
  if (!form.price.trim()) { error.value = 'Price is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: CatalogProductInput = {
      catalogId: selectedCatalogId.value,
      productId: form.productId,
      price: form.price.trim(),
      taxable: form.taxable,
      starts: localInputToIso(form.starts),
      ends: localInputToIso(form.ends),
      extras: buildCatalogExtras(extrasDraft),
    }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save catalog entry'
  } finally {
    saving.value = false
  }
}

const showDelete = ref(false)
const deleteTarget = ref<CatalogProductRow | null>(null)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteCatalogProduct($id: UUID!) { ecom { catalogProducts { catalogProduct(id: $id) { delete } } } }`

function onRowAction(payload: { action: string; row: CatalogProductRow }) {
  if (payload.action === 'edit') openEdit(payload.row)
  else if (payload.action === 'delete') { deleteTarget.value = payload.row; showDelete.value = true }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: deleteTarget.value.id })
    showDelete.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete catalog entry'
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Pricing')"
        title="Pricing"
        :subtitle="selectedCatalogId ? `${entries.length} catalog entries` : 'Select a catalog'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <div class="pick"><Select
            v-model="selectedCatalogId"
            placeholder="Catalog…"
            :options="catalogOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedCatalogId"
            @click="openCreate">New Entry</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load catalog entries — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!catalogs.length" class="state">This company has no catalogs — create one first.</div>
    <template v-else>
      <div class="filter-bar">
        <div class="pick"><Select v-model="typeFilter" :options="PRODUCT_TYPE_FILTER_OPTIONS" size="sm" /></div>
        <label class="toggle"><Switch v-model="activeOnly" :accent="accent" /> Active only</label>
      </div>

      <GlassTable
        :columns="columns"
        :rows="entries"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No catalog entries. Add a product to this catalog to price it."
        :row-actions="rowActions"
        @row-action="onRowAction"
        @row-click="openEdit">
        <template #col-name="{ row }">{{ row.metadata?.name }}</template>
        <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
        <template #col-price="{ row }">{{ formatMoney(row.price, catalogCurrency) }}</template>
        <template #col-active="{ row }">
          <Badge :color="isActive(row) ? accent : '#888'">{{ isActive(row) ? 'Active' : 'Inactive' }}</Badge>
        </template>
        <template #col-taxable="{ row }">{{ row.taxable ? 'Yes' : 'No' }}</template>
        <template #col-promotions="{ row }">
          <span v-if="row.promotions?.length" class="mono">{{ row.promotions.join(', ') }}</span>
          <span v-else>—</span>
        </template>
      </GlassTable>
      <ListPager
        v-model:offset="offset"
        :page-size="pageSize"
        :count="entries.length"
        :has-more="hasMore" />
    </template>

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Catalog Entry' : 'New Catalog Entry'"
      icon="layers"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <Select
          v-if="!editId"
          v-model="form.productId"
          label="Product"
          placeholder="Select…"
          :options="productOptions" />
        <div v-else class="readonly-field"><span class="rf-label">Product</span><span class="rf-value">{{ editProductName }}</span></div>
        <TextInput
          v-model="form.price"
          :label="`Price (${catalogCurrency || 'USD'})`"
          placeholder="19.99"
          mono />
        <label class="toggle"><Switch v-model="form.taxable" :accent="accent" /> Taxable</label>
        <div class="form-row">
          <DateInput v-model="form.starts" label="Available from" type="datetime-local" />
          <DateInput v-model="form.ends" label="Available until" type="datetime-local" />
        </div>
        <p class="hint">Leave the window blank to default to “now” through the far future.</p>
        <Select v-model="extrasDraft.type" label="Extras" :options="CATALOG_EXTRAS_TYPE_OPTIONS" />
        <div v-if="extrasDraft.type === 'quantityRequirements'" class="form-row">
          <NumberInput v-model="extrasDraft.min" label="Min quantity" :min="0" />
          <NumberInput v-model="extrasDraft.max" label="Max quantity" :min="0" />
        </div>
        <p v-if="!productOptions.length && !editId" class="hint">This company has no products yet — create one first.</p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">{{ saving ? 'Saving…' : editId ? 'Save' : 'Create' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Catalog Entry"
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Remove <strong>{{ deleteTarget?.metadata.name }}</strong> from this catalog?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.filter-bar { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; }
.toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
.readonly-field { display: flex; flex-direction: column; gap: 4px; }
.rf-label { font-size: 12px; color: var(--fg-3); }
.rf-value { font-size: 13px; font-weight: 500; }
</style>
