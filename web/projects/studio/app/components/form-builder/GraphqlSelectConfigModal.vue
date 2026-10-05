<script setup lang="ts">
import type { FieldNode, UiSchemaNode } from '@bosca/forms'

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
  node: FieldNode
  accent: string
}>()

const emit = defineEmits<{
  'update:node': [node: UiSchemaNode]
  close: []
}>()

const config = reactive<GraphqlConfig>({
  query: '',
  resultPath: '',
  valuePath: 'value',
  labelPath: '',
  searchVariable: '',
  debounceMs: 300,
})

const allowCustomValue = ref(false)

onMounted(() => {
  const raw = props.node.graphql as GraphqlConfig | undefined
  config.query = raw?.query ?? ''
  config.resultPath = raw?.resultPath ?? ''
  config.valuePath = raw?.valuePath ?? 'value'
  config.labelPath = raw?.labelPath ?? ''
  config.searchVariable = raw?.searchVariable ?? ''
  config.debounceMs = raw?.debounceMs ?? 300
  allowCustomValue.value = props.node.allowCustomValue === true
})

function save() {
  const graphql: Record<string, unknown> = {
    query: config.query,
    resultPath: config.resultPath,
    valuePath: config.valuePath,
  }
  if (config.labelPath) graphql.labelPath = config.labelPath
  if (config.searchVariable) graphql.searchVariable = config.searchVariable
  if (config.debounceMs && config.debounceMs !== 300) graphql.debounceMs = config.debounceMs

  emit('update:node', {
    ...props.node,
    graphql,
    allowCustomValue: allowCustomValue.value || undefined,
  } as UiSchemaNode)
  emit('close')
}
</script>

<template>
  <Modal
    title="GraphQL Data Source"
    icon="database"
    :accent="accent"
    width="740px"
    @close="emit('close')"
  >
    <CodeEditor
      v-model="config.query"
      label="Query"
      language="text"
      :rows="10"
      placeholder="{ search { search(q: $q) { facets { value count } } } }"
    />

    <div class="grid-2">
      <TextInput
        v-model="config.resultPath"
        label="Result Path"
        placeholder="search.search.facets"
      />
      <TextInput
        v-model="config.searchVariable"
        label="Search Variable"
        placeholder="q"
      />
      <TextInput
        v-model="config.valuePath"
        label="Value Path"
        placeholder="value"
      />
      <TextInput
        v-model="config.labelPath"
        label="Label Path"
        placeholder="value"
      />
      <TextInput
        :model-value="String(config.debounceMs)"
        label="Debounce (ms)"
        @update:model-value="(v: string) => config.debounceMs = Number(v) || 300"
      />
      <div class="switch-field">
        <Switch
          v-model="allowCustomValue"
          label="Allow Custom Value"
          :accent="accent"
        />
      </div>
    </div>

    <template #footer>
      <Button @click="emit('close')">Cancel</Button>
      <Button primary :accent="accent" @click="save">Save</Button>
    </template>
  </Modal>
</template>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.switch-field {
  display: flex;
  align-items: flex-end;
  padding-bottom: 4px;
}
</style>
