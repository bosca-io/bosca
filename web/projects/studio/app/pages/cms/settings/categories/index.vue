<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const categoriesGql = gql`query GetCategories { content { categories { all { id name } } } }`
const addGql = gql`mutation AddCategory($category: CategoryInput!) { content { category { add(category: $category) { id name } } } }`
const editGql = gql`mutation EditCategory($id: UUID!, $category: CategoryInput!) { content { category { edit(id: $id, category: $category) { id name } } } }`
const deleteGql = gql`mutation DeleteCategory($id: UUID!) { content { category { delete(id: $id) } } }`

interface Category { id: string; name: string }

const { data, status, refresh } = useAsyncQuery<{ content: { categories: { all: Category[] } } }>('cms-categories', categoriesGql, {})
const categories = computed(() => data.value?.content?.categories?.all ?? [])
const showModal = ref(false)
const editingId = ref<string | null>(null)
const newName = ref('')
const saving = ref(false)
const deleteTarget = ref<Category | null>(null)
const deleteLoading = ref(false)

function openCreate() { editingId.value = null; newName.value = ''; showModal.value = true }
function openEdit(cat: Category) { editingId.value = cat.id; newName.value = cat.name; showModal.value = true }

async function handleSave() {
  if (!newName.value.trim()) return
  saving.value = true
  try {
    if (editingId.value) {
      await gqlMutation(editGql, { id: editingId.value, category: { name: newName.value } })
      toast.success('Updated')
    } else {
      await gqlMutation(addGql, { category: { name: newName.value } })
      toast.success('Created')
    }
    showModal.value = false; newName.value = ''; refresh()
  } catch { toast.error('Failed to save') }
  finally { saving.value = false }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); toast.success('Deleted'); deleteTarget.value = null; refresh() }
  catch { toast.error('Failed to delete') }
  finally { deleteLoading.value = false }
}

const columns: GlassTableColumn[] = [{ key: 'name', label: 'Name', width: '1fr' }]
</script>
<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Categories')"
        title="Categories"
        :subtitle="`${categories.length} categories`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openCreate">New Category</Button></template>
      </PageHeader>
    </template>
    <SectionCard title="Categories">
      <GlassTable
        :columns="columns"
        :rows="categories"
        :loading="status === 'pending' && categories.length === 0"
        empty-text="No categories."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: Category) => openEdit(row)"
        @row-action="({ action, row }: { action: string; row: Category }) => action === 'edit' ? openEdit(row) : deleteTarget = row"
      >
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
      </GlassTable>
    </SectionCard>
    <Modal
      v-if="showModal"
      :title="editingId ? 'Edit Category' : 'New Category'"
      icon="tag"
      :accent="accent"
      @close="showModal = false">
      <TextInput v-model="newName" label="Name" autofocus />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || saving"
          @click="handleSave">{{ editingId ? 'Update' : 'Create' }}</Button>
      </template>
    </Modal>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>
