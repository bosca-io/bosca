<script setup lang="ts">
import Icon from './Icon.vue'
import Button from './Button.vue'

/**
 * Destructive action confirmation dialog. Shows a warning message
 * with cancel and a red-bordered confirm button.
 */
withDefaults(defineProps<{
  title: string
  subtitle?: string
  confirmLabel?: string
  loading?: boolean
}>(), {
  subtitle: 'This cannot be undone.',
  confirmLabel: 'Delete',
  loading: false,
})

const emit = defineEmits<{
  close: []
  confirm: []
}>()
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')">
      <div class="confirm-box" @click.stop>
        <div class="confirm-header">
          <span class="confirm-icon">
            <Icon name="trash" :size="14" color="var(--err)" />
          </span>
          <div class="confirm-header-text">
            <div class="confirm-title">{{ title }}</div>
            <div class="confirm-subtitle">{{ subtitle }}</div>
          </div>
        </div>

        <div class="confirm-body">
          <slot />
        </div>

        <div class="confirm-footer">
          <span class="spacer" />
          <Button size="sm" @click="emit('close')">Cancel</Button>
          <button class="delete-btn" :disabled="loading" @click="emit('confirm')">
            {{ loading ? 'Deleting…' : confirmLabel }}
          </button>
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

.confirm-box {
  width: min(420px, 100%);
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.confirm-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.confirm-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in oklch, var(--err) 16%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 28%, transparent);
}

.confirm-header-text { flex: 1; }
.confirm-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.confirm-subtitle { font-size: 11.5px; color: var(--fg-3); }

.confirm-body { padding: 18px; }

.confirm-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer { flex: 1; }

.delete-btn {
  padding: 6px 12px;
  font-size: 12.5px;
  font-weight: 600;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--err);
  border: 1px solid color-mix(in oklch, var(--err) 50%, transparent);
  cursor: pointer;
}

.delete-btn:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }
.delete-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
