<script setup lang="ts">
import type { NodeFieldMeta } from '~/components/pipelines/pipelineNodeTypes'

/**
 * One row in the Type Browser's field tree. A field whose type is itself an object (or a list of objects)
 * carries nested `fields`; this component shows a disclosure toggle and, when open, recursively renders its
 * children — so nested types expand in place. Recursion relies on Nuxt's global component auto-import.
 */
const props = defineProps<{ field: NodeFieldMeta, depth: number }>()
const open = ref(false)
const expandable = computed(() => !!props.field.fields?.length)

function toggle() {
  if (expandable.value) open.value = !open.value
}
</script>

<template>
  <div class="tf">
    <div
      class="tf-row"
      :class="{ 'is-expandable': expandable }"
      :style="{ paddingLeft: `${depth * 16}px` }"
      @click="toggle"
    >
      <span class="tf-caret">{{ expandable ? (open ? '▾' : '▸') : '' }}</span>
      <code class="tf-name">{{ field.name }}</code>
      <span class="tf-type">{{ field.type }}</span>
    </div>
    <template v-if="open && field.fields">
      <PipelineTypeField
        v-for="c in field.fields"
        :key="c.name"
        :field="c"
        :depth="depth + 1"
      />
    </template>
  </div>
</template>

<style scoped>
.tf-row {
  display: flex; align-items: baseline; gap: 8px;
  padding: 7px 4px; border-bottom: 1px solid color-mix(in oklch, var(--line) 55%, transparent);
}
.tf-row.is-expandable { cursor: pointer; }
.tf-row.is-expandable:hover { background: var(--bg-2); }
.tf-caret { flex: 0 0 12px; font-size: 9px; color: var(--fg-3); line-height: 1.7; }
.tf-name { font-size: 13px; color: var(--fg-0); }
.tf-type { font-size: 12px; color: var(--fg-3); margin-left: auto; flex: 0 0 auto; }
</style>
