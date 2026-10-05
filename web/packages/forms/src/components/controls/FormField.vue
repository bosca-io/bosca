<script setup lang="ts">
import { computed } from 'vue'
import type { FieldNode, JsonSchema, JsonSchemaProperty } from '../../types'
import type { ValidationErrors } from '../../validation'
import { getControl } from '../../controls'

const props = defineProps<{
  node: FieldNode
  schema: JsonSchema
  modelValue: Record<string, unknown>
  readonly: boolean
  errors: ValidationErrors
}>()

const emit = defineEmits<{
  'update:field': [property: string, value: unknown]
}>()

const controlComponent = computed(() => getControl(props.node.control))

const fieldValue = computed(() => props.modelValue[props.node.property])

const fieldError = computed(() => props.errors[props.node.property])

const propertySchema = computed<JsonSchemaProperty | undefined>(
  () => props.schema.properties?.[props.node.property]
)

const isRequired = computed(
  () => props.schema.required?.includes(props.node.property) ?? false
)

const label = computed(
  () => props.node.label ?? props.node.property
)

function handleUpdate(value: unknown) {
  emit('update:field', props.node.property, value)
}
</script>

<template>
  <div class="bosca-form-field">
    <label class="field-label">
      {{ label }}
      <span v-if="isRequired" class="field-required">*</span>
    </label>

    <component
      v-if="controlComponent"
      :is="controlComponent"
      :value="fieldValue"
      :node="node"
      :property-schema="propertySchema"
      :readonly="readonly"
      :error="fieldError"
      @update:value="handleUpdate"
    />

    <div v-else class="field-unknown">
      Unknown control: {{ node.control }}
    </div>

    <div v-if="fieldError" class="field-error">
      {{ fieldError }}
    </div>
  </div>
</template>

<style scoped>
.bosca-form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.field-required {
  color: var(--err);
  margin-left: 2px;
}

.field-unknown {
  padding: 8px;
  border: 1px dashed var(--warn);
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--warn);
}

.field-error {
  font-size: 12px;
  color: var(--err);
}
</style>
