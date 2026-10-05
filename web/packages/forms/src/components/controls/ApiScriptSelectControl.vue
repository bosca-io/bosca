<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Select } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'
import { useBoscaFormContext } from '../../context'
import { resolvePath } from '../../utils'

interface ApiScriptConfig {
  key: string
  method?: 'GET' | 'POST'
  params?: Record<string, unknown>
  searchParam?: string
  resultPath?: string
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

const config = computed<ApiScriptConfig | null>(() => {
  const raw = props.node.apiScript
  if (raw && typeof raw === 'object' && 'key' in (raw as object) && 'valuePath' in (raw as object)) {
    return raw as ApiScriptConfig
  }
  return null
})

const formContext = useBoscaFormContext()

async function fetchOptions(searchText?: string) {
  if (!config.value || !formContext) {
    fetchError.value = !config.value ? 'Missing apiScript configuration' : 'No BoscaForm context'
    return
  }

  const currentFetchId = ++fetchId
  loading.value = true
  fetchError.value = null

  const method = config.value.method ?? 'GET'
  let url = `${formContext.apiUrl}/api/v1/s/${config.value.key}`

  const headers: Record<string, string> = { 'Accept': 'application/json' }
  const token = formContext.getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  let body: string | undefined

  if (method === 'GET') {
    const params = new URLSearchParams()
    if (config.value.params) {
      for (const [k, v] of Object.entries(config.value.params)) {
        params.set(k, String(v))
      }
    }
    if (config.value.searchParam && searchText != null) {
      params.set(config.value.searchParam, searchText)
    }
    const qs = params.toString()
    if (qs) url += `?${qs}`
  } else {
    headers['Content-Type'] = 'application/json'
    const payload: Record<string, unknown> = { ...config.value.params }
    if (config.value.searchParam && searchText != null) {
      payload[config.value.searchParam] = searchText
    }
    body = JSON.stringify(payload)
  }

  let response: Response
  try {
    response = await fetch(url, { method, headers, body })
  } catch (err) {
    if (currentFetchId !== fetchId) return
    fetchError.value = `Network error: ${err instanceof Error ? err.message : String(err)}`
    loading.value = false
    return
  }

  if (currentFetchId !== fetchId) return

  if (!response.ok) {
    fetchError.value = `Request failed with status ${response.status}`
    loading.value = false
    return
  }

  let json: unknown
  try {
    json = await response.json()
  } catch {
    fetchError.value = 'Invalid JSON response'
    loading.value = false
    return
  }

  const items = config.value.resultPath ? resolvePath(json, config.value.resultPath) : json
  if (!Array.isArray(items)) {
    fetchError.value = config.value.resultPath
      ? `Could not resolve options at path "${config.value.resultPath}"`
      : 'Response is not an array — set resultPath'
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
  if (!config.value?.searchParam) return undefined
  return async (q: string) => {
    await fetchOptions(q)
    return options.value
  }
})

onMounted(() => {
  if (config.value && !config.value.searchParam) {
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
