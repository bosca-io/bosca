<script lang="ts" setup>
import gql from 'graphql-tag'
import type { AttributeMetadata, AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Collection, Metadata } from '~/types/graphql'

const { query: gqlQuery } = useGraphQL()

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  attribute: AttributeState
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const searchGql = gql`
  query FindAttributeMetadatas($query: String!, $filter: String!) {
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

const options = ref<Array<{ label: string; value: string; contentType: string }>>([])

interface MetadataSearchDocument {
  metadata: { id: string; name: string; content: { type: string } | null } | null
}

interface MetadataSearchResult {
  search: { search: { documents: MetadataSearchDocument[] } }
}

interface MetadataOption {
  label: string
  value: string
  contentType: string
}

async function onSearch(q: string) {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  const filter = config?.searchFilter
    ? `_type = "metadata" AND ${config.searchFilter}`
    : '_type = "metadata"'
  try {
    const result = await gqlQuery<MetadataSearchResult>(searchGql, { query: q, filter })
    const docs = result?.search?.search?.documents ?? []
    options.value = docs
      .map(d => d.metadata)
      .filter((m): m is NonNullable<MetadataSearchDocument['metadata']> => m !== null && m !== undefined)
      .map(m => ({ label: m.name, value: m.id, contentType: m.content?.type ?? '' }))
  } catch {
    options.value = []
  }
  return options.value
}

const selectedIds = computed({
  get: () => props.attribute.metadatas?.map(m => m.id) ?? [],
  set: (ids: string[]) => {
    const config = props.attribute.configuration as Record<string, unknown> | undefined
    // AttributeState.metadatas is a class setter that updates the Yjs doc, not a plain prop
    // eslint-disable-next-line vue/no-mutating-props
    props.attribute.metadatas = ids.map(id => {
      const existing = props.attribute.metadatas?.find(m => m.id === id)
      if (existing) return existing
      const opt = options.value.find(o => o.value === id) as MetadataOption | undefined
      return {
        id,
        relationship: config?.relationship as string,
        contentType: opt?.contentType || 'text/plain',
        attributes: { sort: 0 },
        name: opt?.label ?? id
      } as AttributeMetadata
    })
  }
})

// The async search only returns the top matches, so linked metadata may be
// absent from the option list — with no options to resolve their labels from,
// the Select would render their tags as missing. Always provide the current
// values as resolvable options.
const currentOptions = computed(() =>
  (props.attribute.metadatas ?? []).map(m => ({ label: m.name, value: m.id })))

onMounted(() => onSearch(''))
</script>

<template>
  <div :key="attribute.changeRef.value" class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :on-run-tool="onRunTool"
    />
    <Select
      v-if="editable"
      v-model="selectedIds"
      :options="currentOptions"
      placeholder="Select metadata…"
      searchable
      multiple
      :on-search="onSearch"
      :debounce="250"
    />
    <div v-else-if="attribute.metadatas?.length" class="metadatas-display">
      <Badge v-for="m in attribute.metadatas" :key="m.id" color="var(--fg-3)">
        {{ m.name }}
      </Badge>
    </div>
    <div v-else class="attr-empty">No selection</div>
  </div>
</template>

<style scoped>
.attr-field { margin-bottom: 14px; }
.metadatas-display { display: flex; flex-wrap: wrap; gap: 4px; }
.attr-empty { font-size: 12px; color: var(--fg-3); }
</style>
