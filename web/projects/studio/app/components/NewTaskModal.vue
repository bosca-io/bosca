<script setup lang="ts">
const props = withDefaults(defineProps<{
  accent?: string
  schemaKey?: string
  projectId?: string
}>(), {
  accent: '#a78bff',
  schemaKey: 'workops.create-task',
  projectId: undefined,
})

const emit = defineEmits<{
  close: []
  created: [taskId: string]
}>()

const toast = useToast()

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- BoscaForm is auto-imported by Nuxt module
const formRef = ref<any>(null)

const initialData = computed(() => {
  const data: Record<string, unknown> = {}
  if (props.projectId) data.projectId = props.projectId
  return data
})

function onSubmitted(result: unknown) {
  const r = result as { id: string; type: string }
  toast.success('Task created')
  emit('created', r.id)
  emit('close')
}

function onValidate(errors: Record<string, string>) {
  if (Object.keys(errors).length) {
    toast.warn('Please fix the errors above')
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')">
      <div class="modal-box" @click.stop>
        <div class="modal-header">
          <span
            class="modal-icon"
            :style="{
              background: `color-mix(in oklch, ${accent} 16%, var(--bg-2))`,
              border: `1px solid color-mix(in oklch, ${accent} 28%, transparent)`,
            }">
            <Icon name="plus" :size="14" :color="accent" />
          </span>
          <div class="modal-header-text">
            <div class="modal-title">New task</div>
            <div class="modal-subtitle">Create a task via form schema</div>
          </div>
          <button class="modal-close" @click="emit('close')">
            <Icon name="x" :size="14" color="var(--fg-3)" />
          </button>
        </div>

        <div class="modal-body">
          <BoscaForm
            ref="formRef"
            :schema-key="schemaKey"
            :model-value="initialData"
            mode="submit"
            submit-label="Create task"
            @submitted="onSubmitted"
            @validate="onValidate"
          />
        </div>

        <div class="modal-footer">
          <span class="shortcut-hint">
            <Icon name="key" :size="11" color="var(--fg-3)" />
            <span class="mono">⌘ ↵</span> to create
          </span>
          <span class="spacer" />
          <Button size="sm" @click="emit('close')">Cancel</Button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: color-mix(in oklch, #000 55%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.modal-box {
  width: min(640px, 100%);
  max-height: 90vh;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.modal-icon {
  width: 28px; height: 28px; border-radius: var(--r-sm); flex: 0 0 28px;
  display: flex; align-items: center; justify-content: center;
}

.modal-header-text { flex: 1; }
.modal-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.modal-subtitle { font-size: 11.5px; color: var(--fg-3); }
.modal-close { color: var(--fg-3); padding: 6px; }

.modal-body {
  flex: 1;
  overflow: auto;
  padding: 18px;
}

.modal-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
  background: var(--bg-2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.shortcut-hint {
  font-size: 11.5px; color: var(--fg-3);
  display: inline-flex; align-items: center; gap: 5px;
}

.spacer { flex: 1; }
</style>
