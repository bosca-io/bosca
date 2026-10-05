<script setup lang="ts">
import { useSlots } from 'vue'

withDefaults(defineProps<{
  /** Header title text. Ignored when the `#header` slot is provided. */
  title?: string
  subtitle?: string
  glass?: boolean
  /** Wrap the default slot in a padded body container. Pages can opt in for free-form content; tables that paint their own padding should leave it off. */
  padded?: boolean
}>(), {
  glass: false,
  padded: false,
})

const slots = useSlots()
const hasHeaderSlot = !!slots.header
</script>

<template>
  <div class="section-card" :class="{ 'section-card--glass': glass }">
    <div v-if="hasHeaderSlot" class="section-header section-header--slot">
      <slot name="header" />
    </div>
    <div v-else-if="title || subtitle || $slots.right" class="section-header">
      <span v-if="title" class="section-title">{{ title }}</span>
      <span v-if="subtitle" class="section-subtitle">{{ subtitle }}</span>
      <span class="section-spacer" />
      <slot name="right" />
    </div>
    <div v-if="padded" class="section-body"><slot /></div>
    <slot v-else />
  </div>
</template>

<style scoped>
.section-card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
}

.section-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
}

.section-header--slot { gap: 10px; }

.section-body {
  padding: 16px 18px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
}

.section-subtitle {
  font-size: 12px;
  color: var(--fg-2);
  margin-left: 12px;
}

.section-spacer {
  flex: 1;
}

.section-card--glass {
  background: color-mix(in oklch, var(--bg-1) 85%, transparent);
  border-color: color-mix(in oklch, var(--line) 57%, transparent);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.section-card--glass .section-header {
  border-bottom-color: color-mix(in oklch, var(--line) 42%, transparent);
}
</style>
