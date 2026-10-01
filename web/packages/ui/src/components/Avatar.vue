<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{
  name: string
  size?: number
  idx?: number
}>(), {
  size: 22,
  idx: 0,
})

const palette = [
  '#ff9b5c',
  '#9d7cff',
  '#5ec5ff',
  '#34d99a',
  '#ffb547',
  '#ff7ac6',
  '#06b6d4',
  '#ff5d6c',
]

const bg = computed(() => palette[props.idx % palette.length])

const initials = computed(() => {
  if (!props.name) return '?'
  return props.name
    .split(' ')
    .map((part) => part[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
})

const fontSize = computed(() => Math.round(props.size * 0.42))
</script>

<template>
  <div
    class="avatar"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      background: bg,
      fontSize: `${fontSize}px`,
    }"
  >
    {{ initials }}
  </div>
</template>

<style scoped>
.avatar {
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
  user-select: none;
}
</style>
