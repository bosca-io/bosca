<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const { accent } = useCurrentSubsystem()
const toast = useToast()

const searchQuery = ref('')
const filter = '_type = "collection" AND labels = "content-library"'

const listGql = gql`
  query GetContentLibraries($query: String!, $filter: [String!]!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: $filter
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          collection {
            id
            name
            slug
            modified
            itemsCount
            labels
          }
        }
      }
    }
  }
`

const createGql = gql`
  mutation CreateContentLibrary($collection: CollectionInput!) {
    content { collection { add(collection: $collection) { id } } }
  }
`

const deleteGql = gql`
  mutation DeleteLibrary($id: UUID!) {
    content { collection { delete(id: $id) } }
  }
`
const slugAvailableGql = gql`
  query SlugAvailable($slug: String!) {
    content { slugAvailable(slug: $slug) }
  }
`

interface LibraryCollection {
  id: string
  name: string
  slug: string | null
  modified: string
  itemsCount: number
  labels: string[]
}

const { data, refresh } = useAsyncQuery<{
  search: { search: { documents: Array<{ collection: LibraryCollection | null }> } }
}>(
  'content-libraries',
  listGql,
  { query: searchQuery, filter: [filter], limit: 50, offset: 0 },
)

const libraries = computed(() =>
  (data.value?.search?.search?.documents ?? [])
    .map((d) => d.collection)
    .filter((c): c is LibraryCollection => c != null),
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'slug', label: 'Slug', width: '160px', muted: true },
  { key: 'itemsCount', label: 'Items', width: '80px', align: 'right' },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
]

const showCreate = ref(false)
const newName = ref('')
const newSlug = ref('')
const creating = ref(false)
// Slug availability is checked asynchronously as the user types. State machine:
//   'idle'     — slug field is empty, no check needed
//   'checking' — debounce timer running or query in flight
//   'ok'       — server says this slug is available
//   'taken'    — server says this slug already exists (blocks Create)
const slugStatus = ref<'idle' | 'checking' | 'ok' | 'taken'>('idle')
let slugCheckTimer: ReturnType<typeof setTimeout> | null = null
let slugCheckSeq = 0

function onSlugInput(value: string) {
  newSlug.value = value
  const trimmed = value.trim()
  if (slugCheckTimer) clearTimeout(slugCheckTimer)
  // Bump the sequence on every input — including the empty-input branch —
  // so any in-flight check for a prior non-empty value gets ignored if its
  // callback lands after the user cleared the field.
  const seq = ++slugCheckSeq
  if (!trimmed) {
    slugStatus.value = 'idle'
    return
  }
  slugStatus.value = 'checking'
  slugCheckTimer = setTimeout(async () => {
    try {
      const r = await gqlQuery<{ content: { slugAvailable: boolean } }>(slugAvailableGql, { slug: trimmed })
      if (seq !== slugCheckSeq) return
      slugStatus.value = r?.content?.slugAvailable ? 'ok' : 'taken'
    } catch (e: unknown) {
      console.warn('[library-builder] slug check failed', e)
      if (seq !== slugCheckSeq) return
      slugStatus.value = 'idle'
    }
  }, 300)
}

onScopeDispose(() => {
  if (slugCheckTimer) clearTimeout(slugCheckTimer)
})

const slugHelpText = computed(() => {
  switch (slugStatus.value) {
    case 'checking': return 'Checking availability…'
    case 'ok': return 'Slug is available'
    case 'taken': return 'Slug is already taken'
    default: return ''
  }
})

const canCreate = computed(() => {
  if (!newName.value.trim()) return false
  if (slugStatus.value === 'checking' || slugStatus.value === 'taken') return false
  return true
})

function resetCreateForm() {
  newName.value = ''
  newSlug.value = ''
  slugStatus.value = 'idle'
  if (slugCheckTimer) clearTimeout(slugCheckTimer)
}

async function createLibrary() {
  if (!canCreate.value) return
  creating.value = true
  try {
    const input: Record<string, unknown> = {
      name: newName.value.trim(),
      collectionType: 'STANDARD',
      labels: ['content-library'],
    }
    if (newSlug.value.trim()) input.slug = newSlug.value.trim()
    const result = await gqlMutation<{ content: { collection: { add: { id: string } } } }>(createGql, { collection: input })
    const id = result.content.collection.add.id
    showCreate.value = false
    resetCreateForm()
    toast.success('Library created')
    await router.push(`/cms/library-builder/${id}`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create library')
  } finally {
    creating.value = false
  }
}

const deleteTarget = ref<LibraryCollection | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Library deleted')
    deleteTarget.value = null
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete library')
    deleteTarget.value = null
  } finally {
    deleteLoading.value = false
  }
}

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'Open Builder', icon: 'wand' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: LibraryCollection) {
  if (action === 'open') router.push(`/cms/library-builder/${row.id}`)
  if (action === 'copy') {
    globalThis.navigator?.clipboard?.writeText(row.id)
    toast.success('ID copied')
  }
  if (action === 'delete') deleteTarget.value = row
}

function formatDate(d: string): string {
  try {
    return new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch { return '—' }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Library Builder')"
        title="Content Libraries"
        :subtitle="`${libraries.length} libraries`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true"
          >New Library</Button>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <SearchInput v-model="searchQuery" placeholder="Search libraries…" />
    </div>

    <SectionCard title="Libraries" glass>
      <GlassTable
        :columns="columns"
        :rows="libraries"
        :accent="accent"
        :row-actions="getRowActions"
        empty-text="No content libraries found."
        @row-click="(row: LibraryCollection) => router.push(`/cms/library-builder/${row.id}`)"
        @row-action="({ action, row }: { action: string; row: LibraryCollection }) => onRowAction(action, row)"
      >
        <template #col-slug="{ row }">
          <code v-if="row.slug" class="slug-cell">{{ row.slug }}</code>
          <span v-else class="no-slug">—</span>
        </template>
        <template #col-modified="{ row }">
          {{ formatDate(row.modified) }}
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Content Library"
      icon="boxes"
      :accent="accent"
      @close="() => { showCreate = false; resetCreateForm() }"
    >
      <div class="create-form">
        <TextInput
          v-model="newName"
          label="Library Name"
          placeholder="e.g. Main Library"
          autofocus
        />
        <div class="slug-field">
          <TextInput
            :model-value="newSlug"
            label="Slug (optional)"
            placeholder="e.g. content-library-main"
            mono
            @update:model-value="onSlugInput"
          />
          <div
            v-if="slugHelpText"
            class="slug-status"
            :class="{
              'slug-status--ok': slugStatus === 'ok',
              'slug-status--taken': slugStatus === 'taken',
            }"
          >
            {{ slugHelpText }}
          </div>
        </div>
        <div class="create-actions">
          <Button @click="() => { showCreate = false; resetCreateForm() }">Cancel</Button>
          <Button
            primary
            :accent="accent"
            :loading="creating"
            :disabled="!canCreate"
            @click="createLibrary"
          >Create</Button>
        </div>
      </div>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete Library"
      :message="`Delete '${deleteTarget.name}'? This cannot be undone.`"
      confirm-label="Delete"
      danger
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </PageShell>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slug-cell {
  font-size: 12px;
  color: var(--fg-3);
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  padding: 2px 6px;
  border-radius: var(--r-xs);
}

.no-slug {
  color: var(--fg-3);
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 360px;
}

.create-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.slug-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.slug-status {
  font-size: 11.5px;
  color: var(--fg-3);
  padding-left: 2px;
}
.slug-status--ok { color: #34d99a; }
.slug-status--taken { color: var(--err); }
</style>
