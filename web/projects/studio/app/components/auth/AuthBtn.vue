<script setup lang="ts">
withDefaults(defineProps<{
  primary?: boolean
  secondary?: boolean
  loading?: boolean
  disabled?: boolean
  full?: boolean
  icon?: string
  accent?: string
}>(), {
  icon: undefined,
  accent: '#7c5cff',
})

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    class="auth-btn"
    :class="{ primary, secondary, full }"
    :disabled="disabled || loading"
    :style="primary ? {
      background: `linear-gradient(180deg, color-mix(in oklch, ${accent} 88%, #000), color-mix(in oklch, ${accent} 70%, #000))`,
      border: `1px solid color-mix(in oklch, ${accent} 60%, #000)`,
      boxShadow: `0 1px 0 color-mix(in oklch, ${accent} 30%, transparent) inset, 0 2px 6px -3px color-mix(in oklch, ${accent} 50%, transparent)`,
    } : undefined"
    @click="emit('click')"
  >
    <span v-if="loading" class="auth-spinner">
      <Icon name="spinner" :size="14" color="currentColor" />
    </span>
    <Icon
      v-else-if="icon"
      :name="icon"
      :size="14"
      :color="primary ? '#fff' : 'var(--fg-2)'" />
    <slot />
  </button>
</template>

<style scoped>
.auth-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 40px;
  padding: 0 16px;
  font-size: 13.5px;
  font-weight: 550;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  background: rgba(255, 255, 255, 0.04);
  color: var(--fg-1);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.auth-btn:disabled { opacity: 0.55; cursor: default; }
.auth-btn.full { width: 100%; }
.auth-btn.primary { color: #fff; }
.auth-btn.secondary {
  background: transparent;
  border: none;
}

@keyframes auth-spin { to { transform: rotate(360deg); } }
.auth-spinner {
  display: inline-flex;
  animation: auth-spin 0.9s linear infinite;
}
</style>
