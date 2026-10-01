<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import tippy, { type Instance } from 'tippy.js'

withDefaults(defineProps<{
  content: string
  placement?: 'top' | 'bottom' | 'left' | 'right'
  delay?: number
}>(), {
  placement: 'top',
  delay: 200,
})

const triggerEl = ref<HTMLElement>()
let instance: Instance | null = null

onMounted(() => {
  if (!triggerEl.value) return
  instance = tippy(triggerEl.value, {
    content: triggerEl.value.dataset.tippyContent ?? '',
    placement: (triggerEl.value.dataset.tippyPlacement as any) ?? 'top',
    delay: [Number(triggerEl.value.dataset.tippyDelay ?? 200), 0],
    theme: 'bosca',
    arrow: false,
    animation: false,
  })
})

onBeforeUnmount(() => {
  instance?.destroy()
})
</script>

<template>
  <span
    ref="triggerEl"
    class="tooltip-trigger"
    :data-tippy-content="content"
    :data-tippy-placement="placement"
    :data-tippy-delay="delay"
  >
    <slot />
  </span>
</template>

<style scoped>
.tooltip-trigger {
  display: inline-flex;
}
</style>

<style>
.tippy-box[data-theme~='bosca'] {
  background: var(--bg-3);
  color: var(--fg-0);
  font-size: 11.5px;
  font-weight: 500;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--line-2);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}
</style>
