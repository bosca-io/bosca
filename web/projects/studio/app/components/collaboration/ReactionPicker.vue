<script lang="ts" setup>
const QUICK_EMOJIS = ['👍', '❤️', '😂', '🎉', '🚀', '👀', '🙏', '✅', '🔥', '🤔', '😢', '👏']

defineProps<{
  x: number
  y: number
}>()

const emit = defineEmits<{
  select: [emoji: string]
  close: []
}>()

const pickerRef = ref<HTMLElement | null>(null)

function onClickOutside(e: MouseEvent) {
  if (pickerRef.value && !pickerRef.value.contains(e.target as Node)) {
    emit('close')
  }
}

onMounted(() => {
  setTimeout(() => document.addEventListener('mousedown', onClickOutside), 0)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', onClickOutside)
})
</script>

<template>
  <Teleport to="body">
    <div
      ref="pickerRef"
      class="reaction-picker"
      :style="{ left: x + 'px', top: y + 'px' }"
    >
      <button
        v-for="emoji in QUICK_EMOJIS"
        :key="emoji"
        class="emoji-btn"
        @click="emit('select', emoji)"
      >
        {{ emoji }}
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.reaction-picker {
  position: fixed;
  z-index: 10000;
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 1px;
  padding: 4px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
}

.emoji-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  background: none;
  border: none;
  border-radius: var(--r-xs);
  cursor: pointer;
  transition: background 0.1s, transform 0.1s;
}

.emoji-btn:hover {
  background: var(--bg-3);
  transform: scale(1.15);
}
</style>
