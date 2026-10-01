<script lang="ts" setup>
import gql from 'graphql-tag'
import type { AttributeState } from '~/utils/editor/attribute'
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
  query FindAttributeCollections($query: String!, $filter: String!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: 50
      }) {
        documents {
          collection { id name }
        }
      }
    }
  }
`

const options = ref<Array<{ label: string; value: string }>>([])

interface CollectionsSearchResult {
  search: { search: { documents: Array<{ collection: { id: string; name: string } | null }> } }
}

async function onSearch(q: string) {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  const filter = config?.searchFilter
    ? `_type = "collection" AND ${config.searchFilter}`
    : '_type = "collection"'
  try {
    const result = await gqlQuery<CollectionsSearchResult>(searchGql, { query: q, filter })
    const docs = result?.search?.search?.documents ?? []
    options.value = docs
      .map(d => d.collection)
      .filter((c): c is { id: string; name: string } => c !== null && c !== undefined)
      .map(c => ({ label: c.name, value: c.id }))
  } catch {
    options.value = []
  }
  return options.value
}

const selectedIds = computed({
  get: () => props.attribute.collections?.map(c => c.id) ?? [],
  set: (ids: string[]) => {
    // AttributeState.collections is a class setter that updates the Yjs doc, not a plain prop
    // eslint-disable-next-line vue/no-mutating-props
    props.attribute.collections = ids.map(id => {
      const existing = props.attribute.collections?.find(c => c.id === id)
      if (existing) return existing
      const opt = options.value.find(o => o.value === id)
      return { id, name: opt?.label ?? id }
    })
  }
})

// The async search only returns the top matches, so connected collections may
// be absent from the option list — with no options to resolve their labels
// from, the Select would render their tags as missing. Always provide the
// current values as resolvable options.
const currentOptions = computed(() =>
  (props.attribute.collections ?? []).map(c => ({ label: c.name, value: c.id })))

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
      placeholder="Select collections…"
      searchable
      multiple
      :on-search="onSearch"
      :debounce="250"
    />
    <div v-else-if="attribute.collections?.length" class="collections-display">
      <Badge v-for="c in attribute.collections" :key="c.id" color="var(--fg-3)">
        {{ c.name }}
      </Badge>
    </div>
    <div v-else class="attr-empty">No selection</div>
  </div>
</template>

<style scoped>
.attr-field { margin-bottom: 14px; }
.collections-display { display: flex; flex-wrap: wrap; gap: 4px; }
.attr-empty { font-size: 12px; color: var(--fg-3); }
</style>
