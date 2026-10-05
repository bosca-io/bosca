<script setup lang="ts">
/**
 * A slot/port's label in the pipeline inspector. When the type (with any `[]` array suffixes stripped)
 * resolves to a catalogued object type, the label is a button that opens the Type Browser focused on it —
 * so the label *is* the browse affordance. Falls back to plain text otherwise. Array types like
 * `ProjectRepository[][]` are browsable via their element type.
 */
const props = defineProps<{
  type: string | null
  /** The text to show (a slot's typeLabel, a port's name, or a coarse kind label). */
  label: string
}>()

const { canBrowse, browse } = usePipelineTypeBrowser()
</script>

<template>
  <button
    v-if="canBrowse(type)"
    type="button"
    class="ti ti-link"
    :title="`Browse ${label} in the Type Browser`"
    @click="browse(type)"
  >{{ label }}</button>
  <span
    v-else
    class="ti"
  >{{ label }}</span>
</template>

<style scoped>
/* Matches the inspector's .expects-label so it reads as the slot's label. */
.ti { font-size: 12px; font-weight: 600; color: var(--text, #e2e8f0); }
.ti-link {
  background: none; border: none; padding: 0; cursor: pointer;
  border-bottom: 1px dotted var(--text-muted, #94a3b8);
}
.ti-link:hover { color: var(--accent, #f59e0b); border-bottom-color: currentColor; }
</style>
