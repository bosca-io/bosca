<script setup lang="ts">
withDefaults(defineProps<{
  size?: number
  color?: string
}>(), {
  size: 26,
  color: 'currentColor',
})
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 512 512"
    fill="none"
    aria-label="Bosca"
    class="bosca-mark"
    :style="color ? { '--mark-top': color, '--mark-right': `color-mix(in oklch, ${color} 85%, #fff)`, '--mark-left': `color-mix(in oklch, ${color} 80%, #000)` } : undefined">
    <path d="M256 262L491 138L256 21L21 138L256 262Z" class="face-top" />
    <path d="M491 138L256 262V498L491 372V138Z" class="face-right" />
    <path d="M21 138L256 262V498L21 372V138Z" class="face-left" />
  </svg>
</template>

<style scoped>
.bosca-mark {
  --mark-top: #00c16a;
  --mark-right: #00dc82;
  --mark-left: #00a155;
}
.face-top { fill: var(--mark-top); }
.face-right { fill: var(--mark-right); }
.face-left { fill: var(--mark-left); }
</style>
