<script lang="ts" setup>
import gql from 'graphql-tag'

const props = defineProps<{
  modelValue: string | null
  searchFilter?: string | null
  readOnly?: boolean
}>()

const emit = defineEmits<{
  (_e: 'update:modelValue', _value: string | null): void
}>()

const { query: gqlQuery } = useGraphQL()

const getMetadataGql = gql`
  query GetMetadataForTimeEventAttr($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        name
        content {
          type
        }
      }
    }
  }
`

const searchGql = gql`
  query FindTimeEventAttrMetadata($query: String!, $filter: String!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: 50
      }) {
        documents {
          metadata { id name content { type } }
        }
      }
    }
  }
`

interface ResolvedMetadata {
  id: string
  name: string
  content: { type: string } | null
}

interface MetadataSearchResult {
  search: { search: { documents: Array<{ metadata: ResolvedMetadata | null }> } }
}

const resolvedMetadata = ref<ResolvedMetadata | null>(null)

watch(
  () => props.modelValue,
  async (id) => {
    if (!id) {
      resolvedMetadata.value = null
      return
    }
    try {
      const result = await gqlQuery<{ content?: { metadata?: ResolvedMetadata | null } }>(
        getMetadataGql,
        { id },
      )
      resolvedMetadata.value = result?.content?.metadata ?? null
    } catch {
      // Resolution failure falls back to displaying the raw id in the template
      resolvedMetadata.value = null
    }
  },
  { immediate: true },
)

const options = ref<Array<{ label: string; value: string }>>([])

async function onSearch(q: string) {
  const filter = props.searchFilter
    ? `_type = "metadata" AND ${props.searchFilter}`
    : '_type = "metadata"'
  try {
    const result = await gqlQuery<MetadataSearchResult>(searchGql, { query: q, filter })
    const docs = result?.search?.search?.documents ?? []
    options.value = docs
      .map((d) => d.metadata)
      .filter((m): m is ResolvedMetadata => m != null)
      .map((m) => ({ label: m.name, value: m.id }))
  } catch {
    options.value = []
  }
  return options.value
}

const selectedId = computed({
  get: () => props.modelValue ?? '',
  set: (v: string) => emit('update:modelValue', v || null),
})

function onClear() {
  emit('update:modelValue', null)
}
</script>

<template>
  <div class="metadata-field">
    <div v-if="resolvedMetadata" class="metadata-display">
      <Icon name="file" :size="12" class="metadata-icon" />
      <span class="metadata-name">{{ resolvedMetadata.name }}</span>
      <Badge v-if="resolvedMetadata.content?.type" color="var(--fg-3)">
        {{ resolvedMetadata.content.type }}
      </Badge>
      <button
        v-if="!readOnly"
        class="metadata-clear"
        title="Clear"
        @click="onClear"
      >
        <Icon name="x" :size="12" />
      </button>
    </div>
    <span v-else-if="modelValue" class="metadata-raw">{{ modelValue }}</span>
    <Select
      v-else-if="!readOnly"
      v-model="selectedId"
      :options="options"
      placeholder="Select metadata…"
      searchable
      :on-search="onSearch"
      :debounce="250"
    />
    <span v-else class="metadata-empty">—</span>
  </div>
</template>

<style scoped>
.metadata-display {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: var(--fg-1);
}

.metadata-icon {
  flex-shrink: 0;
  color: var(--fg-3);
}

.metadata-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-clear {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.metadata-clear:hover {
  color: var(--fg-0);
  background: var(--bg-2);
}

.metadata-raw {
  font-size: 12px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
}

.metadata-empty {
  color: var(--fg-3);
}
</style>
