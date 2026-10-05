<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import gql from 'graphql-tag'
import type { ContentPick } from '~/composables/useLibraryBuilder'

const props = withDefaults(defineProps<{
  title: string
  accent: string
  // When set, restricts the picker to a single content kind. Container blocks
  // (grid/carousel/compact-list) MUST be linked to Collections — without this
  // filter the user could pick a Metadata and create a permanently-broken
  // Grid that can't render children and can't be drilled into.
  filter?: 'collection' | 'metadata' | 'both'
}>(), {
  filter: 'both',
})

const emit = defineEmits<{
  select: [item: ContentPick]
  close: []
}>()

const { query: gqlQuery } = useGraphQL()

const searchGql = gql`
  query SearchLibraryContent($query: String!, $limit: Int!) {
    search { search(query: { query: $query, storageSystemName: "Admin Search Index", limit: $limit }) {
      documents {
        metadata { id name type languageTag }
        collection { id name type languageTag }
      }
    } }
  }
`

interface SearchDoc {
  metadata: { id: string; name: string; type: string } | null
  collection: { id: string; name: string; type: string } | null
}

const searchQuery = ref('')
const searchResults = ref<ContentPick[]>([])
const searching = ref(false)
let debounce: ReturnType<typeof setTimeout> | null = null

onScopeDispose(() => {
  if (debounce) clearTimeout(debounce)
})

function onSearchInput(q: string) {
  searchQuery.value = q
  if (debounce) clearTimeout(debounce)
  if (!q.trim()) { searchResults.value = []; return }
  debounce = setTimeout(async () => {
    searching.value = true
    try {
      const r = await gqlQuery<{ search: { search: { documents: SearchDoc[] } } }>(
        searchGql,
        { query: q, limit: 10 },
      )
      const docs = r?.search?.search?.documents ?? []
      searchResults.value = docs.flatMap((d) => {
        const items: ContentPick[] = []
        if (d.collection && props.filter !== 'metadata') {
          items.push({ ...d.collection, typename: 'Collection' })
        }
        if (d.metadata && props.filter !== 'collection') {
          items.push({ ...d.metadata, typename: 'Metadata' })
        }
        return items
      })
    } catch (e: unknown) {
      console.warn('[library-builder] content search failed', e)
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 300)
}
</script>

<template>
  <Modal
    :title="title"
    icon="search"
    :accent="accent"
    @close="emit('close')"
  >
    <div class="picker">
      <TextInput
        :model-value="searchQuery"
        label="Search collections or metadata"
        placeholder="Type to search…"
        autofocus
        @update:model-value="onSearchInput"
      />
      <div v-if="searching" class="picker-loading">Searching…</div>
      <div v-else-if="searchResults.length" class="picker-results">
        <div
          v-for="item in searchResults"
          :key="item.id"
          class="picker-item"
          @click="emit('select', item)"
        >
          <Icon :name="item.typename === 'Collection' ? 'boxes' : 'file'" :size="14" :color="accent" />
          <span class="picker-name">{{ item.name }}</span>
          <span class="picker-type">{{ item.typename }}</span>
        </div>
      </div>
      <div v-else-if="searchQuery.trim()" class="picker-empty">No results</div>
    </div>
  </Modal>
</template>

<style scoped>
.picker { display: flex; flex-direction: column; gap: 12px; min-width: 400px; }
.picker-loading { color: var(--fg-3); font-size: 13px; padding: 12px 0; }
.picker-results { display: flex; flex-direction: column; gap: 2px; max-height: 300px; overflow-y: auto; }
.picker-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-radius: var(--r-sm); cursor: pointer; transition: background 0.12s;
}
.picker-item:hover { background: var(--bg-3); }
.picker-name { flex: 1; font-size: 13px; color: var(--fg-1); }
.picker-type { font-size: 11px; color: var(--fg-3); text-transform: uppercase; }
.picker-empty { color: var(--fg-3); font-size: 13px; padding: 12px 0; }
</style>
