<script setup lang="ts">
/**
 * Floating bulk-action banner and confirmation modals for collection listings.
 * Simpler than MetadataBulkActions — no visibility toggles since collections
 * don't have the same public/searchable flags.
 */
const props = defineProps<{
  actions: ReturnType<typeof useCollectionBulkActions>
  accent?: string
}>()

const a = computed(() => props.actions)
</script>

<template>
  <!-- Floating action banner -->
  <Transition name="slide-up">
    <div v-if="a.selectedCount.value > 0" class="bulk-banner">
      <span class="bulk-count">{{ a.selectedCount.value }} selected</span>
      <div class="bulk-divider" />
      <Button
        v-if="a.showPublish.value"
        size="sm"
        icon="globe"
        primary
        :accent="accent"
        :disabled="a.publishing.value"
        @click="a.publishOpen.value = true"
      >
        Publish
      </Button>
      <Button
        v-if="a.showUnpublish.value"
        size="sm"
        icon="archive"
        :disabled="a.unpublishing.value"
        @click="a.unpublishOpen.value = true">
        Unpublish
      </Button>
      <Button
        size="sm"
        icon="trash"
        :disabled="a.deleting.value"
        @click="a.deleteOpen.value = true">
        Delete
      </Button>
      <div class="bulk-divider" />
      <Button size="sm" icon="x" @click="a.clearSelection">Clear</Button>
    </div>
  </Transition>

  <!-- Publish modal -->
  <ConfirmModal
    v-if="a.publishOpen.value"
    :title="`Publish ${a.selectedCount.value} collection${a.selectedCount.value !== 1 ? 's' : ''}?`"
    subtitle="Collections already published will be skipped."
    confirm-label="Publish All"
    :loading="a.publishing.value"
    @close="a.publishOpen.value = false"
    @confirm="a.onPublish"
  />

  <!-- Unpublish modal -->
  <ConfirmModal
    v-if="a.unpublishOpen.value"
    :title="`Unpublish ${a.selectedCount.value} collection${a.selectedCount.value !== 1 ? 's' : ''}?`"
    subtitle="Collections not currently published will be skipped."
    confirm-label="Unpublish All"
    :loading="a.unpublishing.value"
    @close="a.unpublishOpen.value = false"
    @confirm="a.onUnpublish"
  />

  <!-- Set Ready modal -->
  <ConfirmModal
    v-if="a.readyOpen.value"
    :title="`Set ${a.selectedCount.value} collection${a.selectedCount.value !== 1 ? 's' : ''} as ready?`"
    subtitle="Collections already marked ready will be skipped."
    confirm-label="Set Ready"
    :loading="a.readying.value"
    @close="a.readyOpen.value = false"
    @confirm="a.onSetReady"
  />

  <!-- Delete modal -->
  <ConfirmModal
    v-if="a.deleteOpen.value"
    :title="`Delete ${a.selectedCount.value} collection${a.selectedCount.value !== 1 ? 's' : ''}?`"
    subtitle="This action cannot be undone."
    confirm-label="Delete All"
    :loading="a.deleting.value"
    @close="a.deleteOpen.value = false"
    @confirm="a.onDelete"
  />
</template>

<style scoped>
.bulk-banner {
  position: fixed;
  bottom: 22px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: var(--bg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(12px);
  z-index: 200;
  white-space: nowrap;
}

.bulk-count {
  font-size: 13px;
  font-weight: 550;
  color: var(--fg-1);
}

.bulk-divider {
  width: 1px;
  height: 16px;
  background: var(--line-2);
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(12px);
}
</style>
