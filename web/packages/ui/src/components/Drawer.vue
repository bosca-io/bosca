<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'

/**
 * Right-side slide-out panel with backdrop. Mirrors Modal's slot shape but
 * presents content as a sheet anchored to the right edge — better for
 * resource-detail surfaces where the underlying list should stay visible.
 * Teleports to body. Closes on backdrop click, close button, or Escape.
 *
 * Visibility is controlled by the caller via v-if (same pattern as Modal).
 */
withDefaults(defineProps<{
  title: string
  subtitle?: string
  icon?: string
  accent?: string
  /** Panel width. Any CSS length; clamped to viewport. Defaults to 560px. */
  width?: string
}>(), {
  accent: 'var(--brand-2)',
  width: '560px',
})

const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => document.addEventListener('keydown', onKey))
onUnmounted(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div class="drawer-backdrop" @click="emit('close')">
      <div
        class="drawer-panel"
        role="dialog"
        aria-modal="true"
        :style="{ width: `min(${width}, 100%)` }"
        @click.stop
      >
        <header class="drawer-header">
          <span
            v-if="icon"
            class="drawer-icon"
            :style="{
              background: `color-mix(in oklch, ${accent} 16%, var(--bg-2))`,
              border: `1px solid color-mix(in oklch, ${accent} 28%, transparent)`,
            }"
          >
            <Icon :name="icon" :size="14" :color="accent" />
          </span>
          <div class="drawer-header-text">
            <div class="drawer-title"><slot name="title">{{ title }}</slot></div>
            <div v-if="subtitle || $slots.subtitle" class="drawer-subtitle">
              <slot name="subtitle">{{ subtitle }}</slot>
            </div>
          </div>
          <div v-if="$slots.actions" class="drawer-actions">
            <slot name="actions" />
          </div>
          <button class="drawer-close" aria-label="Close" @click="emit('close')">
            <Icon name="x" :size="14" color="var(--fg-3)" />
          </button>
        </header>

        <div class="drawer-body">
          <slot />
        </div>

        <footer v-if="$slots.footer" class="drawer-footer">
          <slot name="footer" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: color-mix(in oklch, #000 45%, transparent);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
  display: flex;
  justify-content: flex-end;
}

.drawer-panel {
  height: 100vh;
  background: var(--bg-1);
  border-left: 1px solid var(--line);
  box-shadow: -16px 0 40px rgba(0, 0, 0, 0.35);
  display: flex;
  flex-direction: column;
  min-width: 0;
  animation: drawer-slide 0.22s cubic-bezier(0.2, 0.8, 0.2, 1);
}

@keyframes drawer-slide {
  from { transform: translateX(40px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

.drawer-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  flex: 0 0 auto;
}
.drawer-icon {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.drawer-header-text { flex: 1; min-width: 0; }
.drawer-title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.005em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-subtitle {
  font-size: 12px;
  color: var(--fg-2);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-actions { display: flex; align-items: center; gap: 6px; }
.drawer-close {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
}
.drawer-close:hover { background: var(--bg-2); }

.drawer-body {
  flex: 1 1 auto;
  overflow: auto;
  padding: 16px 18px;
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
  flex: 0 0 auto;
  background: var(--bg-1);
}
</style>
