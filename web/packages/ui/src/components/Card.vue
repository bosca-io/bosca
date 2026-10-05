<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  padded?: boolean
  glass?: boolean
}>(), {
  padded: true,
  glass: false,
})
</script>

<template>
  <div class="card" :class="{ 'card--glass': glass }">
    <div class="card-header">
      <h3 class="card-title">{{ title }}</h3>
      <div class="card-spacer" />
      <slot name="right" />
    </div>
    <div class="card-body" :style="{ padding: padded ? '14px' : '0' }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.card-header {
  padding: 11px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--line);
}

.card-title {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.card-spacer {
  flex: 1;
}

.card-body {
  flex: 1;
  min-height: 0;
}

.card--glass {
  background: rgba(20, 24, 34, 0.38);
  border-color: color-mix(in oklch, var(--line) 57%, transparent);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.card--glass .card-header {
  border-bottom-color: color-mix(in oklch, var(--line) 42%, transparent);
}
</style>
