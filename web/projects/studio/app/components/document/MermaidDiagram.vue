<script setup lang="ts">
import mermaid from 'mermaid'
import elkLayout from '@mermaid-js/layout-elk'

const props = defineProps<{
  content: string
}>()

const el = ref<HTMLElement | null>(null)
const renderError = ref('')
const renderedSvg = ref('')

let initialized = false

async function renderDiagram() {
  if (!props.content.trim()) return
  renderError.value = ''
  renderedSvg.value = ''

  if (!initialized) {
    await mermaid.registerLayoutLoaders(elkLayout)
    mermaid.initialize({
      startOnLoad: false,
      theme: 'neutral',
      securityLevel: 'strict',
      fontFamily: 'inherit',
      layout: 'elk',
    })
    initialized = true
  }

  try {
    const id = `mermaid-${Math.random().toString(36).slice(2, 10)}`
    const { svg } = await mermaid.render(id, props.content.trim())
    renderedSvg.value = svg
  } catch (e: unknown) {
    renderError.value = e instanceof Error ? e.message : 'Failed to render diagram'
  }
}

onMounted(() => {
  renderDiagram()
})

watch(() => props.content, () => {
  renderDiagram()
})
</script>

<template>
  <div class="mermaid-container">
    <div v-if="renderError" class="mermaid-error">
      {{ renderError }}
    </div>
    <!-- eslint-disable vue/no-v-html -- mermaid renders sanitized SVG -->
    <div
      v-else-if="renderedSvg"
      ref="el"
      class="mermaid-diagram"
      v-html="renderedSvg" />
    <!-- eslint-enable vue/no-v-html -->
  </div>
</template>

<style scoped>
.mermaid-container {
  width: 100%;
  overflow-x: auto;
}

.mermaid-diagram {
  display: flex;
  justify-content: center;
  padding: 16px;
}

.mermaid-diagram :deep(svg) {
  max-width: 100%;
  height: auto;
}

.mermaid-error {
  padding: 12px;
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--err) 10%, transparent);
  color: var(--err);
  font-size: 12.5px;
  font-family: monospace;
  white-space: pre-wrap;
}
</style>
