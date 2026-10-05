<script setup lang="ts">
import { computed } from 'vue'
import Badge from './Badge.vue'

const props = defineProps<{
  status: string
}>()

const TASK_STATUS: Record<string, [string, string]> = {
  open: ['Open', '#5ec5ff'],
  inprog: ['In Progress', '#a78bff'],
  review: ['Review', '#ffb547'],
  blocked: ['Blocked', '#ff5d6c'],
  done: ['Done', '#34d99a'],
}

const statusInfo = computed<[string, string]>(
  () => TASK_STATUS[props.status] ?? [props.status, 'var(--fg-3)'],
)
</script>

<template>
  <Badge :color="statusInfo[1]">
    {{ statusInfo[0] }}
  </Badge>
</template>
