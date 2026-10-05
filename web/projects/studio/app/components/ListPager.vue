<script setup lang="ts">
/** Offset/limit pager: shows the current range and Prev/Next, paired with usePagedList. */
const props = defineProps<{ offset: number; pageSize: number; count: number; hasMore: boolean }>()
const emit = defineEmits<{ 'update:offset': [number] }>()

const from = computed(() => (props.count === 0 ? 0 : props.offset + 1))
const to = computed(() => props.offset + props.count)

function prev() { if (props.offset > 0) emit('update:offset', Math.max(0, props.offset - props.pageSize)) }
function next() { if (props.hasMore) emit('update:offset', props.offset + props.pageSize) }
</script>

<template>
  <div v-if="offset > 0 || hasMore" class="pager">
    <span class="range">{{ from }}–{{ to }}</span>
    <Button
      size="sm"
      icon="chevron-left"
      :disabled="offset === 0"
      @click="prev">Prev</Button>
    <Button
      size="sm"
      icon="chevron-right"
      :disabled="!hasMore"
      @click="next">Next</Button>
  </div>
</template>

<style scoped>
.pager { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 12px; }
.range { font-size: 12px; color: var(--fg-3); }
</style>
