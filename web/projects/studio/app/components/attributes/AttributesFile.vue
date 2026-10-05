<script lang="ts" setup>
import gql from 'graphql-tag'
import { useDebounceFn } from '@vueuse/core'
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Uploader } from '~/utils/editor/uploader'
import type { Collection, Metadata } from '~/types/graphql'
import { detectMediaType } from '~/composables/useMediaType'

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  attribute: AttributeState
  uploader: Uploader
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const uploading = ref(false)
const dropZoneRef = ref<HTMLElement | null>(null)
const selectorOpen = ref(false)

const { query: gqlQuery, useSubscription } = useGraphQL()

const selectorSearchFilter = computed(() => {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  return (config?.searchFilter as string) || null
})

// ── Publication status check ────────────────────────────────────────────────
const getFileMetadataGql = gql`
  query GetFileAttrMetadata($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        content { type }
        public
        publicContent
        publicSupplementary
        workflow { state }
      }
    }
  }
`

const notPublic = ref(false)

const updateNotPublic = useDebounceFn(async () => {
  const id = props.attribute.metadata?.id
  if (!id) {
    notPublic.value = false
    return
  }
  try {
    const data = await gqlQuery<{
      content: {
        metadata: {
          content: { type: string | null } | null
          public: boolean
          publicContent: boolean
          publicSupplementary: boolean
          workflow: { state: string } | null
        } | null
      }
    }>(getFileMetadataGql, { id })
    const m = data?.content?.metadata
    if (!m) {
      notPublic.value = true
      return
    }
    const isImage = m.content?.type?.startsWith('image/') ?? false
    notPublic.value = !m.public || !m.publicContent || (isImage && !m.publicSupplementary) || m.workflow?.state !== 'published'
  } catch {
    notPublic.value = true
  }
}, 1000)

const fileMetadataSubGql = gql`
  subscription FileAttrMetadataChanges {
    metadata { id type }
  }
`

useSubscription(fileMetadataSubGql, {}, (event: { metadata?: { id?: string } }) => {
  if (event?.metadata?.id && event.metadata.id === props.attribute.metadata?.id) {
    updateNotPublic()
  }
})

function onAttributeChanged() {
  updateNotPublic()
}

onMounted(() => {
  props.attribute.addListener(onAttributeChanged)
  updateNotPublic()
})

onUnmounted(() => {
  props.attribute.removeListener(onAttributeChanged)
})

const fileUrl = computed(() => {
  const meta = props.attribute.metadata
  if (!meta?.id) return null
  return `/content/file?id=${meta.id}`
})

const fileName = computed(() => props.attribute.metadata?.name ?? '')

const reconstructedMetadata = computed(() => {
  const meta = props.attribute.metadata
  if (!meta?.id) return null
  return {
    __typename: 'Metadata' as const,
    id: meta.id,
    name: meta.name ?? '',
    content: { type: meta.contentType ?? null },
    attributes: meta.attributes ?? {},
    media: null,
  } as unknown as Metadata
})

const mediaInfo = computed(() => {
  const meta = reconstructedMetadata.value
  if (!meta) return { isNativeMedia: false, isMediaWithTimeline: false }
  const result = detectMediaType(meta)
  const contentType = meta.content?.type ?? ''
  const isMediaWithTimeline =
    contentType.startsWith('video/') ||
    contentType.startsWith('audio/') ||
    contentType === 'bosca/x-youtube-video'
  return { isNativeMedia: result.isNativeMedia, isMediaWithTimeline }
})

const isMedia = computed(() => mediaInfo.value.isNativeMedia)
const isMediaWithTimeline = computed(() => mediaInfo.value.isMediaWithTimeline)
const timelineOpen = ref(false)

async function onDrop(files: File[] | null) {
  if (!files?.length || !props.editable) return
  uploading.value = true
  try {
    const file = files[0]!
    const metadataId = await props.uploader.upload(file, 'en-US')
    if (metadataId) {
      const config = props.attribute.configuration as Record<string, unknown> | undefined
      // AttributeState.metadata is a class setter that updates the Yjs doc, not a plain prop
      // eslint-disable-next-line vue/no-mutating-props
      props.attribute.metadata = {
        id: metadataId,
        relationship: config?.relationship as string,
        contentType: file.type,
        attributes: { sort: 0 },
        name: file.name
      }
      updateNotPublic()
    }
  } catch (e) {
    console.error('File upload failed', e)
  } finally {
    uploading.value = false
  }
}

function onSelectorSelected(item: { id: string; name: string; contentType: string }) {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  // AttributeState.metadata is a class setter that updates the Yjs doc, not a plain prop
  // eslint-disable-next-line vue/no-mutating-props
  props.attribute.metadata = {
    id: item.id,
    relationship: config?.relationship as string,
    contentType: item.contentType,
    attributes: { sort: 0 },
    name: item.name,
  }
  updateNotPublic()
}

function onDownload() {
  if (fileUrl.value) window.open(fileUrl.value + '&download=true')
}

function onClear() {
  // eslint-disable-next-line vue/no-mutating-props
  props.attribute.metadata = null
}
</script>

<template>
  <div :key="attribute.changeRef.value" class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :not-public="notPublic"
      :on-run-tool="onRunTool"
    />

    <div
      v-if="editable && !attribute.metadata?.id"
      ref="dropZoneRef"
      class="file-dropzone"
      @click="selectorOpen = true"
      @dragover.prevent
      @drop.prevent="onDrop(Array.from($event.dataTransfer?.files || []))"
    >
      <span v-if="uploading" class="file-dropzone-label">
        <Icon name="spinner" :size="14" color="var(--fg-3)" /> Uploading…
      </span>
      <span v-else class="file-dropzone-label">
        <Icon name="file" :size="14" color="var(--fg-3)" /> Click to choose an asset, or drop a file to upload
      </span>
    </div>

    <div v-if="attribute.metadata?.id && isMedia" class="file-media">
      <ClientOnly>
        <SMediaPlayer :item="reconstructedMetadata" />
      </ClientOnly>
      <div class="file-display">
        <span class="file-name">{{ fileName }}</span>
        <span class="spacer" />
        <button v-if="isMediaWithTimeline" class="file-action" @click="timelineOpen = true">
          <Icon name="clock" :size="12" /> Timeline
        </button>
        <button class="file-action" @click="onDownload">Download</button>
        <button v-if="editable" class="file-action file-action--clear" @click="onClear">Clear</button>
      </div>
    </div>

    <div v-else-if="attribute.metadata?.id" class="file-display">
      <span class="file-name">{{ fileName }}</span>
      <span class="spacer" />
      <button class="file-action" @click="onDownload">Download</button>
      <button v-if="editable" class="file-action file-action--clear" @click="onClear">Clear</button>
    </div>

    <div v-else-if="!editable" class="attr-empty">No file</div>

    <!-- Asset selector modal -->
    <AttributesFileSelectorModal
      v-if="selectorOpen"
      :on-selected="onSelectorSelected"
      :search-filter="selectorSearchFilter"
      @close="selectorOpen = false"
    />

    <!-- Timeline modal -->
    <Teleport to="body">
      <div v-if="timelineOpen && reconstructedMetadata" class="timeline-modal-overlay" @click.self="timelineOpen = false">
        <div class="timeline-modal">
          <div class="timeline-modal-header">
            <span class="timeline-modal-title">Time Events</span>
            <button class="timeline-modal-close" @click="timelineOpen = false">
              <Icon name="x" :size="16" />
            </button>
          </div>
          <div class="timeline-modal-body">
            <ClientOnly>
              <LazyTimeEventsEditor :metadata="reconstructedMetadata" />
            </ClientOnly>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.attr-field { margin-bottom: 14px; }

.file-media {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.file-dropzone {
  height: 100px;
  border-radius: 8px;
  background: var(--bg-2);
  border: 1px dashed var(--line-2);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.file-dropzone:hover { border-color: var(--brand-2); }

.file-dropzone-label {
  font-size: 12px;
  color: var(--fg-3);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.file-display {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
}

.file-name {
  color: var(--fg-1);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spacer { flex: 1; }

.file-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  white-space: nowrap;
  font-size: 11px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
}

.file-action:hover { color: var(--fg-1); }
.file-action--clear:hover { color: var(--err); }
.attr-empty { font-size: 12px; color: var(--fg-3); }

.timeline-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9000;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.timeline-modal {
  background: var(--bg-0);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  width: 100%;
  max-width: 1200px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.timeline-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.timeline-modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg-0);
}

.timeline-modal-close {
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--r-sm);
}

.timeline-modal-close:hover {
  color: var(--fg-0);
  background: var(--bg-2);
}

.timeline-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
}
</style>
