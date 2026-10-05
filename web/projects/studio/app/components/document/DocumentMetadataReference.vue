<script setup lang="ts">
import gql from 'graphql-tag'
import type { ContainerRenderer } from '~/types/graphql'

const { query: gqlQuery } = useGraphQL()

const metadataId = defineModel<string | undefined>('metadataId', { required: true })
const renderer = defineModel<string | undefined>('renderer', { required: true })

const props = defineProps<{
  renderers: ContainerRenderer[]
  filters: string[]
}>()

const options = ref<Array<{ label: string; value: string }>>([])
const selectedName = ref('')
const selectedContentType = ref('')

const searchGql = gql`
  query SearchMetadataForContainer($query: String!, $filter: [String!]!) {
    search {
      search(query: {
        query: $query
        filter: $filter
        storageSystemName: "Admin Search Index"
        offset: 0
        limit: 20
      }) {
        documents {
          metadata {
            id
            name
            content {
              type
            }
          }
        }
      }
    }
  }
`

async function onSearch(q: string) {
  const parts = ['_type = "metadata"']
  if (props.filters?.length) parts.push(...props.filters)
  interface SearchDoc {
    metadata?: { id: string; name: string; content?: { type: string } }
  }
  interface SearchResult {
    search?: { search?: { documents?: SearchDoc[] } }
  }
  try {
    const result = await gqlQuery<SearchResult>(searchGql, { query: q, filter: parts })
    const docs = result?.search?.search?.documents ?? []
    options.value = docs
      .map((d) => d.metadata)
      .filter(Boolean)
      .map((m) => ({ label: m!.name, value: m!.id }))
  } catch {
    options.value = []
  }
  return options.value
}

const getMetadataGql = gql`
  query GetMetadataForContainer($id: UUID!) {
    content { metadata { metadata(id: $id) { id name content { type } } } }
  }
`

async function loadSelected() {
  if (!metadataId.value) return
  interface MetadataResult {
    content?: { metadata?: { metadata?: { id: string; name: string; content?: { type: string } } } }
  }
  try {
    const result = await gqlQuery<MetadataResult>(getMetadataGql, { id: metadataId.value })
    const m = result?.content?.metadata?.metadata
    if (m) {
      selectedName.value = m.name
      selectedContentType.value = m.content?.type ?? ''
    }
  } catch { /* ignore */ }
}

const rendererOptions = computed(() =>
  props.renderers.map(r => ({ label: r.name, value: r.name }))
)

function onClear() {
  metadataId.value = undefined
  renderer.value = undefined
  selectedName.value = ''
  selectedContentType.value = ''
}

watch(metadataId, loadSelected)
onMounted(() => { onSearch(''); loadSelected() })
</script>

<template>
  <div class="metadata-ref">
    <div class="metadata-ref-controls">
      <Select
        :model-value="metadataId"
        :options="options"
        placeholder="Search metadata…"
        searchable
        :on-search="onSearch"
        :debounce="250"
        @update:model-value="metadataId = $event as string"
      />
      <Select
        v-if="rendererOptions.length"
        :model-value="renderer"
        :options="rendererOptions"
        placeholder="Renderer"
        @update:model-value="renderer = $event as string"
      />
    </div>

    <div v-if="metadataId && selectedName" class="metadata-ref-selected">
      <Icon name="file" :size="13" color="var(--fg-2)" />
      <span class="metadata-ref-name">{{ selectedName }}</span>
      <Badge v-if="selectedContentType" color="var(--fg-3)">{{ selectedContentType }}</Badge>
      <Badge v-if="renderer" color="var(--info)">{{ renderer }}</Badge>
      <span class="spacer" />
      <button class="metadata-ref-clear" @click="onClear">
        <Icon name="x" :size="12" color="var(--fg-3)" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.metadata-ref {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metadata-ref-controls {
  display: flex;
  gap: 8px;
}

.metadata-ref-selected {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--line);
  background: var(--bg-2);
  font-size: 12.5px;
}

.metadata-ref-name {
  font-weight: 500;
  color: var(--fg-0);
}

.spacer {
  flex: 1;
}

.metadata-ref-clear {
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
}
</style>
