<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import type { ProductInput, ProductType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const productId = computed(() => route.params.id as string)

const detailGql = gql`
  query CommerceProduct($id: UUID!) {
    ecom {
      product(id: $id) {
        id
        manufacturerSku
        type
        weight
        width
        height
        length
        company { id lengthUnit weightUnit }
        manufacturer { id name }
        configuration {
          __typename
          ... on ClothingProductConfiguration { sizes }
          ... on SubscriptionProductConfiguration { planGroupId }
        }
        metadata { id name version }
        metadataVersion
        created
        modified
      }
    }
  }
`

const { data, status, refresh, error: loadError } = useAsyncQuery<{
  ecom: {
    product: {
      id: string
      manufacturerSku: string
      type: string
      weight: number
      width: number
      height: number
      length: number
      company: { id: string; lengthUnit: string; weightUnit: string }
      manufacturer: { id: string; name: string }
      configuration: { __typename: string; sizes?: string[]; planGroupId?: string }
      metadata: { id: string; name: string; version: number }
      metadataVersion: number
      created: string
      modified: string
    } | null
  }
}>('commerce-product', detailGql, { id: productId })

const product = computed(() => data.value?.ecom?.product ?? null)
const companyId = computed(() => product.value?.company?.id ?? undefined)

// Unit suffixes from the product's own company — dimensions/weight are stored in those units.
const lenAbbr = computed(() => lengthUnitAbbr(product.value?.company?.lengthUnit))
const wtAbbr = computed(() => weightUnitAbbr(product.value?.company?.weightUnit))

const manufacturersGql = gql`
  query CommerceProductEditMfgs($companyId: UUID!) {
    ecom { manufacturers(companyId: $companyId, offset: 0, limit: 200) { id name } }
  }
`
const { data: mfgData } = useAsyncQuery<{ ecom: { manufacturers: { id: string; name: string }[] } }>(
  'commerce-product-edit-mfgs', manufacturersGql, { companyId },
)
const manufacturerOptions = computed<SelectOption[]>(() =>
  (mfgData.value?.ecom?.manufacturers ?? []).map(m => ({ value: m.id, label: m.name })),
)

const form = reactive({ manufacturerId: '', manufacturerSku: '', type: 'PHYSICAL', weight: 1, width: 0, height: 0, length: 0 })
const configDraft = reactive(emptyProductConfigDraft())
const saving = ref(false)
const error = ref('')
const saved = ref(false)

watch(product, (p) => {
  if (!p) return
  form.manufacturerId = p.manufacturer.id
  form.manufacturerSku = p.manufacturerSku
  form.type = p.type
  form.weight = p.weight
  form.width = p.width
  form.height = p.height
  form.length = p.length
  Object.assign(configDraft, parseProductConfiguration(p.configuration))
}, { immediate: true })

const editGql = gql`mutation EditProduct($id: UUID!, $input: ProductInput!) { ecom { products { product(id: $id) { edit(input: $input) { id } } } } }`

async function handleSave() {
  if (!product.value) return
  if (!form.manufacturerId || !form.manufacturerSku.trim()) { error.value = 'Manufacturer and SKU are required.'; return }
  saving.value = true
  error.value = ''
  saved.value = false
  try {
    const input: ProductInput = {
      companyId: product.value.company.id,
      manufacturerId: form.manufacturerId,
      manufacturerSku: form.manufacturerSku.trim(),
      type: form.type as ProductType,
      weight: Number(form.weight),
      width: Number(form.width),
      height: Number(form.height),
      length: Number(form.length),
      // title is required by ProductInput but edit ignores it (content-managed) — echo the current name.
      title: product.value.metadata.name,
      configuration: buildProductConfiguration(configDraft),
    }
    await mutation(editGql, { id: product.value.id, input })
    saved.value = true
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save product'
  } finally {
    saving.value = false
  }
}

const showDelete = ref(false)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteProduct($id: UUID!) { ecom { products { product(id: $id) { delete } } } }`

async function handleDelete() {
  if (!product.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: product.value.id })
    showDelete.value = false
    router.push('/commerce/products')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete product'
    deleting.value = false
  }
}

function fmt(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="product"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Products', { label: product.metadata.name })"
        :title="product.metadata.name"
        :subtitle="`${product.type} · SKU ${product.manufacturerSku}`">
        <template #actions>
          <Button icon="trash" size="sm" @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load product — {{ loadError.message }}</div>
    <div v-if="status === 'pending' && !product" class="state">Loading…</div>
    <div v-else-if="!product" class="state">Product not found.</div>
    <template v-else>
      <SectionCard title="Commerce" padded>
        <div class="form-stack">
          <div class="form-row">
            <Select v-model="form.manufacturerId" label="Manufacturer" :options="manufacturerOptions" />
            <Select v-model="form.type" label="Type" :options="PRODUCT_TYPE_OPTIONS" />
          </div>
          <div class="form-row">
            <TextInput v-model="form.manufacturerSku" label="Manufacturer SKU" mono />
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
            mono />
          <div class="actions">
            <span v-if="saved" class="saved">Saved.</span>
            <span v-if="error" class="form-error">{{ error }}</span>
            <Button
              primary
              size="sm"
              :accent="accent"
              :disabled="saving"
              @click="handleSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Content" padded>
        <p class="hint">
          Name, description, and rich merchandising content live on the backing content document. It is
          edited and published through the content workflow; this product pins the published version
          <strong>v{{ product.metadataVersion }}</strong> (the pin advances automatically on publish).
        </p>
        <div class="content-row">
          <span class="content-name">{{ product.metadata.name }}</span>
          <Button size="sm" icon="external-link" @click="router.push(`/cms/editor/${product.metadata.id}`)">Edit content</Button>
        </div>
      </SectionCard>

      <SectionCard title="Details" padded>
        <dl class="kv">
          <dt>Created</dt><dd>{{ fmt(product.created) }}</dd>
          <dt>Modified</dt><dd>{{ fmt(product.modified) }}</dd>
        </dl>
      </SectionCard>
    </template>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Product"
      subtitle="Soft-deletes the product and unpublishes its content document."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ product?.metadata.name }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-row.dims { grid-template-columns: 1fr 1fr 1fr; }
.actions { display: flex; align-items: center; gap: 12px; justify-content: flex-end; }
.saved { color: var(--ok, #4ade80); font-size: 12px; }
.form-error { color: var(--err); font-size: 12px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 12px; }
.content-row { display: flex; align-items: center; gap: 12px; }
.content-name { font-weight: 500; flex: 1; }
.kv { display: grid; grid-template-columns: 160px 1fr; gap: 8px 16px; margin: 0; }
.kv dt { color: var(--fg-3); font-size: 12.5px; }
.kv dd { margin: 0; font-size: 13px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
