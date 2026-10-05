<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Select } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'
import { graphqlFetch } from '../../graphql-fetch'
import { useBoscaFormContext } from '../../context'
import { resolvePath } from '../../utils'

const ENTITY_CONFIGS: Record<string, { query: string; resultPath: string; valuePath: string; labelPath: string }> = {
  projects: {
    query: '{ workOps { projects { all { id key name } } } }',
    resultPath: 'workOps.projects.all',
    valuePath: 'id',
    labelPath: 'name',
  },
  taskTypes: {
    query: '{ workOps { tasks { taskTypes { id name } } } }',
    resultPath: 'workOps.tasks.taskTypes',
    valuePath: 'id',
    labelPath: 'name',
  },
  priorities: {
    query: '{ workOps { tasks { priorities { id name } } } }',
    resultPath: 'workOps.tasks.priorities',
    valuePath: 'id',
    labelPath: 'name',
  },
  statuses: {
    query: '{ workOps { statuses { all { id name category } } } }',
    resultPath: 'workOps.statuses.all',
    valuePath: 'id',
    labelPath: 'name',
  },
  resolutions: {
    query: '{ workOps { tasks { resolutions { id name } } } }',
    resultPath: 'workOps.tasks.resolutions',
    valuePath: 'id',
    labelPath: 'name',
  },
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

const entityType = computed(() => (props.node.workOpsEntity as string) ?? null)
const entityConfig = computed(() => entityType.value ? ENTITY_CONFIGS[entityType.value] ?? null : null)

const formContext = useBoscaFormContext()

async function fetchOptions() {
  if (!entityConfig.value || !formContext) {
    fetchError.value = !entityConfig.value
      ? `Unknown workOpsEntity: "${entityType.value}". Expected: ${Object.keys(ENTITY_CONFIGS).join(', ')}`
      : 'No BoscaForm context'
    return
  }

  loading.value = true
  fetchError.value = null

  const result = await graphqlFetch({
    apiUrl: formContext.apiUrl,
    query: entityConfig.value.query,
    variables: {},
    token: formContext.getToken(),
  })

  if (result.error) {
    fetchError.value = result.error
    loading.value = false
    return
  }

  const items = resolvePath(result.data, entityConfig.value.resultPath)
  if (!Array.isArray(items)) {
    fetchError.value = `Could not resolve options at "${entityConfig.value.resultPath}"`
    loading.value = false
    return
  }

  const { valuePath, labelPath } = entityConfig.value
  options.value = items.map(item => ({
    value: String(resolvePath(item, valuePath) ?? ''),
    label: String(resolvePath(item, labelPath) ?? ''),
  }))

  loading.value = false
}

onMounted(fetchOptions)
</script>

<template>
  <div>
    <Select
      :model-value="(value as string) ?? ''"
      :options="options"
      :placeholder="(node.placeholder as string) ?? 'Select...'"
      :disabled="readonly"
      :loading="loading"
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
