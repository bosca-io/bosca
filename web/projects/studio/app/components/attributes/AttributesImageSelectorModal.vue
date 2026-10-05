<script lang="ts" setup>
import gql from 'graphql-tag'

const props = defineProps<{
   
  onSelected: (_metadataId: string) => void
}>()

const emit = defineEmits<{ close: [] }>()

const { query: gqlQuery } = useGraphQL()
const searchTerm = ref('')
const results = ref<Array<{ id: string; name: string; slug: string; contentType: string }>>([])
const loading = ref(false)
const searchInputRef = ref<HTMLInputElement>()

const searchGql = gql`
  query SearchImages($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
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
  nextTick(() => searchInputRef.value?.focus())
})

interface ImageSearchDocument {
  metadata: { id: string; name: string; slug: string; content: { type: string } | null } | null
}

interface ImageSearchResult {
  search: { search: { documents: ImageSearchDocument[]; estimatedHits: number } }
}

async function doSearch() {
  loading.value = true
  try {
    const result = await gqlQuery<ImageSearchResult>(searchGql, {
      query: searchTerm.value,
      filter: '_type = "metadata" AND contentType STARTS WITH "image/"',
      limit: 24,
      offset: 0,
    })
    results.value = (result?.search?.search?.documents ?? [])
      .filter((d): d is ImageSearchDocument & { metadata: NonNullable<ImageSearchDocument['metadata']> } => d.metadata !== null && d.metadata !== undefined)
      .map(d => ({
        id: d.metadata.id,
        name: d.metadata.name,
        slug: d.metadata.slug,
        contentType: d.metadata.content?.type || '',
      }))
  } catch (e) {
    console.error('Image search failed', e)
    results.value = []
  } finally {
    loading.value = false
  }
}

function onSelect(item: { id: string }) {
  props.onSelected(item.id)
  emit('close')
}
</script>

<template>
  <Modal
    title="Select Image"
    subtitle="Choose an existing image from the content library"
    icon="image"
    width="720px"
    @close="emit('close')"
  >
    <div class="selector-body">
      <div class="search-bar">
        <TextInput
          ref="searchInputRef"
          v-model="searchTerm"
          placeholder="Search images…"
          icon="search"
        />
      </div>

      <div v-if="loading" class="selector-loading">Searching…</div>

      <div v-else-if="results.length === 0" class="selector-empty">
        {{ searchTerm ? 'No images found' : 'No images available' }}
      </div>

      <div v-else class="image-grid">
        <button
          v-for="item in results"
          :key="item.id"
          class="image-card"
          @click="onSelect(item)"
        >
          <img
            :src="`/content/image/${item.slug || item.id}`"
            :alt="item.name"
            class="image-thumb"
            loading="lazy"
          >
          <span class="image-label">{{ item.name }}</span>
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

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
  max-height: 400px;
  overflow-y: auto;
  padding: 2px;
}

.image-card {
  display: flex;
  flex-direction: column;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.12s, transform 0.12s;
}
.image-card:hover {
  border-color: var(--brand-2);
  transform: scale(1.03);
}

.image-thumb {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  background: var(--bg-3);
}

.image-label {
  padding: 6px 8px;
  font-size: 11px;
  color: var(--fg-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
