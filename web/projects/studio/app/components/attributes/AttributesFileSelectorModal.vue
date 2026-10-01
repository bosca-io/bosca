<script lang="ts" setup>
import gql from 'graphql-tag'

const props = defineProps<{
  onSelected: (_item: { id: string; name: string; contentType: string }) => void
  searchFilter?: string | null
}>()

const emit = defineEmits<{ close: [] }>()

const { query: gqlQuery } = useGraphQL()
const searchTerm = ref('')
const results = ref<Array<{ id: string; name: string; slug: string; contentType: string }>>([])
const loading = ref(false)
const searchInputRef = ref<HTMLInputElement>()

const searchGql = gql`
  query SearchFileAssets($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          metadata {
            id
            name
            slug
            content { type }
          }
        }
        estimatedHits
      }
    }
  }
`

let searchTimeout: ReturnType<typeof setTimeout> | undefined

watch(searchTerm, () => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => doSearch(), 300)
})

onMounted(() => {
  doSearch()
  nextTick(() => searchInputRef.value?.focus?.())
})

interface FileSearchDocument {
  metadata: { id: string; name: string; slug: string; content: { type: string } | null } | null
}

interface FileSearchResult {
  search: { search: { documents: FileSearchDocument[]; estimatedHits: number } }
}

async function doSearch() {
  loading.value = true
  try {
    const filter = props.searchFilter
      ? `_type = "metadata" AND ${props.searchFilter}`
      : '_type = "metadata"'
    const result = await gqlQuery<FileSearchResult>(searchGql, {
      query: searchTerm.value,
      filter,
      limit: 50,
      offset: 0,
    })
    results.value = (result?.search?.search?.documents ?? [])
      .map((d) => d.metadata)
      .filter((m): m is NonNullable<FileSearchDocument['metadata']> => m != null)
      .map((m) => ({
        id: m.id,
        name: m.name,
        slug: m.slug,
        contentType: m.content?.type || '',
      }))
  } catch (e) {
    console.error('Asset search failed', e)
    results.value = []
  } finally {
    loading.value = false
  }
}

function onSelect(item: { id: string; name: string; contentType: string }) {
  props.onSelected({ id: item.id, name: item.name, contentType: item.contentType })
  emit('close')
}

function assetIcon(contentType: string): string {
  if (contentType.startsWith('video/')) return 'video'
  if (contentType.startsWith('audio/')) return 'audio'
  if (contentType.startsWith('image/')) return 'image'
  return 'file'
}
</script>

<template>
  <Modal
    title="Select Asset"
    subtitle="Choose an existing asset from the content library"
    icon="file"
    width="720px"
    @close="emit('close')"
  >
    <div class="selector-body">
      <div class="search-bar">
        <TextInput
          ref="searchInputRef"
          v-model="searchTerm"
          placeholder="Search assets…"
          icon="search"
        />
      </div>

      <div v-if="loading" class="selector-loading">Searching…</div>

      <div v-else-if="results.length === 0" class="selector-empty">
        {{ searchTerm ? 'No assets found' : 'No assets available' }}
      </div>

      <div v-else class="asset-list">
        <button
          v-for="item in results"
          :key="item.id"
          class="asset-row"
          @click="onSelect(item)"
        >
          <Icon :name="assetIcon(item.contentType)" :size="14" color="var(--fg-3)" />
          <span class="asset-name">{{ item.name }}</span>
          <Badge v-if="item.contentType" color="var(--fg-3)">{{ item.contentType }}</Badge>
        </button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.selector-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-bar { padding: 0; }

.selector-loading,
.selector-empty {
  text-align: center;
  padding: 32px;
  color: var(--fg-3);
  font-size: 13px;
}

.asset-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 400px;
  overflow-y: auto;
  padding: 2px;
}

.asset-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.12s;
}

.asset-row:hover {
  border-color: var(--brand-2);
}

.asset-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
