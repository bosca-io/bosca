<script setup lang="ts">
import { Textarea } from '@bosca/ui'
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
  <Textarea
    :model-value="(value as string) ?? ''"
    :placeholder="node.placeholder ?? ''"
    :disabled="readonly"
    :rows="node.rows ?? 3"
    @update:model-value="emit('update:value', $event)"
  />
</template>
