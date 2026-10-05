<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  label: string
  ok?: boolean
  warn?: boolean
  err?: boolean
  // Neutral state: the check is neither passing nor failing — the thing
  // it describes isn't observable (e.g. a provider-managed control plane
  // whose nodes aren't exposed). Rendered muted, never as a warning.
  info?: boolean
  note?: string
}>()

const tone = computed(() => props.err ? 'err' : props.warn ? 'warn' : props.info ? 'info' : 'ok')
const noteText = computed(() => props.note ?? (props.err ? 'Issue' : props.warn ? 'Warning' : props.info ? 'Not observed' : 'Operational'))
const iconName = computed(() => (props.warn || props.err) ? 'alert' : props.info ? 'info' : 'check')
const color = computed(() => tone.value === 'err' ? 'var(--err, #ff5d6c)' : tone.value === 'warn' ? 'var(--warn, #ffb547)' : tone.value === 'info' ? 'var(--fg-3, #9b9ba5)' : 'var(--ok, #34d99a)')
</script>

<template>
  <div class="row">
    <div class="left">
      <span class="ico" :style="{ color }"><Icon :name="iconName" :size="13" /></span>
      <span class="label">{{ label }}</span>
    </div>
    <span class="note">{{ noteText }}</span>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 0;
  font-size: 12.5px;
}
.left { display: flex; align-items: center; gap: 8px; }
.ico { display: grid; place-items: center; }
.note { color: var(--fg-3, #9b9ba5); font-size: 11.5px; }
</style>
