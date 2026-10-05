<script setup lang="ts">
/**
 * Floating bulk-action banner and confirmation modals for metadata listings.
 * Renders a slide-up bar when items are selected, with buttons that open
 * confirmation dialogs matching the administration site's UX.
 */
const props = defineProps<{
  actions: ReturnType<typeof useMetadataBulkActions>
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
        v-if="a.showReady.value"
        size="sm"
        icon="check"
        :disabled="a.readying.value"
        @click="a.readyOpen.value = true">
        Ready
      </Button>
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
        icon="globe"
        :disabled="a.publicUpdating.value"
        @click="a.publicOpen.value = true">
        Visibility
      </Button>
      <Button
        v-if="a.showProcessMedia.value"
        size="sm"
        icon="upload"
        :disabled="a.processingMedia.value"
        @click="a.processMediaOpen.value = true">
        Upload to Mux
      </Button>
      <Button
        v-if="a.showDeleteMedia.value"
        size="sm"
        icon="x"
        :disabled="a.deletingMedia.value"
        @click="a.deleteMediaOpen.value = true">
        Remove from Mux
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
  <Modal
    v-if="a.publishOpen.value"
    title="Publish selected items"
    subtitle="Configure visibility before publishing. Items already published will be skipped."
    icon="globe"
    :accent="accent ?? '#34d99a'"
    @close="a.publishOpen.value = false"
  >
    <div class="toggle-group">
      <Switch v-model="a.publishPublic.value" label="Public" :accent="accent" />
      <Switch v-model="a.publishPublicContent.value" label="Public Content" :accent="accent" />
      <Switch v-model="a.publishPublicSupplementary.value" label="Public Supplementary" :accent="accent" />
      <Switch v-model="a.publishSearchable.value" label="Searchable" :accent="accent" />
      <Switch v-model="a.publishRecommendable.value" label="Recommendable" :accent="accent" />
    </div>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="a.publishing.value" @click="a.publishOpen.value = false">
        Cancel
      </Button>
      <Button
        size="sm"
        primary
        icon="globe"
        :accent="accent ?? '#34d99a'"
        :disabled="a.publishing.value"
        @click="a.onPublish"
      >
        {{ a.publishing.value ? 'Publishing…' : `Publish ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''}` }}
      </Button>
    </template>
  </Modal>

  <!-- Unpublish modal -->
  <ConfirmModal
    v-if="a.unpublishOpen.value"
    :title="`Unpublish ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''}?`"
    subtitle="Items not currently published will be skipped."
    confirm-label="Unpublish All"
    :loading="a.unpublishing.value"
    @close="a.unpublishOpen.value = false"
    @confirm="a.onUnpublish"
  />

  <!-- Set Ready modal -->
  <ConfirmModal
    v-if="a.readyOpen.value"
    :title="`Set ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''} as ready?`"
    subtitle="Items already marked ready will be skipped."
    confirm-label="Set Ready"
    :loading="a.readying.value"
    @close="a.readyOpen.value = false"
    @confirm="a.onSetReady"
  />

  <!-- Mark Public / Visibility modal -->
  <Modal
    v-if="a.publicOpen.value"
    :title="`Update visibility for ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''}`"
    icon="globe"
    :accent="accent ?? '#5ec5ff'"
    @close="a.publicOpen.value = false"
  >
    <div class="toggle-group">
      <Switch v-model="a.publicPublic.value" label="Public" :accent="accent" />
      <Switch v-model="a.publicContent.value" label="Public Content" :accent="accent" />
      <Switch v-model="a.publicSupplementary.value" label="Public Supplementary" :accent="accent" />
      <Switch v-model="a.publicSearchable.value" label="Searchable" :accent="accent" />
      <Switch v-model="a.publicRecommendable.value" label="Recommendable" :accent="accent" />
    </div>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="a.publicUpdating.value" @click="a.publicOpen.value = false">
        Cancel
      </Button>
      <Button
        size="sm"
        primary
        icon="globe"
        :accent="accent ?? '#5ec5ff'"
        :disabled="a.publicUpdating.value"
        @click="a.onSetPublic"
      >
        {{ a.publicUpdating.value ? 'Updating…' : 'Update Visibility' }}
      </Button>
    </template>
  </Modal>

  <!-- Upload to Mux modal -->
  <ConfirmModal
    v-if="a.processMediaOpen.value"
    :title="`Upload ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''} to Mux?`"
    subtitle="Media processing creates HLS streams, thumbnails, and transcriptions. Items without uploaded source media will be skipped."
    confirm-label="Upload to Mux"
    :loading="a.processingMedia.value"
    @close="a.processMediaOpen.value = false"
    @confirm="a.onProcessMedia"
  />

  <!-- Remove from Mux modal -->
  <ConfirmModal
    v-if="a.deleteMediaOpen.value"
    :title="`Remove media for ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''} from Mux?`"
    subtitle="This deletes the processed media (HLS streams, thumbnails, transcriptions) from Mux. The original source files are not affected."
    confirm-label="Remove from Mux"
    :loading="a.deletingMedia.value"
    @close="a.deleteMediaOpen.value = false"
    @confirm="a.onDeleteMedia"
  />

  <!-- Delete modal -->
  <ConfirmModal
    v-if="a.deleteOpen.value"
    :title="`Delete ${a.selectedCount.value} item${a.selectedCount.value !== 1 ? 's' : ''}?`"
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

.toggle-group {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.spacer {
  flex: 1;
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
