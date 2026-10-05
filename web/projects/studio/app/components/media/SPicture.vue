<script setup lang="ts">
import type { Collection, CollectionMetadataRelationship, Metadata, MetadataRelationship } from '~/types/graphql'

const baseUrl = useRuntimeConfig().public.imageBaseUrl

const props = defineProps<{
  item: Metadata | Collection | MetadataRelationship | CollectionMetadataRelationship | null
  pictureClass?: string
  src?: string
  lazy?: boolean
  aspectRatio?: string
}>()

const error = ref(false)

watch(() => props.item, () => { error.value = false })

interface ImageRelationship {
  relationship: string
  attributes?: Record<string, unknown>
  metadata?: { id?: string; slug?: string | null; attributes?: Record<string, unknown> } | null
}

function getRelationship(): ImageRelationship | null {
  switch (props.item?.__typename) {
    case 'Metadata':
      if (props.item?.content?.type?.startsWith('image/')) {
        return { relationship: 'image.featured', attributes: {}, metadata: props.item }
      }
      return (props.item?.relationships as ImageRelationship[] | undefined)?.find(
        (r) => r.relationship === 'image.featured' || r.relationship === 'image.preview' || r.relationship === 'image.featured.square',
      ) ?? null
    case 'Collection':
      return ((props.item as Collection)?.metadataRelationships as ImageRelationship[] | undefined)?.find(
        (r) => r.relationship === 'image.featured' || r.relationship === 'image.preview' || r.relationship === 'image.featured.square',
      ) ?? null
    case 'MetadataRelationship':
    case 'CollectionMetadataRelationship':
      return props.item as unknown as ImageRelationship
    default:
      return null
  }
}

const url = computed(() => {
  const rel = getRelationship()?.metadata
  const slug = rel?.slug || rel?.id
  if (!slug) return ''
  return `${baseUrl}/${slug}`
})

const imageId = computed(() => getRelationship()?.metadata?.id ?? null)
const slug = computed(() => getRelationship()?.metadata?.slug ?? null)

interface ImageFormatAttrs {
  small?: string
  medium?: string
  large?: string
}

function findFormat(imageItem: ImageRelationship | null, format: string): ImageFormatAttrs | null {
  const attrs = imageItem?.attributes as Record<string, ImageFormatAttrs> | undefined
  if (attrs?.[format]) return attrs[format]
  const metaAttrs = imageItem?.metadata?.attributes as Record<string, ImageFormatAttrs> | undefined
  if (metaAttrs?.[format]) return metaAttrs[format]
  if (format === 'jpg') return findFormat(imageItem, 'jpeg')
  return null
}

function getSourceSet(format: string): string {
  const rel = getRelationship()
  const s = rel?.metadata?.slug || rel?.metadata?.id
  if (!s) return ''
  const attrs = findFormat(rel, format)
  const prefix = `${baseUrl}/${s}`
  return [
    `${prefix}.${format}?key=${attrs?.small || 'small'}`,
    `${prefix}.${format}?key=${attrs?.medium || 'medium'} 2x`,
    `${prefix}.${format}?key=${attrs?.large || 'large'} 3x`,
  ].join(', ')
}
</script>

<template>
  <picture
    v-if="url && !error"
    :key="slug || imageId || ''"
    :class="pictureClass"
    @error="error = true"
  >
    <source :srcset="getSourceSet('webp')" type="image/webp">
    <source :srcset="getSourceSet('jpg')" type="image/jpeg">
    <img
      :loading="props.lazy === false ? 'eager' : 'lazy'"
      :src="url"
      :alt="slug || imageId || ''"
      class="s-picture-img"
      :style="aspectRatio ? { aspectRatio } : undefined"
      @error="error = true"
    >
  </picture>
  <div
    v-else
    class="s-picture-placeholder"
    :class="pictureClass"
    :style="aspectRatio ? { aspectRatio } : undefined"
  />
</template>

<style scoped>
.s-picture-img {
  display: block;
  width: 100%;
  height: auto;
  object-fit: cover;
  background: var(--bg-2);
  border-radius: var(--r-sm);
}

.s-picture-placeholder {
  display: block;
  width: 100%;
  background: var(--bg-2);
  border-radius: var(--r-sm);
}
</style>
