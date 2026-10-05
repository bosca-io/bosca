<script setup lang="ts">
import { TextInput as BoscaTextInput } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'

defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: string]
}>()
</script>

<template>
  <BoscaTextInput
    :model-value="(value as string) ?? ''"
    :placeholder="node.placeholder ?? ''"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
