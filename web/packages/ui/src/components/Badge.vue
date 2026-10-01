<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{
  color?: string
  solid?: boolean
}>(), {
  solid: false,
})

const dynamicStyle = computed(() => ({
  background: props.solid
    ? props.color
    : `color-mix(in srgb, ${props.color} 14%, transparent)`,
  color: props.solid ? '#fff' : props.color,
  border: `1px solid ${props.solid ? props.color : `color-mix(in srgb, ${props.color} 30%, transparent)`}`,
}))
</script>

<template>
  <span class="pill" :style="dynamicStyle">
    <slot />
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 530;
  line-height: 1.4;
}
</style>
