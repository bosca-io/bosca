<script setup lang="ts">
import { computed } from 'vue'
import type { K8sEvent } from '~/composables/useK8sTypes'

const props = defineProps<{ event: K8sEvent }>()

const color = computed(() => {
  if (props.event.lvl === 'error') return 'var(--err, #ff5d6c)'
  if (props.event.lvl === 'warn') return 'var(--warn, #ffb547)'
  return 'var(--info, #5ec5ff)'
})
const iconName = computed(() => props.event.lvl === 'info' ? 'info' : 'alert')
const objName = computed(() => {
  const parts = props.event.obj.split('/')
  return parts.length > 1 ? parts[1] : props.event.obj
})
</script>

<template>
  <div class="event">
    <span class="lvl" :style="{ color }"><Icon :name="iconName" :size="13" /></span>
    <span class="when">{{ event.when }}</span>
    <div class="body">
      <div class="mono dim">{{ event.ns }}/{{ objName }}</div>
      <div class="msg">{{ event.msg }}</div>
    </div>
  </div>
</template>

<style scoped>
.event {
  display: grid;
  grid-template-columns: 18px 70px 1fr;
  gap: 8px;
  align-items: start;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}
.event:last-child { border-bottom: none; }
.lvl { display: grid; place-items: center; padding-top: 2px; }
.when {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11.5px;
  color: var(--fg-3, #9b9ba5);
  padding-top: 2px;
}
.body { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 11.5px; }
.dim { color: var(--fg-3, #9b9ba5); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.msg {
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
