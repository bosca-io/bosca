<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UiSchemaNode, FieldNode, SectionNode, DisplayNode, JsonSchema } from '@bosca/forms'
import type { SelectOption } from '@bosca/ui'

const props = withDefaults(defineProps<{
  node: UiSchemaNode
  schema: JsonSchema
  siblingFieldKeys?: string[]
  accent: string
}>(), {
  siblingFieldKeys: () => [],
})

const duplicateKey = computed(() => {
  if (props.node.type !== 'field') return false
  return props.siblingFieldKeys.includes((props.node as FieldNode).property)
})

const emit = defineEmits<{
  'update:node': [node: UiSchemaNode]
  'update:schema': [schema: JsonSchema]
  'open:graphql-config': []
  'open:api-script-config': []
}>()

const controlOptions: SelectOption[] = [
  { label: 'Text Input', value: 'text-input' },
  { label: 'Text Area', value: 'textarea' },
  { label: 'Number', value: 'number-input' },
  { label: 'Select', value: 'select' },
  { label: 'GraphQL Select', value: 'graphql-select' },
  { label: 'API Script Select', value: 'api-script-select' },
  { label: 'Work Ops Select', value: 'workops-select' },
  { label: 'Checkbox', value: 'checkbox' },
  { label: 'Switch', value: 'switch' },
  { label: 'Date Picker', value: 'date-picker' },
  { label: 'Tags', value: 'tag-input' },
  { label: 'Radio Group', value: 'radio-group' },
  { label: 'Color Picker', value: 'color-picker' },
  { label: 'Image Upload', value: 'image-upload' },
  { label: 'File Upload', value: 'file-upload' },
]

const workOpsEntityOptions: SelectOption[] = [
  { label: 'Projects', value: 'projects' },
  { label: 'Task Types', value: 'taskTypes' },
  { label: 'Priorities', value: 'priorities' },
  { label: 'Statuses', value: 'statuses' },
  { label: 'Resolutions', value: 'resolutions' },
]

const alertVariants: SelectOption[] = [
  { label: 'Info', value: 'info' },
  { label: 'Warning', value: 'warning' },
  { label: 'Error', value: 'error' },
  { label: 'Success', value: 'success' },
]

const schemaTypeOptions: SelectOption[] = [
  { label: 'String', value: 'string' },
  { label: 'Integer', value: 'integer' },
  { label: 'Number', value: 'number' },
  { label: 'Boolean', value: 'boolean' },
  { label: 'Array', value: 'array' },
]

function updateField<K extends keyof FieldNode>(key: K, value: FieldNode[K]) {
  emit('update:node', { ...props.node, [key]: value } as UiSchemaNode)
}

function updateDisplay<K extends keyof DisplayNode>(key: K, value: DisplayNode[K]) {
  emit('update:node', { ...props.node, [key]: value } as UiSchemaNode)
}

function updateSection<K extends keyof SectionNode>(key: K, value: SectionNode[K]) {
  emit('update:node', { ...props.node, [key]: value } as UiSchemaNode)
}

const propertySchema = computed(() => {
  if (props.node.type !== 'field') return null
  return props.schema.properties?.[(props.node as FieldNode).property]
})

function updatePropertySchema(key: string, value: unknown) {
  if (props.node.type !== 'field') return
  const property = (props.node as FieldNode).property
  const currentPropSchema = props.schema.properties?.[property] ?? {}
  emit('update:schema', {
    ...props.schema,
    properties: {
      ...props.schema.properties,
      [property]: { ...currentPropSchema, [key]: value },
    },
  })
}

function setRequired(value: boolean) {
  if (props.node.type !== 'field') return
  const property = (props.node as FieldNode).property
  const required = props.schema.required ?? []
  emit('update:schema', {
    ...props.schema,
    required: value
      ? [...required.filter(r => r !== property), property]
      : required.filter(r => r !== property),
  })
}

const isRequired = computed(() => {
  if (props.node.type !== 'field') return false
  return props.schema.required?.includes((props.node as FieldNode).property) ?? false
})

const enumValuesStr = ref('')
watch(() => propertySchema.value?.enum, (val) => {
  enumValuesStr.value = ((val as string[]) ?? []).join(', ')
}, { immediate: true })

function updateEnum() {
  const values = enumValuesStr.value.split(',').map(s => s.trim()).filter(Boolean)
  updatePropertySchema('enum', values.length > 0 ? values : undefined)
}

const fieldKey = computed({
  get: () => (props.node as FieldNode).property ?? '',
  set: (v: string) => updateField('property', v),
})

const fieldLabel = computed({
  get: () => (props.node as FieldNode).label ?? '',
  set: (v: string) => updateField('label', v || undefined),
})

const fieldPlaceholder = computed({
  get: () => (props.node as FieldNode).placeholder ?? '',
  set: (v: string) => updateField('placeholder', v || undefined),
})

const fieldControl = computed({
  get: () => (props.node as FieldNode).control ?? 'text-input',
  set: (v: string) => updateField('control', v),
})

const fieldCol = computed({
  get: () => String((props.node as FieldNode).col ?? ''),
  set: (v: string) => updateField('col', v ? Number(v) : undefined),
})

const sectionLabel = computed({
  get: () => (props.node as SectionNode).label ?? '',
  set: (v: string) => updateSection('label', v),
})

const displayText = computed({
  get: () => (props.node as DisplayNode).text ?? '',
  set: (v: string) => updateDisplay('text', v),
})

const displayVariant = computed({
  get: () => (props.node as DisplayNode).variant ?? 'info',
  set: (v: string) => updateDisplay('variant', v),
})

const displayIcon = computed({
  get: () => (props.node as DisplayNode).icon ?? '',
  set: (v: string) => updateDisplay('icon', v || undefined),
})

const displayLevel = computed({
  get: () => String((props.node as DisplayNode).level ?? 3),
  set: (v: string) => updateDisplay('level', Number(v)),
})

const schemaType = computed({
  get: () => (propertySchema.value?.type as string) ?? 'string',
  set: (v: string) => updatePropertySchema('type', v),
})

const workOpsEntity = computed({
  get: () => ((props.node as FieldNode).workOpsEntity as string) ?? 'projects',
  set: (v: string) => updateField('workOpsEntity' as keyof FieldNode, v),
})
</script>

<template>
  <div class="editor">
    <div class="editor-heading">Properties</div>

    <!-- Section node -->
    <template v-if="node.type === 'section'">
      <TextInput v-model="sectionLabel" label="Label" />
    </template>

    <!-- Field node -->
    <template v-else-if="node.type === 'field'">
      <div class="editor-field">
        <TextInput v-model="fieldKey" label="Key" mono />
        <p v-if="duplicateKey" class="editor-error">A sibling field already uses this key</p>
      </div>

      <TextInput v-model="fieldLabel" label="Label" />

      <Select
        v-model="fieldControl"
        :options="controlOptions"
        label="Control"
        :accent="accent"
      />

      <TextInput v-model="fieldPlaceholder" label="Placeholder" />

      <TextInput v-model="fieldCol" label="Column Span (1–12)" />

      <Switch
        :model-value="isRequired"
        label="Required"
        :accent="accent"
        @update:model-value="setRequired"
      />

      <div class="editor-heading schema-heading">Schema</div>

      <Select
        v-model="schemaType"
        :options="schemaTypeOptions"
        label="Type"
        :accent="accent"
      />

      <template v-if="(node as FieldNode).control === 'select' || (node as FieldNode).control === 'radio-group'">
        <TextInput
          v-model="enumValuesStr"
          label="Options (comma-separated)"
          placeholder="option1, option2, option3"
          @blur="updateEnum"
        />
      </template>

      <template v-if="(node as FieldNode).control === 'graphql-select'">
        <Button icon="database" :accent="accent" @click="emit('open:graphql-config')">
          Configure Data Source
        </Button>
      </template>

      <template v-if="(node as FieldNode).control === 'api-script-select'">
        <Button icon="code" :accent="accent" @click="emit('open:api-script-config')">
          Configure Data Source
        </Button>
      </template>

      <template v-if="(node as FieldNode).control === 'workops-select'">
        <Select
          v-model="workOpsEntity"
          :options="workOpsEntityOptions"
          label="Entity Type"
          :accent="accent"
        />
      </template>

      <template v-if="propertySchema?.type === 'string'">
        <TextInput
          :model-value="String(propertySchema?.minLength ?? '')"
          label="Min Length"
          @update:model-value="(v: string) => updatePropertySchema('minLength', v ? Number(v) : undefined)"
        />
        <TextInput
          :model-value="String(propertySchema?.maxLength ?? '')"
          label="Max Length"
          @update:model-value="(v: string) => updatePropertySchema('maxLength', v ? Number(v) : undefined)"
        />
      </template>

      <template v-if="propertySchema?.type === 'integer' || propertySchema?.type === 'number'">
        <TextInput
          :model-value="String(propertySchema?.minimum ?? '')"
          label="Minimum"
          @update:model-value="(v: string) => updatePropertySchema('minimum', v ? Number(v) : undefined)"
        />
        <TextInput
          :model-value="String(propertySchema?.maximum ?? '')"
          label="Maximum"
          @update:model-value="(v: string) => updatePropertySchema('maximum', v ? Number(v) : undefined)"
        />
      </template>
    </template>

    <!-- Display node -->
    <template v-else-if="node.type === 'display'">
      <Select
        v-if="(node as DisplayNode).control === 'alert'"
        v-model="displayVariant"
        :options="alertVariants"
        label="Variant"
        :accent="accent"
      />

      <TextInput
        v-if="(node as DisplayNode).control === 'alert'"
        v-model="displayIcon"
        label="Icon"
        placeholder="e.g. alert"
      />

      <TextInput
        v-if="(node as DisplayNode).control !== 'divider'"
        v-model="displayText"
        label="Text"
      />

      <TextInput
        v-if="(node as DisplayNode).control === 'heading'"
        v-model="displayLevel"
        label="Level (1–6)"
      />
    </template>

    <!-- Row node -->
    <template v-else-if="node.type === 'row'">
      <p class="editor-hint">
        Row container. Add field nodes to the layout, then move them into this row.
      </p>
    </template>
  </div>
</template>

<style scoped>
.editor {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-heading {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.schema-heading {
  margin-top: 8px;
}

.editor-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.editor-error {
  font-size: 11px;
  color: var(--err);
  margin: 0;
}

.editor-hint {
  font-size: 13px;
  color: var(--fg-3);
  margin: 0;
}
</style>
