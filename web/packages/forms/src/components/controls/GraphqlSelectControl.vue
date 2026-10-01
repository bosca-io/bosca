<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Select } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'
import { graphqlFetch } from '../../graphql-fetch'
import { useBoscaFormContext } from '../../context'
import { resolvePath } from '../../utils'

interface GraphqlConfig {
  query: string
  variables?: Record<string, unknown>
  searchVariable?: string
  resultPath: string
  valuePath: string
  labelPath?: string
  debounceMs?: number
}

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
const fetchError = ref<string | null>(null)

let fetchId = 0

const config = computed<GraphqlConfig | null>(() => {
  const raw = props.node.graphql
  if (raw && typeof raw === 'object' && 'query' in (raw as object) && 'resultPath' in (raw as object)) {
    return raw as GraphqlConfig
  }
  return null
})

const formContext = useBoscaFormContext()

async function fetchOptions(searchText?: string) {
  if (!config.value || !formContext) {
    fetchError.value = !config.value ? 'Missing graphql configuration' : 'No BoscaForm context'
    return
  }

  const currentFetchId = ++fetchId
  loading.value = true
  fetchError.value = null

  const variables: Record<string, unknown> = { ...config.value.variables }
  if (config.value.searchVariable && searchText != null) {
    variables[config.value.searchVariable] = searchText
  }

  const result = await graphqlFetch({
    apiUrl: formContext.apiUrl,
    query: config.value.query,
    variables,
    token: formContext.getToken(),
  })

  if (currentFetchId !== fetchId) return

  if (result.error) {
    fetchError.value = result.error
    loading.value = false
    return
  }

  const items = resolvePath(result.data, config.value.resultPath)
  if (!Array.isArray(items)) {
    fetchError.value = `Could not resolve options at path "${config.value.resultPath}"`
    loading.value = false
    return
  }

  const valuePath = config.value.valuePath
  const labelPath = config.value.labelPath ?? valuePath
  options.value = items.map(item => ({
    value: String(resolvePath(item, valuePath) ?? ''),
    label: String(resolvePath(item, labelPath) ?? ''),
  }))

  loading.value = false
}

const searchHandler = computed(() => {
  if (!config.value?.searchVariable) return undefined
  return async (q: string) => {
    await fetchOptions(q)
    return options.value
  }
})

onMounted(() => {
  if (config.value && !config.value.searchVariable) {
    fetchOptions()
  }
})
</script>

<template>
  <div>
    <Select
      :model-value="(value as string) ?? ''"
      :options="options"
      :placeholder="(node.placeholder as string) ?? 'Select...'"
      :disabled="readonly"
      :loading="loading"
      :on-search="searchHandler"
      @update:model-value="emit('update:value', ($event as string) ?? '')"
    />
    <p v-if="fetchError" class="select-error">{{ fetchError }}</p>
  </div>
</template>

<style scoped>
.select-error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--err);
}
</style>
