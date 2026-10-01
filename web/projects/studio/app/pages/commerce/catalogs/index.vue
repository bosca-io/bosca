<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { CatalogInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))

interface CatalogRow { id: string; key: string; name: string; currency: string; created: string }

const listGql = gql`
  query CommerceCatalogs($companyId: UUID!) {
    ecom { catalogs(companyId: $companyId) { id key name currency created } }
  }
`

// `undefined` skips the fetch until a company is selected; the composable resolves to the first.
const companyId = computed(() => selectedId.value ?? undefined)
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { catalogs: CatalogRow[] } }>(
  'commerce-catalogs', listGql, { companyId },
)
const catalogs = computed(() => data.value?.ecom?.catalogs ?? [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'currency', label: 'Currency', width: '110px' },
  { key: 'created', label: 'Created', width: '150px', muted: true },
]

function fmt(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const showForm = ref(false)
const editId = ref<string | null>(null)
const form = reactive({ key: '', name: '', currency: 'USD' })
const saving = ref(false)
const error = ref('')

function openCreate() {
  editId.value = null
  form.key = ''
  form.name = ''
  form.currency = 'USD'
  error.value = ''
  showForm.value = true
}

function openEdit(row: CatalogRow) {
  editId.value = row.id
  form.key = row.key
  form.name = row.name
  form.currency = row.currency
  error.value = ''
  showForm.value = true
}

const addGql = gql`mutation AddCatalog($input: CatalogInput!) { ecom { catalogs { add(input: $input) { id } } } }`
const editGql = gql`mutation EditCatalog($id: UUID!, $input: CatalogInput!) { ecom { catalogs { catalog(id: $id) { edit(input: $input) { id } } } } }`

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.key.trim() || !form.name.trim()) { error.value = 'Key and name are required.'; return }
  saving.value = true
  error.value = ''
  try {
    // Currency is honored on create (a catalog's currency is fixed — its prices are denominated in it).
    const input: CatalogInput = {
      companyId: selectedId.value, key: form.key.trim(), name: form.name.trim(),
      currency: form.currency.trim().toUpperCase() || 'USD',
    }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save catalog'
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Catalogs')"
        title="Catalogs"
        :subtitle="selectedId ? `${catalogs.length} in the selected company` : 'Select a company'">
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
            @click="openCreate">New Catalog</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load catalogs — {{ loadError.message }}</div>
    <div v-if="!selectedId" class="state">Select a company to view its catalogs.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="catalogs"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No catalogs in this company yet."
      @row-click="openEdit">
      <template #col-key="{ value }"><code class="mono">{{ value }}</code></template>
      <template #col-created="{ row }">{{ fmt(row.created) }}</template>
    </GlassTable>

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Catalog' : 'New Catalog'"
      icon="boxes"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Default" />
        <TextInput
          v-model="form.key"
          label="Key"
          placeholder="default"
          mono />
        <p class="hint">The key is unique per company.</p>
        <TextInput
          v-model="form.currency"
          label="Currency (ISO-4217)"
          placeholder="USD"
          mono
          :disabled="!!editId" />
        <p class="hint">{{ editId ? "A catalog's currency is fixed — its prices are denominated in it." : 'The currency this price book is denominated in. Stores selling it inherit it.' }}</p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : editId ? 'Save' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.company-pick { width: 200px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
