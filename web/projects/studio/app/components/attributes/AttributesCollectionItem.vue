<script setup lang="ts">
import type { Collection } from '~/types/graphql'

const { query: gqlQuery } = useGraphQL()

const props = defineProps<{
  collection: Collection | null
}>()

const imageId = ref<string | null>(null)
const imageRelationship = ref<string | null>(null)

async function loadImage() {
  if (!props.collection?.id) return
  const colGql = `
    query GetCollectionItemDetails($id: UUID!) {
      content {
        collections {
          collection(id: $id) {
            metadataRelationships {
              relationship
              metadata { id }
            }
          }
        }
      }
    }
  `
  try {
    interface CollectionItemResult {
      content: { collections: { collection: { metadataRelationships: Array<{ relationship: string; metadata: { id: string } }> } | null } }
    }
    const result = await gqlQuery<CollectionItemResult>(colGql, { id: props.collection.id })
    const rels = result?.content?.collections?.collection?.metadataRelationships ?? []
    const rel = rels.find(r =>
      r.relationship === 'image.avatar' || r.relationship === 'image.featured' || r.relationship === 'image.preview'
    )
    if (rel) {
      imageId.value = rel.metadata?.id ?? null
      imageRelationship.value = rel.relationship
    }
  } catch { /* ignore */ }
}

onMounted(loadImage)
</script>

<template>
  <div class="collection-item">
    <img
      v-if="imageId"
      :src="'/content/file?id=' + imageId"
      :alt="collection?.name"
      class="collection-item-img"
      :class="{ 'collection-item-img--avatar': imageRelationship === 'image.avatar' }"
    >
    <span class="collection-item-name">{{ collection?.name }}</span>
  </div>
</template>

<style scoped>
.collection-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.collection-item-img {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  object-fit: cover;
  background: var(--bg-2);
}

.collection-item-img--avatar {
  border-radius: 50%;
}

.collection-item-name {
  font-size: 12.5px;
  color: var(--fg-1);
}
</style>
