<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{
  name: string
  size?: number
}>(), {
  size: 36,
})

const palette = ['#ff9b5c', '#9d7cff', '#5ec5ff', '#34d99a', '#ffb547', '#ff7ac6', '#5f8cff', '#ffa14a']

const seed = computed(() => [...props.name].reduce((a, c) => a + c.charCodeAt(0), 0))
const color = computed(() => palette[seed.value % palette.length])
const shape = computed(() => seed.value % 4)

const initials = computed(() =>
  props.name.split(' ').map(w => w[0]).slice(0, 2).join('').toUpperCase()
)
</script>

<template>
  <div
    class="org-mark"
    :style="{
      width: `${props.size}px`,
      height: `${props.size}px`,
      background: `linear-gradient(135deg, ${color}, color-mix(in oklch, ${color} 60%, #fff))`,
      fontSize: `${props.size * 0.4}px`,
      boxShadow: `0 4px 12px -4px color-mix(in oklch, ${color} 40%, transparent)`,
    }"
  >
    <template v-if="shape === 1">
      <svg viewBox="0 0 24 24" :width="props.size * 0.55" :height="props.size * 0.55" fill="none" stroke="#fff" stroke-width="2.2">
        <path d="M4 20 L4 8 L12 4 L20 8 L20 20 z M9 13 h2 M13 13 h2 M9 17 h6" />
      </svg>
    </template>
    <template v-else-if="shape === 2">
      <svg viewBox="0 0 24 24" :width="props.size * 0.55" :height="props.size * 0.55" fill="none" stroke="#fff" stroke-width="2.2">
        <circle cx="12" cy="12" r="7" /><path d="M5 12 h14 M12 5 a 12 7 0 0 1 0 14 a 12 7 0 0 1 0 -14" />
      </svg>
    </template>
    <template v-else>{{ initials }}</template>
  </div>
</template>

<style scoped>
.org-mark {
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
</style>
