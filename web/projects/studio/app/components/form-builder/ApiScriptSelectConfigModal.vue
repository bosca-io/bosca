<script setup lang="ts">
import gql from 'graphql-tag'
import type { FieldNode, UiSchemaNode } from '@bosca/forms'
import type { SelectOption } from '@bosca/ui'

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
  node: FieldNode
  accent: string
}>()

const emit = defineEmits<{
  'update:node': [node: UiSchemaNode]
  close: []
}>()

const { useAsyncQuery } = useGraphQL()

const apiScriptsQuery = gql`
  query GetApiScripts {
    scripts {
      all(type: API) {
        id
        key
        name
        enabled
      }
    }
  }
`

const { data: scriptsData } = useAsyncQuery<{
  scripts: { all: Array<{ id: string; key: string; name: string; enabled: boolean }> }
}>('form-builder-api-scripts', apiScriptsQuery)

const scriptOptions = computed<SelectOption[]>(() =>
  (scriptsData.value?.scripts?.all ?? []).map(s => ({
    label: `${s.name} (${s.key})`,
    value: s.key,
  })),
)

const config = reactive<{
  key: string
  method: string
  resultPath: string
  valuePath: string
  labelPath: string
  searchParam: string
  debounceMs: number
}>({
  key: '',
  method: 'GET',
  resultPath: '',
  valuePath: 'value',
  labelPath: '',
  searchParam: '',
  debounceMs: 300,
})

const allowCustomValue = ref(false)

const methodOptions: SelectOption[] = [
  { label: 'GET', value: 'GET' },
  { label: 'POST', value: 'POST' },
]

onMounted(() => {
  const raw = props.node.apiScript as ApiScriptConfig | undefined
  config.key = raw?.key ?? ''
  config.method = raw?.method ?? 'GET'
  config.resultPath = raw?.resultPath ?? ''
  config.valuePath = raw?.valuePath ?? 'value'
  config.labelPath = raw?.labelPath ?? ''
  config.searchParam = raw?.searchParam ?? ''
  config.debounceMs = raw?.debounceMs ?? 300
  allowCustomValue.value = props.node.allowCustomValue === true
})

function save() {
  const apiScript: Record<string, unknown> = {
    key: config.key,
    valuePath: config.valuePath,
  }
  if (config.method !== 'GET') apiScript.method = config.method
  if (config.resultPath) apiScript.resultPath = config.resultPath
  if (config.labelPath) apiScript.labelPath = config.labelPath
  if (config.searchParam) apiScript.searchParam = config.searchParam
  if (config.debounceMs && config.debounceMs !== 300) apiScript.debounceMs = config.debounceMs

  emit('update:node', {
    ...props.node,
    apiScript,
    allowCustomValue: allowCustomValue.value || undefined,
  } as UiSchemaNode)
  emit('close')
}
</script>

<template>
  <Modal
    title="API Script Data Source"
    icon="code"
    :accent="accent"
    width="640px"
    @close="emit('close')"
  >
    <Select
      v-model="config.key"
      :options="scriptOptions"
      label="API Script"
      placeholder="Select a script…"
      :accent="accent"
    />

    <div class="grid-2">
      <Select
        v-model="config.method"
        :options="methodOptions"
        label="HTTP Method"
        :accent="accent"
      />
      <TextInput
        v-model="config.resultPath"
        label="Result Path"
        placeholder="data.items"
      />
      <TextInput
        v-model="config.valuePath"
        label="Value Path"
        placeholder="code"
      />
      <TextInput
        v-model="config.labelPath"
        label="Label Path"
        placeholder="name"
      />
      <TextInput
        v-model="config.searchParam"
        label="Search Param"
        placeholder="q"
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
      <Button
        primary
        :accent="accent"
        :disabled="!config.key || !config.valuePath"
        @click="save">Save</Button>
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
