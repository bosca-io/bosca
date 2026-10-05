<script setup lang="ts">
/**
 * The JSONata expression editor used by a CODE setting whose language is "jsonata": an inline code
 * editor plus an Expand workbench — a wide two-pane modal that previews the expression against an
 * editable sample input. Extracted verbatim from the old per-kind inspector so the live preview
 * survives the move to a data-driven settings form. CodeMirror has no JSONata mode, so it highlights
 * the expression as JavaScript (which JSONata reads cleanly).
 */
const props = defineProps<{
  modelValue: string
  label?: string
  placeholder?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const workbenchOpen = ref(false)
const sample = ref('{\n  "profile": { "name": "Ada" },\n  "attributes": []\n}')
const preview = ref('')
const previewError = ref('')

async function runPreview() {
  previewError.value = ''
  preview.value = ''
  try {
    const jsonata = (await import('jsonata')).default
    const input = JSON.parse(sample.value)
    const result = await jsonata(props.modelValue).evaluate(input)
    preview.value = JSON.stringify(result, null, 2) ?? 'undefined'
  }
  catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="jsonata-field">
    <CodeEditor
      :model-value="modelValue"
      :label="label ?? 'JSONata expression'"
      language="javascript"
      :rows="8"
      :placeholder="placeholder ?? 'profile.email'"
      @update:model-value="(v: string) => emit('update:modelValue', v)"
    />
    <div class="workbench-open">
      <Button
        size="xs"
        icon="maximize"
        title="Open the wide editor with a sample-input preview"
        @click="workbenchOpen = true"
      >
        Expand
      </Button>
    </div>

    <Modal
      v-if="workbenchOpen"
      title="JSONata"
      subtitle="Shape the inbound JSON — preview runs the expression against the sample"
      icon="workflow"
      width="min(94vw, 1600px)"
      @close="workbenchOpen = false"
    >
      <div class="workbench">
        <CodeEditor
          :model-value="modelValue"
          label="Expression"
          language="javascript"
          height="56vh"
          placeholder="profile.email"
          @update:model-value="(v: string) => emit('update:modelValue', v)"
        />
        <CodeEditor
          :model-value="sample"
          label="Sample input (JSON)"
          language="json"
          height="56vh"
          @update:model-value="(v: string) => sample = v"
        />
        <div class="workbench-actions">
          <Button
            size="xs"
            icon="play"
            @click="runPreview"
          >
            Preview
          </Button>
          <p
            v-if="previewError"
            class="error"
          >
            {{ previewError }}
          </p>
        </div>
        <pre
          v-if="preview"
          class="preview workbench-preview"
        >{{ preview }}</pre>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.jsonata-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.workbench-open {
  display: flex;
  justify-content: flex-end;
  margin-top: -2px;
}
.workbench {
  display: grid;
  /* minmax(0,…) + min-width:0 so an unwrapped CodeMirror line can't blow the column out
     (it scrolls inside the editor instead of pushing the sample column off-screen). */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}
.workbench > * {
  min-width: 0;
}
.workbench-actions {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 12px;
}
.workbench-preview {
  grid-column: 1 / -1;
  max-height: 22vh;
}
.preview {
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.6);
  font-size: 11px;
  max-height: 160px;
  overflow: auto;
}
.error {
  margin: 0;
  font-size: 11px;
  color: var(--danger, #f87171);
}
</style>
