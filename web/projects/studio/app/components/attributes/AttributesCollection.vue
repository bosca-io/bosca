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
  query FindAttributeCollection($query: String!, $filter: String!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: 20
      }) {
        documents {
          collection {
            id
            name
          }
        }
      }
    }
  }
`

const options = ref<Array<{ label: string; value: string }>>([])

interface SearchResult {
  search: { search: { documents: Array<{ collection: { id: string; name: string } | null }> } }
}

async function onSearch(q: string) {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  const filter = config?.searchFilter
    ? `_type = "collection" AND ${config.searchFilter}`
    : '_type = "collection"'
  try {
    const result = await gqlQuery<SearchResult>(searchGql, { query: q, filter })
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

const selectedValue = computed({
  get: () => props.attribute.collection?.id ?? '',
  set: (id: string) => {
    const opt = options.value.find(o => o.value === id)
    // AttributeState.collection is a class setter that updates the Yjs doc, not a plain prop
    // eslint-disable-next-line vue/no-mutating-props
    props.attribute.collection = opt
      ? { id: opt.value, name: opt.label }
      : null
  }
})

// The async search only returns the top matches, so the connected collection
// may be absent from the option list — with no option to resolve its label
// from, the Select would render as if nothing were connected. Always provide
// the current value as a resolvable option.
const currentOptions = computed(() => {
  const c = props.attribute.collection
  return c ? [{ label: c.name, value: c.id }] : []
})

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
      v-model="selectedValue"
      :options="currentOptions"
      placeholder="Select collection…"
      searchable
      :on-search="onSearch"
      :debounce="250"
    />
    <div v-else class="collection-display">
      {{ attribute.collection?.name || '--' }}
    </div>
  </div>
</template>

<style scoped>
.attr-field {
  margin-bottom: 14px;
}

.collection-display {
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--fg-1);
}
</style>
