<script lang="ts" setup>
interface SlashCommand {
  command: string
  description: string
  args?: string
}

const COMMANDS: SlashCommand[] = [
  { command: '/kit summarize', description: 'Summarize recent conversation' },
  { command: '/kit translate', description: 'Translate last message', args: '<language>' },
  { command: '/kit search', description: 'Search Bosca content', args: '<query>' },
  { command: '/kit ask', description: 'Ask Kit with channel context', args: '<question>' },
]

const props = defineProps<{
  query: string
  visible: boolean
}>()

const emit = defineEmits<{
  select: [command: string]
  close: []
}>()

const highlightIndex = ref(0)

const filtered = computed(() => {
  const q = props.query.toLowerCase()
  if (!q) return COMMANDS
  return COMMANDS.filter(c => c.command.toLowerCase().includes(q))
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
    emit('select', filtered.value[highlightIndex.value]!.command)
  } else if (e.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="visible && filtered.length" class="slash-picker">
    <button
      v-for="(cmd, i) in filtered"
      :key="cmd.command"
      class="slash-item"
      :class="{ highlighted: i === highlightIndex }"
      @mouseenter="highlightIndex = i"
      @click="emit('select', cmd.command)"
    >
      <span class="slash-cmd">{{ cmd.command }}</span>
      <span v-if="cmd.args" class="slash-args">{{ cmd.args }}</span>
      <span class="slash-desc">{{ cmd.description }}</span>
    </button>
  </div>
</template>

<style scoped>
.slash-picker {
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

.slash-item {
  width: 100%;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 12px;
  text-align: left;
  transition: background 0.1s;
}

.slash-item.highlighted {
  background: color-mix(in oklch, var(--brand-2) 10%, transparent);
}

.slash-cmd {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-2);
  font-family: var(--font-mono, monospace);
}

.slash-args {
  font-size: 12px;
  color: var(--fg-3);
  font-family: var(--font-mono, monospace);
}

.slash-desc {
  font-size: 12px;
  color: var(--fg-3);
  margin-left: auto;
}
</style>
