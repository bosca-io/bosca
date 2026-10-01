<script setup lang="ts">
import { computed } from 'vue'
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

const items = computed(() => {
  return (props.propertySchema?.enum as string[])?.map((opt) => ({
    label: String(opt),
    value: opt,
  })) ?? []
})
</script>

<template>
  <URadioGroup
    :model-value="(value as string) ?? ''"
    :items="items"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
