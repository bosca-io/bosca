<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Select } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'
import { graphqlFetch } from '../../graphql-fetch'
import { useBoscaFormContext } from '../../context'

const props = defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: string]
}>()

const options = ref<{ value: string; label: string }[]>([])
const loading = ref(false)

const formContext = useBoscaFormContext()

async function searchProfiles(query: string) {
  if (!formContext) return []
  loading.value = true

  const gql = `
    query SearchProfiles($query: String!, $limit: Int!, $offset: Long!) {
      search {
        search(query: {
          query: $query
          filter: ["_type = \\"profile\\""]
          storageSystemName: "Admin Search Index"
          limit: $limit
          offset: $offset
        }) {
          documents {
            profile { id name slug }
          }
        }
      }
    }
  `

  const result = await graphqlFetch({
    apiUrl: formContext.apiUrl,
    query: gql,
    variables: { query: query || '', limit: 20, offset: 0 },
    token: formContext.getToken(),
  })

  loading.value = false

  if (result.error || !result.data) return options.value

  const docs = (result.data as any)?.search?.search?.documents ?? []
  options.value = docs
    .filter((d: any) => d.profile != null)
    .map((d: any) => ({
      value: d.profile.id,
      label: d.profile.name || d.profile.slug || d.profile.id,
    }))

  return options.value
}

onMounted(() => {
  searchProfiles('')
})
</script>

<template>
  <Select
    :model-value="(value as string) ?? ''"
    :options="options"
    :placeholder="(node.placeholder as string) ?? 'Search profiles…'"
    :disabled="readonly"
    :loading="loading"
    :on-search="searchProfiles"
    searchable
    @update:model-value="emit('update:value', ($event as string) ?? '')"
  />
</template>
