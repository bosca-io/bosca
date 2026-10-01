<script setup lang="ts">
interface TextPart {
  kind: 'text'
  text: string
}

interface ImagePart {
  kind: 'image'
  alt: string
  src: string
  metadataId: string
}

type MessagePart = TextPart | ImagePart

const props = defineProps<{
  text: string
}>()

const boscaImagePattern = /!\[([^\]\r\n]*)\]\(([^)\s]+)\)/g
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function boscaImageMetadataId(value: string): string | null {
  if (!value.startsWith('/') || value.startsWith('//')) return null

  const url = new URL(value, 'https://studio.invalid')
  if (url.origin !== 'https://studio.invalid') return null

  if (url.pathname === '/api/v1/content/metadata/download') {
    const id = url.searchParams.get('id')
    return id !== null && uuidPattern.test(id) ? id : null
  }

  if (url.pathname.startsWith('/content/image/')) {
    const id = url.pathname.slice('/content/image/'.length).split('.')[0]
    return id !== undefined && uuidPattern.test(id) ? id : null
  }

  return null
}

function parseKitMessage(text: string): MessagePart[] {
  const parts: MessagePart[] = []
  let cursor = 0

  for (const match of text.matchAll(boscaImagePattern)) {
    const start = match.index
    const source = match[2]
    if (start === undefined || source === undefined) continue
    const metadataId = boscaImageMetadataId(source)
    if (metadataId === null) continue

    if (start > cursor) {
      parts.push({ kind: 'text', text: text.slice(cursor, start) })
    }
    parts.push({
      kind: 'image',
      alt: match[1]?.trim() || 'Generated image',
      src: source,
      metadataId,
    })
    cursor = start + match[0].length
  }

  if (cursor < text.length || parts.length === 0) {
    parts.push({ kind: 'text', text: text.slice(cursor) })
  }

  return parts
}

const parts = computed(() => parseKitMessage(props.text))
const failedImages = ref(new Set<number>())

function markFailed(index: number) {
  failedImages.value = new Set(failedImages.value).add(index)
}
</script>

<template>
  <div class="kit-message-content">
    <template v-for="(part, index) in parts" :key="index">
      <span v-if="part.kind === 'text'">{{ part.text }}</span>
      <figure v-else class="kit-message-image-result">
        <span v-if="failedImages.has(index)" class="kit-message-image-error">
          Couldn’t load {{ part.alt }}.
          <a :href="part.src" target="_blank" rel="noopener noreferrer">Open image</a>
        </span>
        <a
          v-else
          class="kit-message-image-link"
          :href="part.src"
          target="_blank"
          rel="noopener noreferrer"
        >
          <img
            class="kit-message-image"
            :src="part.src"
            :alt="part.alt"
            loading="lazy"
            @error="markFailed(index)"
          >
        </a>
        <figcaption class="kit-message-image-actions" aria-label="Generated image actions">
          <NuxtLink
            class="kit-message-image-metadata-link"
            :to="`/cms/metadata/${part.metadataId}`"
            :aria-label="`View metadata for ${part.alt}`"
          >
            <Icon name="database" :size="12" />
            <span>View metadata</span>
          </NuxtLink>
        </figcaption>
      </figure>
    </template>
  </div>
</template>

<style scoped>
.kit-message-content {
  white-space: pre-wrap;
}

.kit-message-image-result {
  width: fit-content;
  max-width: 100%;
  margin: 8px 0;
}

.kit-message-image-link {
  display: block;
  width: fit-content;
  max-width: 100%;
}

.kit-message-image {
  display: block;
  max-width: 100%;
  max-height: 520px;
  object-fit: contain;
  border-radius: var(--r-sm);
  background: var(--bg-2);
}

.kit-message-image-error {
  display: block;
  margin: 8px 0;
  color: var(--fg-2);
  font-size: 12.5px;
}

.kit-message-image-error a {
  color: var(--accent, var(--fg-0));
  text-decoration: underline;
}

.kit-message-image-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

.kit-message-image-metadata-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-1);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  text-decoration: none;
}

.kit-message-image-metadata-link:hover {
  background: var(--bg-3);
}
</style>
