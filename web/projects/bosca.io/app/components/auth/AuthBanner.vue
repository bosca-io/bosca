<script setup lang="ts">
withDefaults(defineProps<{
  tone?: 'info' | 'warn' | 'err' | 'ok'
}>(), {
  tone: 'info'
})

const toneMap = {
  info: { bg: 'rgba(94, 197, 255, 0.08)', border: 'color-mix(in oklch, #5ec5ff 35%, transparent)', icon: '#5ec5ff', iconName: 'info' },
  warn: { bg: 'rgba(255, 181, 71, 0.08)', border: 'color-mix(in oklch, #ffb547 35%, transparent)', icon: '#ffb547', iconName: 'alert' },
  err: { bg: 'rgba(255, 84, 112, 0.08)', border: 'color-mix(in oklch, #ff5470 40%, transparent)', icon: '#ff5470', iconName: 'alert' },
  ok: { bg: 'rgba(52, 217, 154, 0.08)', border: 'color-mix(in oklch, #34d99a 35%, transparent)', icon: '#34d99a', iconName: 'info' }
}
</script>

<template>
  <div
    class="auth-banner"
    :style="{
      background: toneMap[tone].bg,
      borderColor: toneMap[tone].border
    }"
  >
    <Icon
      :name="toneMap[tone].iconName"
      :size="14"
      :color="toneMap[tone].icon"
    />
    <span class="auth-banner-text"><slot /></span>
  </div>
</template>

<style scoped>
.auth-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid;
  border-radius: 10px;
  font-size: 12.5px;
  color: var(--fg-1);
  line-height: 1.45;
}
.auth-banner-text { flex: 1; }
</style>
