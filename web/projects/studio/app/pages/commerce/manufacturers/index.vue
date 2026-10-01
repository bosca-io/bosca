<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { ManufacturerInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))

interface ManufacturerRow { id: string; name: string; created: string }

const listGql = gql`
  query CommerceManufacturers($companyId: UUID!, $offset: Int!, $limit: Int!) {
    ecom { manufacturers(companyId: $companyId, offset: $offset, limit: $limit) { id name created } }
  }
`

const companyId = computed(() => selectedId.value ?? undefined)
const { rows: manufacturers, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<ManufacturerRow>(
  'commerce-manufacturers', listGql, { companyId }, d => (d as { ecom?: { manufacturers?: ManufacturerRow[] } })?.ecom?.manufacturers,
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Manufacturer', width: 'minmax(220px, 2fr)' },
  { key: 'created', label: 'Created', width: '150px', muted: true },
]
const rowActions: OverflowMenuItem[] = [
  { id: 'edit', label: 'Edit', icon: 'edit' },
  { id: 'delete', label: 'Delete', icon: 'trash' },
]

function fmt(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

// --- create / edit (one modal) ---
const showForm = ref(false)
const editId = ref<string | null>(null)
const formName = ref('')
const saving = ref(false)
const error = ref('')

// ManufacturerExtras is a sealed union whose only variant is empty, so there is nothing to
// configure here yet — the extension point opens up with the marketplace follow-up.
const addGql = gql`mutation AddManufacturer($input: ManufacturerInput!) { ecom { manufacturers { add(input: $input) { id } } } }`
const editGql = gql`mutation EditManufacturer($id: UUID!, $input: ManufacturerInput!) { ecom { manufacturers { manufacturer(id: $id) { edit(input: $input) { id } } } } }`
const deleteGql = gql`mutation DeleteManufacturer($id: UUID!) { ecom { manufacturers { manufacturer(id: $id) { delete } } } }`

function openCreate() { editId.value = null; formName.value = ''; error.value = ''; showForm.value = true }
function openEdit(row: ManufacturerRow) { editId.value = row.id; formName.value = row.name; error.value = ''; showForm.value = true }

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!formName.value.trim()) { error.value = 'Name is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: ManufacturerInput = { companyId: selectedId.value, name: formName.value.trim() }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save manufacturer'
  } finally {
    saving.value = false
  }
}

// --- delete ---
const showDelete = ref(false)
const deleteTarget = ref<ManufacturerRow | null>(null)
const deleting = ref(false)

function onRowAction(payload: { action: string; row: ManufacturerRow }) {
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Manufacturers')"
        title="Manufacturers"
        :subtitle="selectedId ? `${manufacturers.length} in the selected company` : 'Select a company'">
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
            @click="openCreate">New Manufacturer</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load manufacturers — {{ loadError.message }}</div>
    <div v-if="!selectedId" class="state">Select a company to view its manufacturers.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="manufacturers"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No manufacturers in this company yet."
      :row-actions="rowActions"
      @row-action="onRowAction">
      <template #col-created="{ row }">{{ fmt(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="manufacturers.length"
      :has-more="hasMore" />

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Manufacturer' : 'New Manufacturer'"
      icon="tag"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput v-model="formName" label="Name" placeholder="Acme Manufacturing" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Manufacturer"
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Remove <strong>{{ deleteTarget?.name }}</strong>? Products already referencing it keep their link.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.company-pick { width: 200px; }
</style>
