<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { ProductInput, ProductType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()
const { companies, selected, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// Unit suffixes for the selected company — dimensions/weight are stored in the company's units.
const lenAbbr = computed(() => lengthUnitAbbr(selected.value?.lengthUnit))
const wtAbbr = computed(() => weightUnitAbbr(selected.value?.weightUnit))

interface ProductRow {
  id: string
  manufacturerSku: string
  type: string
  manufacturer: { id: string; name: string }
  metadata: { id: string; name: string }
  metadataVersion: number
  modified: string
}

const listGql = gql`
  query CommerceProducts($companyId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      products(companyId: $companyId, offset: $offset, limit: $limit) {
        id
        manufacturerSku
        type
        manufacturer { id name }
        metadata { id name }
        metadataVersion
        modified
      }
    }
  }
`

const { rows: products, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<ProductRow>(
  'commerce-products', listGql, { companyId }, d => (d as { ecom?: { products?: ProductRow[] } })?.ecom?.products,
)

// Manufacturers for the create form's select (a product must reference a manufacturer).
const manufacturersGql = gql`
  query CommerceProductManufacturers($companyId: UUID!) {
    ecom { manufacturers(companyId: $companyId, offset: 0, limit: 200) { id name } }
  }
`
const { data: mfgData } = useAsyncQuery<{ ecom: { manufacturers: { id: string; name: string }[] } }>(
  'commerce-product-manufacturers', manufacturersGql, { companyId },
)
const manufacturerOptions = computed<SelectOption[]>(() =>
  (mfgData.value?.ecom?.manufacturers ?? []).map(m => ({ value: m.id, label: m.name })),
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Product', width: 'minmax(220px, 2fr)' },
  { key: 'sku', label: 'SKU', width: '1fr', muted: true },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'manufacturer', label: 'Manufacturer', width: '1fr', muted: true },
  { key: 'version', label: 'Version', width: '90px', muted: true },
]

const showCreate = ref(false)
const form = reactive({ manufacturerId: '', manufacturerSku: '', type: 'PHYSICAL', weight: 1, width: 0, height: 0, length: 0, title: '', description: '' })
const configDraft = reactive(emptyProductConfigDraft())
const saving = ref(false)
const error = ref('')

function openCreate() {
  form.manufacturerId = ''
  form.manufacturerSku = ''
  form.type = 'PHYSICAL'
  form.weight = 1
  form.width = 0
  form.height = 0
  form.length = 0
  form.title = ''
  form.description = ''
  Object.assign(configDraft, emptyProductConfigDraft())
  error.value = ''
  showCreate.value = true
}

const addGql = gql`mutation AddProduct($input: ProductInput!) { ecom { products { add(input: $input) { id } } } }`

async function handleCreate() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.manufacturerId) { error.value = 'Manufacturer is required.'; return }
  if (!form.manufacturerSku.trim()) { error.value = 'SKU is required.'; return }
  if (!form.title.trim()) { error.value = 'Title is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: ProductInput = {
      companyId: selectedId.value,
      manufacturerId: form.manufacturerId,
      manufacturerSku: form.manufacturerSku.trim(),
      type: form.type as ProductType,
      weight: Number(form.weight),
      width: Number(form.width),
      height: Number(form.height),
      length: Number(form.length),
      title: form.title.trim(),
      description: form.description.trim() || null,
      configuration: buildProductConfiguration(configDraft),
    }
    const result = await mutation<{ ecom: { products: { add: { id: string } } } }>(addGql, { input })
    showCreate.value = false
    await refresh()
    const id = result?.ecom?.products?.add?.id
    if (id) router.push(`/commerce/products/${id}`)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create product'
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Products')"
        title="Products"
        :subtitle="selectedId ? `${products.length} in the selected company` : 'Select a company'">
        <template #actions>
          <div class="company-pick">
            <Select
              v-model="selectedId"
              placeholder="Company…"
              :options="companyOptions"
              size="sm" />
          </div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedId"
            @click="openCreate">New Product</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load products — {{ loadError.message }}</div>
    <div v-if="!selectedId" class="state">Select a company to view its products.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="products"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No products in this company yet."
      @row-click="(row) => router.push(`/commerce/products/${row.id}`)">
      <template #col-name="{ row }">{{ row.metadata?.name }}</template>
      <template #col-sku="{ row }"><code class="mono">{{ row.manufacturerSku }}</code></template>
      <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
      <template #col-manufacturer="{ row }">{{ row.manufacturer?.name }}</template>
      <template #col-version="{ row }">v{{ row.metadataVersion }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="products.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Product"
      icon="package"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <p class="hint">Creates the product and its backing content document (pinned at v1). Edit rich content afterwards from the product page.</p>
        <TextInput v-model="form.title" label="Title" placeholder="Product name" />
        <Textarea
          v-model="form.description"
          label="Description"
          :rows="2"
          placeholder="Short description (optional)" />
        <div class="form-row">
          <Select
            v-model="form.manufacturerId"
            label="Manufacturer"
            placeholder="Select…"
            :options="manufacturerOptions" />
          <Select v-model="form.type" label="Type" :options="PRODUCT_TYPE_OPTIONS" />
        </div>
        <div class="form-row">
          <TextInput
            v-model="form.manufacturerSku"
            label="Manufacturer SKU"
            placeholder="SKU-001"
            mono />
          <NumberInput
            v-model="form.weight"
            :label="`Weight (${wtAbbr})`"
            :min="0"
            :step="0.1" />
        </div>
        <div class="form-row dims">
          <NumberInput
            v-model="form.length"
            :label="`Length (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.width"
            :label="`Width (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.height"
            :label="`Height (${lenAbbr})`"
            :min="0"
            :step="0.1" />
        </div>
        <Select v-model="configDraft.type" label="Configuration" :options="PRODUCT_CONFIG_TYPE_OPTIONS" />
        <TextInput
          v-if="configDraft.type === 'clothing'"
          v-model="configDraft.sizes"
          label="Sizes"
          placeholder="S, M, L" />
        <TextInput
          v-if="configDraft.type === 'subscription'"
          v-model="configDraft.planGroupId"
          label="Plan group id"
          placeholder="UUID"
          mono />
        <p v-if="!manufacturerOptions.length" class="hint">This company has no manufacturers yet — create one first.</p>
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
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-row.dims { grid-template-columns: 1fr 1fr 1fr; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.company-pick { width: 200px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
