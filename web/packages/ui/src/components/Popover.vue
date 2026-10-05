<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import tippy, { type Instance } from 'tippy.js'

const props = withDefaults(defineProps<{
  placement?: 'top' | 'bottom' | 'left' | 'right' | 'bottom-start' | 'bottom-end'
  trigger?: 'click' | 'mouseenter'
  interactive?: boolean
  modelValue?: boolean
  /** Show delay in ms — useful for mouseenter popovers so passing the cursor through doesn't flash them. */
  delay?: number
}>(), {
  placement: 'bottom',
  trigger: 'click',
  interactive: true,
  delay: 0,
})

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const triggerEl = ref<HTMLElement>()
const contentEl = ref<HTMLElement>()
let instance: Instance | null = null

onMounted(() => {
  if (!triggerEl.value || !contentEl.value) return
  contentEl.value.classList.add('is-mounted')
  instance = tippy(triggerEl.value, {
    content: contentEl.value,
    placement: props.placement,
    trigger: props.trigger,
    interactive: props.interactive,
    delay: [props.delay, 0],
    theme: 'bosca-popover',
    arrow: false,
    animation: false,
    appendTo: () => document.body,
    onShow: () => emit('update:modelValue', true),
    onHide: () => emit('update:modelValue', false),
  })
})

watch(() => props.modelValue, (v) => {
  if (v) instance?.show()
  else instance?.hide()
})

onBeforeUnmount(() => {
  instance?.destroy()
})
</script>

<template>
  <span ref="triggerEl" class="popover-trigger">
    <slot name="trigger" />
  </span>
  <div ref="contentEl" class="popover-content">
    <slot />
  </div>
</template>

<style scoped>
.popover-trigger {
  display: inline-flex;
}

.popover-content {
  display: none;
}

.popover-content.is-mounted {
  display: block;
}
</style>

<style>
.tippy-box[data-theme~='bosca-popover'] {
  background: var(--bg-1);
  color: var(--fg-0);
  font-size: 13px;
  padding: 0;
  border-radius: var(--r-md);
  border: 1px solid var(--line);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
  max-width: 400px;
}

.tippy-box[data-theme~='bosca-popover'] .tippy-content {
  padding: 12px;
}
</style>
