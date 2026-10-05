<script setup lang="ts">
defineProps<{
  type?: 'info' | 'warn' | 'tip'
}>()
</script>

<template>
  <div
    class="callout"
    :class="type || 'info'"
  >
    <div class="callout-icon">
      <Icon
        :name="type === 'warn' ? 'alert' : type === 'tip' ? 'checkCircle' : 'info'"
        :size="16"
        :color="type === 'warn' ? 'var(--warn)' : type === 'tip' ? 'var(--ok)' : 'var(--info)'"
      />
    </div>
    <div>
      <slot />
    </div>
  </div>
</template>
