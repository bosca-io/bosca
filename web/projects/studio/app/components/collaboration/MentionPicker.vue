<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps<{
  query: string
  members: Array<{ id: string; name: string; handle?: string }>
  visible: boolean
}>()

const emit = defineEmits<{
  select: [member: { id: string; name: string; handle?: string }]
  close: []
}>()

const highlightIndex = ref(0)

const filtered = computed(() => {
  const q = props.query.toLowerCase()
  if (!q) return props.members.slice(0, 8)
  return props.members
    .filter(m => m.name.toLowerCase().includes(q) || m.handle?.toLowerCase().includes(q))
    .slice(0, 8)
})

watch(() => props.query, () => { highlightIndex.value = 0 })

function onKeydown(e: KeyboardEvent) {
  if (!props.visible) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightIndex.value = Math.min(highlightIndex.value + 1, filtered.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightIndex.value = Math.max(highlightIndex.value - 1, 0)
  } else if (e.key === 'Enter' && filtered.value[highlightIndex.value]) {
    e.preventDefault()
    emit('select', filtered.value[highlightIndex.value]!)
  } else if (e.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

function initials(name: string): string {
  return name.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2)
}
</script>

<template>
  <div v-if="visible && filtered.length" class="mention-picker">
    <button
      v-for="(m, i) in filtered"
      :key="m.id"
      class="mention-item"
      :class="{ highlighted: i === highlightIndex }"
      @mouseenter="highlightIndex = i"
      @click="emit('select', m)"
    >
      <span class="mention-avatar">{{ initials(m.name) }}</span>
      <span class="mention-copy">
        <span class="mention-name">{{ m.name }}</span>
        <span v-if="m.handle" class="mention-handle">@{{ m.handle }}</span>
      </span>
    </button>
  </div>
</template>

<style scoped>
.mention-picker {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  margin-bottom: 4px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  max-height: 200px;
  overflow: auto;
  z-index: 10;
}

.mention-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  text-align: left;
  transition: background 0.1s;
}

.mention-item.highlighted {
  background: color-mix(in oklch, var(--brand-2) 10%, transparent);
}

.mention-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--brand-2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.mention-name {
  font-size: 13px;
  color: var(--fg-0);
  font-weight: 500;
}

.mention-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.mention-handle {
  font-size: 11px;
  color: var(--fg-3);
}
</style>
