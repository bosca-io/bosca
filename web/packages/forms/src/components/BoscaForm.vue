<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { Button } from '@bosca/ui'
import type { FormSchema, JsonSchema, UiSchema, FormSchemaProfileMapping, ProfileInput } from '../types'
import type { ValidationErrors } from '../validation'
import { validateFormData } from '../validation'
import { provideBoscaFormContext } from '../context'
import { useBoscaForms } from '../nuxt'
import BoscaFormRenderer from './BoscaFormRenderer.vue'

// Register built-in controls on first mount
import '../register'

const props = withDefaults(defineProps<{
  /** Key to auto-fetch schema from backend */
  schemaKey?: string
  /** JSON Schema passed directly (skip fetch) */
  schema?: JsonSchema
  /** UI Schema passed directly (skip fetch) */
  uiSchema?: UiSchema
  /** Form data (v-model) */
  modelValue?: Record<string, unknown>
  /** Disable all editing */
  readonly?: boolean
  /** External validation errors */
  errors?: Record<string, string>
  /** Edit mode (two-way binding) or submit mode (collect + submit) */
  mode?: 'edit' | 'submit'
  /** Button text in submit mode */
  submitLabel?: string
  /** FormSchema ID for submission mode */
  formSchemaId?: string
}>(), {
  readonly: false,
  mode: 'edit',
  submitLabel: 'Submit',
})

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
  validate: [errors: ValidationErrors]
  submitted: [result: unknown]
}>()

let forms: ReturnType<typeof useBoscaForms> | null = null
try {
  forms = useBoscaForms()
  provideBoscaFormContext({ apiUrl: forms.apiUrl, getToken: forms.getToken })
} catch {
  // Forms not initialized — controls that need API access will degrade gracefully
}

const loading = ref(false)
const submitting = ref(false)
const fetchedSchema = ref<FormSchema | null>(null)
const internalData = ref<Record<string, unknown>>({})
const validationErrors = ref<ValidationErrors>({})
const submitSuccess = ref(false)
const submitError = ref<string | null>(null)

const jsonSchema = computed(() => props.schema ?? fetchedSchema.value?.schema ?? null)
const uiSchemaComputed = computed(() => props.uiSchema ?? fetchedSchema.value?.uiSchema ?? null)

const formData = computed({
  get: () => props.mode === 'edit' ? (props.modelValue ?? {}) : internalData.value,
  set: (val) => {
    if (props.mode === 'edit') {
      emit('update:modelValue', val)
    } else {
      internalData.value = val
    }
  },
})

const allErrors = computed(() => ({
  ...validationErrors.value,
  ...props.errors,
}))

function setByPath(obj: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
  const segments = path.split('.')
  if (segments.length === 1) {
    return { ...obj, [path]: value }
  }
  const [head, ...rest] = segments as [string, ...string[]]
  const child = (obj[head] && typeof obj[head] === 'object') ? { ...(obj[head] as Record<string, unknown>) } : {}
  return { ...obj, [head]: setByPath(child, rest.join('.'), value) }
}

function updateField(property: string, value: unknown) {
  formData.value = setByPath(formData.value, property, value)
}

function validate(): boolean {
  if (!jsonSchema.value) return false
  const errors = validateFormData(jsonSchema.value, formData.value)
  validationErrors.value = errors
  emit('validate', errors)
  return Object.keys(errors).length === 0
}

function prefillFromProfile(forms: ReturnType<typeof useBoscaForms>) {
  const mapping = fetchedSchema.value?.profileMapping
  if (!mapping) return
  const profile = forms.getProfile()
  if (!profile) return

  const prefilled: Record<string, unknown> = {}
  prefilled[mapping.nameField] = profile.name

  for (const attr of mapping.attributes) {
    const profileAttr = profile.attributes.find(a => a.typeId === attr.typeId)
    if (profileAttr?.attributes?.[attr.attributeKey] != null) {
      prefilled[attr.field] = profileAttr.attributes[attr.attributeKey]
    }
  }

  formData.value = { ...prefilled, ...formData.value }
}

function buildProfileFromMapping(data: Record<string, unknown>, mapping: FormSchemaProfileMapping): ProfileInput {
  const name = String(data[mapping.nameField] ?? '')
  return {
    name,
    visibility: mapping.visibility,
    attributes: mapping.attributes.map((attr) => ({
      typeId: attr.typeId,
      attributes: { [attr.attributeKey]: data[attr.field] },
      confidence: 100,
      priority: 1,
      source: `form:${fetchedSchema.value?.key ?? props.formSchemaId ?? 'unknown'}`,
      visibility: mapping.visibility,
    })),
  }
}

async function handleSubmit() {
  if (!validate()) return

  submitting.value = true
  submitError.value = null
  submitSuccess.value = false

  try {
    if (!forms) throw new Error('Forms SDK not initialized')
    const resolvedSchemaId = props.formSchemaId ?? fetchedSchema.value?.id
    const profileMapping = fetchedSchema.value?.profileMapping
    const token = forms.getToken()
    const profile = (!token && profileMapping) ? buildProfileFromMapping(formData.value, profileMapping) : undefined
    const result = await forms.api.submitForm({
      formSchemaId: resolvedSchemaId,
      attributes: formData.value,
      profile,
    })
    submitSuccess.value = true
    emit('submitted', result)
    internalData.value = {}
  } catch (err) {
    submitError.value = err instanceof Error ? err.message : 'Submission failed'
  } finally {
    submitting.value = false
  }
}

async function loadSchema(key: string) {
  loading.value = true
  try {
    if (!forms) throw new Error('Forms SDK not initialized')
    fetchedSchema.value = await forms.fetchSchema(key)
    prefillFromProfile(forms)
  } catch (err) {
    console.error('Failed to fetch form schema:', err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.schemaKey && !props.schema) {
    loadSchema(props.schemaKey)
  }
})

watch(() => props.schemaKey, (newKey) => {
  if (newKey && !props.schema) {
    loadSchema(newKey)
  }
})
</script>

<template>
  <div class="bosca-form">
    <div v-if="loading" class="form-loading">
      Loading form...
    </div>

    <template v-else-if="jsonSchema && uiSchemaComputed">
      <BoscaFormRenderer
        :schema="jsonSchema"
        :ui-schema="uiSchemaComputed"
        :model-value="formData"
        :readonly="readonly || false"
        :errors="allErrors"
        @update:field="updateField"
      />

      <div v-if="allErrors['']" class="form-error">
        {{ allErrors[''] }}
      </div>

      <div v-if="mode === 'submit'" class="form-actions">
        <Button
          primary
          :disabled="submitting || readonly"
          @click="handleSubmit"
        >
          {{ submitting ? 'Submitting...' : submitLabel }}
        </Button>

        <div v-if="submitSuccess" class="form-success">
          Submitted successfully!
        </div>
        <div v-if="submitError" class="form-error">
          {{ submitError }}
        </div>
      </div>
    </template>

    <div v-else class="form-empty">
      No form schema found{{ schemaKey ? ` for key "${schemaKey}"` : '' }}.
    </div>
  </div>
</template>

<style scoped>
.form-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  font-size: 13px;
  color: var(--fg-3);
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}

.form-error {
  font-size: 12px;
  color: var(--err);
}

.form-success {
  font-size: 12px;
  color: var(--ok);
}

.form-empty {
  padding: 14px;
  font-size: 13px;
  color: var(--fg-3);
}
</style>
