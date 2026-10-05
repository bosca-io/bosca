<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { StoreInput, StoreType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface StoreRow {
  id: string
  identifier: string
  name: string
  type: string
  cartExpirationSeconds: number
  catalog: { id: string; name: string }
  // Nullable: the Store.paymentProvider resolver returns null when the bound provider was deleted.
  paymentProvider: { id: string; name: string } | null
  shippingCatalogProduct: { id: string; metadata: { name: string } }
}

const listGql = gql`
  query CommerceStores($companyId: UUID!) {
    ecom {
      stores(companyId: $companyId) {
        id
        identifier
        name
        type
        cartExpirationSeconds
        catalog { id name }
        paymentProvider { id name }
        shippingCatalogProduct { id metadata { name } }
      }
    }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { stores: StoreRow[] } }>(
  'commerce-stores', listGql, { companyId },
)
const stores = computed(() => data.value?.ecom?.stores ?? [])

// --- selects for the create/edit form ---
const catalogsGql = gql`query CommerceStoreCatalogs($companyId: UUID!) { ecom { catalogs(companyId: $companyId) { id name } } }`
const { data: catData } = useAsyncQuery<{ ecom: { catalogs: { id: string; name: string }[] } }>(
  'commerce-store-catalogs', catalogsGql, { companyId },
)
const catalogOptions = computed<SelectOption[]>(() => (catData.value?.ecom?.catalogs ?? []).map(c => ({ value: c.id, label: c.name })))

const providersGql = gql`query CommerceStorePayProviders($companyId: UUID!) { ecom { paymentProviders(companyId: $companyId) { id name } } }`
const { data: provData } = useAsyncQuery<{ ecom: { paymentProviders: { id: string; name: string }[] } }>(
  'commerce-store-pay-providers', providersGql, { companyId },
)
const providerOptions = computed<SelectOption[]>(() => (provData.value?.ecom?.paymentProviders ?? []).map(p => ({ value: p.id, label: p.name })))

// Shipping line product: a SHIPPING catalog product in the *selected* catalog. Refetches when the
// form's catalog changes (a store's shipping line must live in the same catalog the store sells).
const form = reactive({
  identifier: '',
  name: '',
  type: 'VIRTUAL',
  catalogId: '',
  paymentProviderId: '',
  shippingCatalogProductId: '',
  cartExpirationSeconds: 86400,
})
const shippingCatalogId = computed(() => form.catalogId || undefined)
const shippingGql = gql`
  query CommerceStoreShippingProducts($catalogId: UUID!) {
    ecom { catalogProducts(catalogId: $catalogId, type: SHIPPING, activeOnly: false, offset: 0, limit: 200) { id metadata { name } } }
  }
`
const { data: shipData } = useAsyncQuery<{ ecom: { catalogProducts: { id: string; metadata: { name: string } }[] } }>(
  'commerce-store-shipping-products', shippingGql, { catalogId: shippingCatalogId },
)
const shippingOptions = computed<SelectOption[]>(() =>
  (shipData.value?.ecom?.catalogProducts ?? []).map(p => ({ value: p.id, label: p.metadata.name })),
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Store', width: 'minmax(180px, 2fr)' },
  { key: 'identifier', label: 'Identifier', width: '1fr', muted: true },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'catalog', label: 'Catalog', width: '1fr', muted: true },
  { key: 'cart', label: 'Cart TTL', width: '120px', align: 'right' },
]

const rowActions: OverflowMenuItem[] = [{ id: 'edit', label: 'Edit', icon: 'edit' }]

const showForm = ref(false)
const editId = ref<string | null>(null)
const saving = ref(false)
const error = ref('')

function reset() {
  form.identifier = ''
  form.name = ''
  form.type = 'VIRTUAL'
  form.catalogId = ''
  form.paymentProviderId = ''
  form.shippingCatalogProductId = ''
  form.cartExpirationSeconds = 86400
}

function openCreate() {
  editId.value = null
  reset()
  error.value = ''
  showForm.value = true
}

function openEdit(row: StoreRow) {
  editId.value = row.id
  form.identifier = row.identifier
  form.name = row.name
  form.type = row.type
  form.catalogId = row.catalog.id
  // A deleted/dangling provider resolves to null; leave the select empty so the admin must re-pick one.
  form.paymentProviderId = row.paymentProvider?.id ?? ''
  form.shippingCatalogProductId = row.shippingCatalogProduct.id
  form.cartExpirationSeconds = row.cartExpirationSeconds
  error.value = ''
  showForm.value = true
}

function onRowAction(payload: { action: string; row: StoreRow }) {
  if (payload.action === 'edit') openEdit(payload.row)
}

const addGql = gql`mutation AddStore($input: StoreInput!) { ecom { stores { add(input: $input) { id } } } }`
const editGql = gql`mutation EditStore($id: UUID!, $input: StoreInput!) { ecom { stores { store(id: $id) { edit(input: $input) { id } } } } }`

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.identifier.trim()) { error.value = 'Identifier is required.'; return }
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  if (!form.catalogId) { error.value = 'Catalog is required.'; return }
  if (!form.paymentProviderId) { error.value = 'Payment provider is required.'; return }
  if (!form.shippingCatalogProductId) { error.value = 'Shipping line product is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: StoreInput = {
      companyId: selectedId.value,
      identifier: form.identifier.trim(),
      name: form.name.trim(),
      type: form.type as StoreType,
      catalogId: form.catalogId,
      paymentProviderId: form.paymentProviderId,
      shippingCatalogProductId: form.shippingCatalogProductId,
      cartExpirationSeconds: Number(form.cartExpirationSeconds),
    }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save store'
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Stores')"
        title="Stores"
        :subtitle="selectedId ? `${stores.length} selling contexts` : 'Select a company'">
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
            @click="openCreate">New Store</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load stores — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company to view its stores.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="stores"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No stores yet. A store binds a catalog, a payment provider, and a shipping line product."
      :row-actions="rowActions"
      @row-action="onRowAction"
      @row-click="openEdit">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-identifier="{ row }"><code class="mono">{{ row.identifier }}</code></template>
      <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
      <template #col-catalog="{ row }">{{ row.catalog?.name }}</template>
      <template #col-cart="{ row }">{{ row.cartExpirationSeconds }}s</template>
    </GlassTable>

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Store' : 'New Store'"
      icon="store"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput v-model="form.name" label="Name" placeholder="e.g. Main Storefront" />
          <TextInput
            v-model="form.identifier"
            label="Identifier (slug)"
            placeholder="main"
            mono />
        </div>
        <Select v-model="form.type" label="Type" :options="STORE_TYPE_OPTIONS" />
        <Select
          v-model="form.catalogId"
          label="Catalog"
          placeholder="Select…"
          :options="catalogOptions" />
        <Select
          v-model="form.paymentProviderId"
          label="Payment provider"
          placeholder="Select…"
          :options="providerOptions" />
        <p v-if="editId && !form.paymentProviderId" class="hint">
          This store's payment provider was removed. Choose a new one to keep checkout working.
        </p>
        <Select
          v-model="form.shippingCatalogProductId"
          label="Shipping line product"
          :placeholder="form.catalogId ? 'Select a SHIPPING catalog entry…' : 'Choose a catalog first'"
          :options="shippingOptions"
          :disabled="!form.catalogId" />
        <p v-if="form.catalogId && !shippingOptions.length" class="hint">
          This catalog has no SHIPPING catalog entries. Create a product of type <strong>Shipping</strong> on the
          Products page, then add it to this catalog on the Pricing page — it will appear here.
        </p>
        <NumberInput
          v-model="form.cartExpirationSeconds"
          label="Cart lifetime (seconds)"
          :min="60"
          :step="60" />
        <p v-if="!catalogOptions.length" class="hint">This company has no catalogs — create one first.</p>
        <p v-if="!providerOptions.length" class="hint">This company has no payment providers — register one first.</p>
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
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
