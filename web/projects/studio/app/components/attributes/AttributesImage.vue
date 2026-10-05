<script lang="ts" setup>
import gql from 'graphql-tag'
import type { AttributeMetadata, AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Collection, Metadata } from '~/types/graphql'
import type { Uploader } from '~/utils/editor/uploader'
import { useDropZone, useDebounceFn } from '@vueuse/core'

interface AspectRatioConfig {
  name: string
  value: number
  icon: string
  default?: boolean
}

interface ActionConfig {
  name: string
  action: string
  icon: string
}

const getMetadataGql = gql`
  query GetImageMetadata($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        name
        content { type }
        attributes
        public
        publicContent
        publicSupplementary
        workflow { state }
      }
    }
  }
`

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  attribute: AttributeState
  uploader: Uploader
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const { query: gqlQuery, useSubscription } = useGraphQL()
const language = useLanguage()
const toast = useToast()
const dropzoneRef = ref<HTMLElement>()
const uploading = ref(false)
const selectorOpen = ref(false)

interface CropCoordinates {
  width: number
  height: number
  left: number
  top: number
}

interface CropperResult {
  coordinates: CropCoordinates
  visibleArea: CropCoordinates
}

const editorRef = ref<{
   
  zoom: (_factor: number) => void
  maximize: () => void
  getResult: () => CropperResult | undefined
   
  setCoordinates: (_coords: CropCoordinates) => void
} | null>(null)

const aspectRatio = ref<number | null>(null)
const metadata = ref(props.attribute.metadata)
const notPublic = ref(false)

const hasImage = computed(() => !!metadata.value?.id)

const imageUrl = computed(() => {
  const meta = metadata.value
  if (!meta?.id) return null
  const key = meta.attributes?.jpeg?.large
  if (key) return `/content/image/${meta.id}?key=${key}`
  return `/content/image/${meta.id}`
})

const aspectRatios = computed<AspectRatioConfig[]>(() => {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  return (config?.aspectRatios as AspectRatioConfig[] | undefined) ?? []
})

const actions = computed<ActionConfig[]>(() => {
  const config = props.attribute.configuration as Record<string, unknown> | undefined
  return (config?.actions as ActionConfig[] | undefined) ?? []
})

const showAspectRatios = computed(() => {
  const ratios = aspectRatios.value
  if (ratios.length === 0) return false
  if (ratios.length === 1 && ratios[0]?.default) return false
  return true
})

// ── Publication status check ────────────────────────────────────────────────
const updateNotPublic = useDebounceFn(async () => {
  if (!metadata.value?.id) {
    notPublic.value = true
    return
  }
  try {
    const data = await gqlQuery<{
      content: { metadata: { public: boolean; publicContent: boolean; publicSupplementary: boolean; workflow: { state: string } } | null }
    }>(getMetadataGql, { id: metadata.value.id })
    const m = data?.content?.metadata
    if (!m) {
      notPublic.value = true
      return
    }
    notPublic.value = !m.public || !m.publicContent || !m.publicSupplementary || m.workflow.state !== 'published'
  } catch {
    notPublic.value = true
  }
}, 1000)

const metadataSubGql = gql`
  subscription ImageMetadataChanges {
    metadata { id type }
  }
`

useSubscription(metadataSubGql, {}, (event: { metadata?: { id?: string; type?: string } }) => {
  if (event?.metadata?.id === metadata.value?.id) {
    updateNotPublic()
  }
})

// ── Drag & drop upload ──────────────────────────────────────────────────────
useDropZone(dropzoneRef, {
  onDrop: async (files: File[] | null) => {
    if (!props.editable || !files?.length) return
    if (files.length > 1) {
      toast.error('Only a single image can be dropped at a time')
      return
    }
    const file = files[0]!
    if (!file.type.startsWith('image/')) return
    await uploadFile(file)
  },
})

async function uploadFile(file: File) {
  uploading.value = true
  const progressHandle = toast.showProgress(`Uploading ${file.name}…`)
  try {
    const metadataId = await props.uploader.upload(file, language.current.value?.tag ?? 'en', (progress, phase) => {
      if (phase === 'processing') {
        progressHandle.update(progress, `Processing ${file.name}`)
      } else {
        progressHandle.update(progress, `Uploading ${file.name}`)
      }
    })
    progressHandle.complete()
    await onMetadataSelected(metadataId)
    toast.success('Image uploaded')
  } catch (e: unknown) {
    progressHandle.dismiss()
    toast.error(e instanceof Error ? e.message : 'Image upload failed')
  } finally {
    uploading.value = false
  }
}

async function onMetadataSelected(metadataId: string) {
  try {
    const result = await gqlQuery<{
      content: { metadata: { id: string; name: string; content: { type: string } | null; attributes: Record<string, unknown> } | null }
    }>(getMetadataGql, { id: metadataId })
    const m = result?.content?.metadata
    if (!m) return
    const config = props.attribute.configuration as Record<string, unknown> | undefined
    // AttributeState.metadata is a class setter that updates the Yjs doc, not a plain prop
    // eslint-disable-next-line vue/no-mutating-props
    props.attribute.metadata = {
      id: m.id,
      name: m.name,
      contentType: m.content?.type || 'application/octet-stream',
      relationship: (config?.relationship as string) ?? '',
      attributes: {
        ...m.attributes,
        targetSize: config?.targetSize,
      },
    }
    metadata.value = props.attribute.metadata
    updateNotPublic()
  } catch (e) {
    console.error('Failed to fetch uploaded image metadata', e)
  }
}

function onDropZoneClick() {
  selectorOpen.value = true
}

async function onSelectorSelected(metadataId: string) {
  selectorOpen.value = false
  await onMetadataSelected(metadataId)
}

// ── Toolbar actions ─────────────────────────────────────────────────────────
function onSelectAspectRatio(ratio: AspectRatioConfig) {
  aspectRatio.value = ratio.value
}

function onAction(action: string) {
  switch (action) {
    case 'zoomin':
      editorRef.value?.zoom(1.5)
      break
    case 'zoomout':
      editorRef.value?.zoom(0.75)
      break
    case 'maximize':
      editorRef.value?.maximize()
      break
    case 'copy':
      try {
        navigator.clipboard.writeText(JSON.stringify(props.attribute.metadata))
      } catch (e) { console.error('Copy failed', e) }
      break
    case 'download':
      onDownload()
      break
    case 'paste':
      onPaste()
      break
  }
}

function onDownload() {
  const meta = metadata.value
  if (!meta?.id) return
  const key = meta.attributes?.jpeg?.large
  const url = key
    ? `/content/image/${meta.id}?key=${key}&download=true`
    : `/content/file?id=${meta.id}&download=true`
  window.open(url)
}

async function onPaste() {
  try {
    const items = await navigator.clipboard.read()
    for (const item of items) {
      if (!item.types.includes('text/plain')) continue
      const text = await (await item.getType('text/plain')).text()
      const data = JSON.parse(text) as AttributeMetadata
      const config = props.attribute.configuration as Record<string, unknown> | undefined
      data.relationship = (config?.relationship as string) || data.relationship
      // eslint-disable-next-line vue/no-mutating-props
      props.attribute.metadata = data
      metadata.value = data
      updateNotPublic()
      break
    }
  } catch (e) { console.error('Paste failed', e) }
}

function onClear() {
  // eslint-disable-next-line vue/no-mutating-props
  props.attribute.metadata = null
  metadata.value = null
}

// ── Attribute change listener ───────────────────────────────────────────────
function onChanged() {
  metadata.value = props.attribute.metadata
  updateNotPublic()
}

onMounted(() => {
  props.attribute.addListener(onChanged)
  updateNotPublic()
  const ratios = aspectRatios.value
  if (ratios.length > 0 && ratios[0]?.default) {
    aspectRatio.value = ratios[0]!.value
  }
})

onUnmounted(() => {
  props.attribute.removeListener(onChanged)
})
</script>

<template>
  <!--
    Unlike the other attribute editors, this one is intentionally NOT keyed on
    `attribute.changeRef.value`. It both reads and writes the same attribute: the
    cropper persists crop coordinates back into the Yjs doc on drag, which bumps
    changeRef. Keying on it would remount the whole subtree on every crop edit,
    reloading the cropper's <img> and causing a visible flash. External changes are
    handled reactively via the `onChanged` listener (updates `metadata`), and image
    identity changes remount the cropper via its own `:key="stableImageId"`.
  -->
  <div class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :not-public="notPublic"
      :on-run-tool="onRunTool"
    />

    <!-- Editable mode -->
    <div v-if="editable" ref="dropzoneRef">
      <!-- Has image: show cropper -->
      <template v-if="hasImage">
        <div class="editor-dropzone">
          <ClientOnly>
            <AttributesImageEditor
              ref="editorRef"
              :attribute="attribute"
              :aspect-ratio="aspectRatio"
              :editable="editable"
            />
          </ClientOnly>
        </div>

        <!-- Toolbar: aspect ratios + actions -->
        <div class="image-toolbar">
          <template v-if="showAspectRatios">
            <button
              v-for="ratio in aspectRatios"
              :key="ratio.name"
              class="toolbar-btn"
              :class="{ 'toolbar-btn--active': aspectRatio === ratio.value }"
              :title="ratio.name"
              @click="onSelectAspectRatio(ratio)"
            >
              <Icon :name="ratio.icon || 'image'" :size="14" />
            </button>
            <span v-if="actions.length" class="toolbar-sep" />
          </template>

          <button
            v-for="act in actions"
            :key="act.action"
            class="toolbar-btn"
            :title="act.name"
            @click="onAction(act.action)"
          >
            <Icon :name="act.icon || 'settings'" :size="14" />
          </button>

          <span class="toolbar-spacer" />
          <button class="toolbar-btn toolbar-btn--danger" title="Remove image" @click="onClear">
            <Icon name="x" :size="14" />
          </button>
        </div>
      </template>

      <!-- No image: click/drop zone -->
      <div v-else class="image-dropzone" @click="onDropZoneClick">
        <span v-if="uploading" class="dropzone-label">
          <Icon
            name="spinner"
            :size="14"
            color="var(--fg-3)"
            class="spin" /> Uploading…
        </span>
        <span v-else class="dropzone-label">
          <Icon name="image" :size="14" color="var(--fg-3)" /> Click or drop image here
        </span>
      </div>
    </div>

    <!-- Read-only mode with image -->
    <template v-else-if="!editable && hasImage">
      <div class="image-preview">
        <img :src="imageUrl!" alt="" class="image-preview-img">
      </div>
      <div class="image-toolbar">
        <button class="toolbar-btn" title="Download" @click="onDownload">
          <Icon name="download" :size="14" />
        </button>
      </div>
    </template>

    <!-- Read-only mode without image -->
    <div v-else class="attr-empty">No image</div>

    <!-- Image selector modal -->
    <AttributesImageSelectorModal
      v-if="selectorOpen"
      :on-selected="onSelectorSelected"
      @close="selectorOpen = false"
    />
  </div>
</template>

<style scoped>
.attr-field { margin-bottom: 14px; }

.editor-dropzone { overflow: hidden; border-radius: var(--r-md); }

.image-dropzone {
  height: 132px;
  border-radius: var(--r-md);
  background: var(--bg-2);
  border: 1px dashed var(--line-2);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.image-dropzone:hover { border-color: var(--brand-2); }

.dropzone-label {
  font-size: 12px;
  color: var(--fg-3);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.image-preview {
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--line);
}
.image-preview-img {
  width: 100%;
  display: block;
  max-height: 300px;
  object-fit: contain;
  background: var(--bg-2);
}

.image-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 4px 0;
}
.toolbar-btn {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  background: none;
  border: 1px solid transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fg-3);
}
.toolbar-btn:hover { background: var(--bg-2); color: var(--fg-1); }
.toolbar-btn--active { background: var(--bg-3); border-color: var(--brand-2); color: var(--fg-0); }
.toolbar-btn--danger:hover { color: var(--err); }
.toolbar-sep { width: 1px; height: 16px; background: var(--line); margin: 0 4px; }
.toolbar-spacer { flex: 1; }
.attr-empty { font-size: 12px; color: var(--fg-3); }
</style>
