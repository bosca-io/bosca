<script setup lang="ts">
/**
 * Renders a row of small icons indicating visibility flags for a metadata
 * or collection item. Each active flag gets an icon with a tooltip.
 * Matches the administration site's VisibilityCell pattern.
 */
defineProps<{
  locked?: boolean
  public?: boolean
  publicContent?: boolean
  publicSupplementary?: boolean
  searchable?: boolean
  recommendable?: boolean
}>()
</script>

<template>
  <div v-if="locked || public || publicContent || publicSupplementary || searchable || recommendable" class="vis-cell">
    <Tooltip v-if="locked" content="Locked">
      <Icon name="lock" :size="14" color="var(--warn)" />
    </Tooltip>
    <Tooltip v-if="public" content="Public">
      <Icon name="globe" :size="14" color="var(--fg-3)" />
    </Tooltip>
    <Tooltip v-if="publicContent" content="Public Content">
      <Icon name="file" :size="14" color="var(--fg-3)" />
    </Tooltip>
    <Tooltip v-if="publicSupplementary" content="Public Supplementary">
      <Icon name="link" :size="14" color="var(--fg-3)" />
    </Tooltip>
    <Tooltip v-if="searchable" content="Searchable">
      <Icon name="search" :size="14" color="var(--fg-3)" />
    </Tooltip>
    <Tooltip v-if="recommendable" content="Recommendable">
      <Icon name="sparkles" :size="14" color="var(--fg-3)" />
    </Tooltip>
  </div>
  <span v-else class="vis-empty">--</span>
</template>

<style scoped>
.vis-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.vis-empty {
  color: var(--fg-4);
}
</style>
