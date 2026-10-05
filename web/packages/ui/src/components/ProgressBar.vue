<script setup lang="ts">
const props = withDefaults(defineProps<{
  value: number
  accent?: string
  height?: number
  sub?: string
}>(), {
  accent: 'var(--brand-accent)',
  height: 6,
})
</script>

<template>
  <div class="bar-wrapper">
    <div class="bar-track" :style="{ height: `${props.height}px` }">
      <div
        class="bar-fill"
        :style="{
          width: `${Math.max(0, Math.min(100, props.value))}%`,
          background: props.accent,
        }"
      />
    </div>
    <span v-if="props.sub" class="bar-sub mono tabular">{{ props.sub }}</span>
  </div>
</template>

<style scoped>
.bar-wrapper {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 90px;
}

.bar-track {
  border-radius: 999px;
  background: var(--bg-2);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 999px;
}

.bar-sub {
  font-size: 10.5px;
  color: var(--fg-3);
}
</style>
