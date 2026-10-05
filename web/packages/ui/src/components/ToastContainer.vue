<script setup lang="ts">
import Icon from './Icon.vue'
import { useToast } from '../composables/useToast'

const { toasts, dismiss } = useToast()

const TONE_ICON: Record<string, string> = {
  info: 'info',
  ok: 'checkCircle',
  warn: 'alert',
  err: 'x',
}

const TONE_COLOR: Record<string, string> = {
  info: 'var(--info)',
  ok: 'var(--ok)',
  warn: 'var(--warn)',
  err: 'var(--err)',
}
</script>

<template>
  <Teleport to="body">
    <TransitionGroup name="toast" tag="div" class="toast-container">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :style="{ borderColor: `color-mix(in oklch, ${TONE_COLOR[t.tone]} 40%, transparent)` }"
      >
        <span class="toast-icon">
          <Icon :name="TONE_ICON[t.tone]" :size="13" :color="TONE_COLOR[t.tone]" />
        </span>
        <div class="toast-body">
          <span class="toast-message">{{ t.message }}</span>
          <div v-if="t.progress !== undefined" class="toast-progress-track">
            <div
              class="toast-progress-bar"
              :style="{
                width: `${t.progress}%`,
                background: TONE_COLOR[t.tone],
              }"
            />
          </div>
        </div>
        <button class="toast-close" @click="dismiss(t.id)">
          <Icon name="x" :size="12" color="var(--fg-3)" />
        </button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<style scoped>
.toast-container {
  position: fixed;
  top: 75vh;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  box-shadow: 0 12px 40px -10px rgba(0, 0, 0, 0.45);
  min-width: 240px;
  max-width: 440px;
}

.toast-icon {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.toast-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.toast-message {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
  line-height: 1.35;
}

.toast-progress-track {
  width: 100%;
  height: 3px;
  border-radius: 2px;
  background: var(--bg-3);
  overflow: hidden;
}

.toast-progress-bar {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.toast-close {
  flex: 0 0 auto;
  padding: 4px;
  opacity: 0.5;
  cursor: pointer;
}

.toast-close:hover {
  opacity: 1;
}

.toast-enter-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.toast-leave-active {
  transition: all 0.2s ease-in;
}

.toast-enter-from {
  opacity: 0;
  transform: translateY(12px) scale(0.95);
}

.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.95);
}

.toast-move {
  transition: transform 0.25s ease;
}
</style>
