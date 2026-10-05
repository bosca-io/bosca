<script setup lang="ts">
import type { ShapeField } from '~/components/pipelines/pipelineNodeTypes'

/**
 * Edits an object *shape* — a list of typed `{ name, type }` fields — used as a pipeline's Input or Output
 * contract when the value is an ad-hoc object (e.g. an Objects → Map result). Each field's type is picked
 * from the same catalogued types the rest of the editor uses (plus primitives and `[]` list variants), so
 * a For Each and consuming pipelines see the real shape rather than opaque JSON.
 */
const props = defineProps<{
  modelValue: ShapeField[]
  /** Type choices for each field — primitives, JSON, and catalogued types (single; use the array toggle for lists). */
  typeOptions: { value: string, label: string }[]
  /** Hide the built-in "Save as reusable type" row (the host already handles saving, e.g. the Type Browser). */
  hideReusableSave?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [ShapeField[]] }>()

// A field's stored type is `X` or `X[]`; the UI splits it into a single-type dropdown + an array toggle.
function baseType(type: string): string { return type.replace(/\[\]$/, '') }
function isArray(type: string): boolean { return type.endsWith('[]') }

function setName(i: number, name: string) {
  emit('update:modelValue', props.modelValue.map((f, idx) => (idx === i ? { ...f, name } : f)))
}
/** Set the base type, preserving the field's current array flag. */
function setType(i: number, base: string) {
  const type = isArray(props.modelValue[i]!.type) ? `${base}[]` : base
  emit('update:modelValue', props.modelValue.map((f, idx) => (idx === i ? { ...f, type } : f)))
}
/** Toggle whether the field is a list of its base type. */
function setArray(i: number, arr: boolean) {
  const base = baseType(props.modelValue[i]!.type)
  emit('update:modelValue', props.modelValue.map((f, idx) => (idx === i ? { ...f, type: arr ? `${base}[]` : base } : f)))
}
function addField() {
  emit('update:modelValue', [...props.modelValue, { name: '', type: 'String' }])
}
function removeField(i: number) {
  emit('update:modelValue', props.modelValue.filter((_, idx) => idx !== i))
}

// Save the current fields as a reusable named shape (a first-class type), if the editor provided a saver.
const saveNamedShape = inject<(name: string, fields: ShapeField[]) => Promise<void>>('saveNamedShape')
const shapeName = ref('')
const saving = ref(false)
async function saveAsReusable() {
  const name = shapeName.value.trim()
  if (!name || !saveNamedShape || !props.modelValue.length) return
  saving.value = true
  try {
    await saveNamedShape(name, props.modelValue)
    shapeName.value = ''
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="shape-builder">
    <div
      v-for="(f, i) in modelValue"
      :key="i"
      class="shape-field"
    >
      <TextInput
        :model-value="f.name"
        placeholder="field name"
        size="sm"
        @update:model-value="(v: string) => setName(i, v)"
      />
      <Select
        :model-value="baseType(f.type)"
        :options="typeOptions"
        size="sm"
        searchable
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && setType(i, v)"
      />
      <button
        type="button"
        class="shape-array-btn"
        :class="{ 'is-array': isArray(f.type) }"
        title="Toggle list (array of this type)"
        @click="setArray(i, !isArray(f.type))"
      >
        [ ]
      </button>
      <button
        type="button"
        class="shape-remove"
        title="Remove field"
        @click="removeField(i)"
      >
        <Icon
          name="trash"
          :size="13"
        />
      </button>
    </div>
    <Button
      size="xs"
      variant="ghost"
      icon="plus"
      @click="addField"
    >
      Add field
    </Button>
    <div
      v-if="!hideReusableSave && saveNamedShape && modelValue.length"
      class="shape-save"
    >
      <TextInput
        v-model="shapeName"
        placeholder="Reusable name, e.g. ReleaseBundle"
        size="sm"
      />
      <Button
        size="xs"
        variant="ghost"
        icon="save"
        :disabled="!shapeName.trim() || saving"
        @click="saveAsReusable"
      >
        Save as reusable type
      </Button>
    </div>
  </div>
</template>

<style scoped>
.shape-builder { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.shape-field { display: flex; align-items: center; gap: 6px; width: 100%; }
.shape-field > :first-child { flex: 0 0 42%; }
.shape-field > :nth-child(2) { flex: 1; min-width: 0; }
.shape-remove {
  background: none; border: none; padding: 4px; cursor: pointer; display: flex;
  opacity: 0.5; color: var(--text-muted, #94a3b8);
}
.shape-remove:hover { opacity: 1; color: var(--err, #f87171); }
.shape-array-btn {
  flex: 0 0 auto; cursor: pointer; font-family: var(--font-mono, monospace); font-size: 12px; font-weight: 600;
  padding: 4px 7px; border-radius: 6px; letter-spacing: -1px;
  color: var(--text-muted, #94a3b8); background: none; border: 1px solid var(--line, rgba(148, 163, 184, 0.2));
}
.shape-array-btn:hover { color: var(--text, #e2e8f0); }
.shape-array-btn.is-array {
  color: var(--accent, #5ec5ff); border-color: color-mix(in oklch, var(--accent, #5ec5ff) 50%, transparent);
  background: color-mix(in oklch, var(--accent, #5ec5ff) 12%, transparent);
}
.shape-save { display: flex; align-items: center; gap: 6px; width: 100%; margin-top: 4px; padding-top: 8px; border-top: 1px solid var(--line, rgba(148,163,184,0.16)); }
.shape-save > :first-child { flex: 1; min-width: 0; }
</style>
