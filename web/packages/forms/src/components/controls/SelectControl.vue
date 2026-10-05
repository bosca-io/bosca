<script setup lang="ts">
import { computed } from 'vue'
import { Select } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'

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

const options = computed(() => {
  return (props.propertySchema?.enum as string[])?.map((opt) => ({
    label: String(opt),
    value: opt,
  })) ?? []
})
</script>

<template>
  <Select
    :model-value="(value as string) ?? ''"
    :options="options"
    :placeholder="node.placeholder ?? 'Select...'"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event as string)"
  />
</template>
