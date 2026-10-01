<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */

const emit = defineEmits<{
  select: [metadata: { id: string; name: string }]
  close: []
}>()

const gql = useGraphQL()
const toast = useToast()

const query = ref('')
const results = ref<{ id: string; name: string; contentType: string; imageSlug: string | null }[]>([])
const searching = ref(false)
const isDragging = ref(false)
const uploading = ref(false)
const fileInputEl = ref<HTMLInputElement>()

const SEARCH_METADATA = `
  query SearchMetadata($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          metadata {
            id name slug content { type }
            relationships(filter: ["image.featured", "image.avatar", "image.preview"]) {
              relationship
              metadata { id slug }
            }
          }
        }
      }
    }
  }
`

const ADD_METADATA = `
  mutation AddMetadataForChat($metadata: MetadataInput!) {
    content { metadata { add(metadata: $metadata, setReady: true) {
      id name
      content { urls { upload { url headers { name value } } } }
    } } }
  }
`

let searchTimer: ReturnType<typeof setTimeout> | null = null

async function doSearch() {
  if (!query.value.trim()) { results.value = []; searching.value = false; return }
  searching.value = true
  try {
    const result = await gql.query<any>(SEARCH_METADATA, { query: query.value, limit: 20, offset: 0 })
    results.value = (result?.search?.search?.documents ?? [])
      .filter((d: any) => d.metadata)
      .map((d: any) => {
        const rel = d.metadata.relationships?.[0]
        const imageSlug = rel?.metadata?.slug ?? null
        return {
          id: d.metadata.id,
          name: d.metadata.name,
          contentType: d.metadata.content?.type ?? '',
          imageSlug,
        }
      })
  } catch {
    results.value = []
  }
  searching.value = false
}

watch(query, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(doSearch, 250)
})

onUnmounted(() => { if (searchTimer) clearTimeout(searchTimer) })

async function uploadFile(file: File) {
  uploading.value = true
  try {
    const result = await gql.mutation<any>(ADD_METADATA, {
      metadata: {
        name: file.name,
        languageTag: 'en',
        contentType: file.type || 'application/octet-stream',
        contentLength: file.size,
      },
    })

    const metadata = result?.content?.metadata?.add
    if (!metadata) throw new Error('Failed to create metadata')

    const upload = metadata.content?.urls?.upload
    if (upload) {
      const headers = new Headers()
      for (const hdr of upload.headers) headers.append(hdr.name, hdr.value)
      const formData = new FormData()
      formData.append('file', file)
      await fetch(upload.url, { method: 'POST', body: formData, headers })
    }

    toast.success(`Uploaded ${file.name}`)
    emit('select', { id: metadata.id, name: metadata.name || file.name })
  } catch (e: any) {
    toast.error(e?.message || 'Upload failed')
  }
  uploading.value = false
}

function onFileInput(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (file) uploadFile(file)
  ;(e.target as HTMLInputElement).value = ''
}

function onDrop(e: DragEvent) {
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) uploadFile(file)
}
</script>

<template>
  <Teleport to="body">
    <div class="picker-overlay" @click.self="emit('close')">
      <div
        class="picker-dialog"
        :class="{ 'picker-dragging': isDragging }"
        @dragover.prevent="isDragging = true"
        @dragleave.self="isDragging = false"
        @drop.prevent="onDrop"
      >
        <div class="picker-header">
          <h3 class="picker-title">Select Metadata</h3>
          <button class="picker-close" @click="emit('close')">
            <Icon name="x" :size="16" />
          </button>
        </div>

        <div class="picker-body">
          <!-- Search -->
          <input
            v-model="query"
            class="search-input"
            placeholder="Search metadata…"
            autofocus
          >

          <!-- Results -->
          <div v-if="searching" class="searching-text">Searching…</div>
          <div v-else-if="results.length" class="result-list">
            <button
              v-for="r in results"
              :key="r.id"
              class="result-row"
              @click="emit('select', { id: r.id, name: r.name })"
            >
              <img
                v-if="r.imageSlug"
                :src="`/content/image/${r.imageSlug}`"
                class="result-thumb"
                alt=""
              >
              <Icon
                v-else
                name="file"
                :size="14"
                color="var(--fg-3)" />
              <span class="result-name">{{ r.name }}</span>
              <span class="result-type">{{ r.contentType }}</span>
            </button>
          </div>

          <!-- Drop zone -->
          <div class="drop-zone" @click="fileInputEl?.click()">
            <template v-if="uploading">
              <Icon name="spinner" :size="20" color="var(--fg-3)" />
              <span>Uploading…</span>
            </template>
            <template v-else-if="isDragging">
              <Icon name="upload" :size="20" color="var(--brand-2)" />
              <span>Drop file here</span>
            </template>
            <template v-else>
              <Icon name="upload" :size="20" color="var(--fg-3)" />
              <span>Drop a file or click to upload</span>
            </template>
          </div>
          <input
            ref="fileInputEl"
            type="file"
            hidden
            @change="onFileInput">
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.picker-overlay { position: fixed; inset: 0; z-index: 9000; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; }
.picker-dialog { background: var(--bg-0); border: 1px solid var(--line); border-radius: var(--r-lg); width: 480px; max-height: 70vh; display: flex; flex-direction: column; overflow: hidden; }
.picker-dragging { border-color: var(--brand-2); }
.picker-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--line); }
.picker-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0; }
.picker-close { background: none; border: none; color: var(--fg-3); cursor: pointer; padding: 4px; border-radius: var(--r-sm); }
.picker-close:hover { color: var(--fg-0); background: var(--bg-2); }
.picker-body { padding: 14px 18px; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }

.search-input { width: 100%; padding: 8px 12px; font-size: 13px; background: var(--bg-2); border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none; box-sizing: border-box; }
.search-input:focus { border-color: var(--brand-2); }

.searching-text { font-size: 12px; color: var(--fg-3); text-align: center; padding: 8px; }

.result-list { display: flex; flex-direction: column; gap: 2px; max-height: 240px; overflow-y: auto; }
.result-row { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: var(--r-sm); cursor: pointer; text-align: left; background: none; border: none; color: var(--fg-1); width: 100%; }
.result-row:hover { background: var(--bg-2); }
.result-name { flex: 1; font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-type { font-size: 11px; color: var(--fg-3); }

.result-thumb {
  width: 32px; height: 32px; border-radius: var(--r-sm);
  object-fit: cover; flex-shrink: 0; background: var(--bg-3);
}

.drop-zone { padding: 24px; border: 2px dashed var(--line); border-radius: var(--r-md); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; cursor: pointer; font-size: 13px; color: var(--fg-3); transition: border-color 0.15s; }
.drop-zone:hover { border-color: var(--brand-2); }
.picker-dragging .drop-zone { border-color: var(--brand-2); background: color-mix(in oklch, var(--brand-2) 5%, transparent); }
</style>
