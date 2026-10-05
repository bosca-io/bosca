<script setup lang="ts">
import Icon from './Icon.vue'
/**
 * Reusable modal shell with backdrop, header (icon + title + subtitle + close),
 * body, and footer. Teleports to body. Closes on backdrop click or close button.
 */
withDefaults(defineProps<{
  title: string
  subtitle?: string
  icon?: string
  accent?: string
  width?: string
}>(), {
  accent: '#5ec5ff',
  width: '520px',
})

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')">
      <div class="modal-box" :style="{ width: `min(${width}, 100%)` }" @click.stop>
        <div class="modal-header">
          <span
            v-if="icon"
            class="modal-icon"
            :style="{
              background: `color-mix(in oklch, ${accent} 16%, var(--bg-2))`,
              border: `1px solid color-mix(in oklch, ${accent} 28%, transparent)`,
            }"
          >
            <Icon :name="icon" :size="14" :color="accent" />
          </span>
          <div class="modal-header-text">
            <div class="modal-title">{{ title }}</div>
            <div v-if="subtitle" class="modal-subtitle">{{ subtitle }}</div>
          </div>
          <button class="modal-close" @click="emit('close')">
            <Icon name="x" :size="14" color="var(--fg-3)" />
          </button>
        </div>

        <div class="modal-body">
          <slot />
        </div>

        <div v-if="$slots.footer" class="modal-footer">
          <slot name="footer" />
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
  max-height: 90vh;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;

}

.modal-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.modal-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-header-text { flex: 1; }
.modal-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.modal-subtitle { font-size: 11.5px; color: var(--fg-3); }
.modal-close { color: var(--fg-3); padding: 6px; }

.modal-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.modal-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
