<script lang="ts" setup>
import gql from 'graphql-tag'
import type { Collection, Metadata } from '~/types/graphql'
import type * as Y from 'yjs'

const { mutation } = useGraphQL()

const setCollectionSlugGql = gql`
  mutation SetCollectionSlug($id: UUID!, $slug: String!) {
    content { collections { setSlug(id: $id, slug: $slug) } }
  }
`

const setMetadataSlugGql = gql`
  mutation SetMetadataSlug($id: UUID!, $slug: String!) {
    content { metadata { setSlug(id: $id, slug: $slug) } }
  }
`

const props = defineProps<{
  item: Metadata | Collection
  itemName?: string
  editable: boolean
  ydoc?: Y.Doc
}>()

const emit = defineEmits<{
  'update:slug': [slug: string]
  edit: []
}>()

const slug = ref('slug' in props.item ? (props.item.slug as string) || '' : '')
const savedSlug = ref(slug.value)
const saving = ref(false)

// In read-only mode the field mirrors the item; keep it in sync when the
// slug is changed elsewhere (e.g. via the slug modal).
watch(
  () => ('slug' in props.item ? (props.item.slug as string) || '' : ''),
  (v) => {
    if (!props.editable && v !== slug.value) {
      slug.value = v
      savedSlug.value = v
    }
  },
)

function onFieldClick() {
  if (!props.editable) emit('edit')
}

function formatSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/[\s_]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

async function saveSlug() {
  if (!slug.value || slug.value === savedSlug.value) return
  saving.value = true
  try {
    const slugMutation = props.item.__typename === 'Collection'
      ? setCollectionSlugGql
      : setMetadataSlugGql
    await mutation(slugMutation, { id: props.item.id, slug: slug.value })
    savedSlug.value = slug.value
    emit('update:slug', slug.value)
  } catch (e) {
    console.error('Failed to save slug', e)
  } finally {
    saving.value = false
  }
}

// A manual edit is shared via the ydoc so a collaborator typing the title
// doesn't regenerate a slug someone else hand-tuned. Persisting it in the doc
// also keeps the override across reloads.
function onManualEdit() {
  props.ydoc?.getMap('slugs').set('manual-slug', 'true')
}

function setSlugFromTitle(title: string) {
  const manuallyEdited = props.ydoc?.getMap('slugs').get('manual-slug') === 'true'
  const published = (props.item as { workflow?: { state?: string } | null }).workflow?.state === 'published'
  if (manuallyEdited || published) return
  slug.value = formatSlug(title)
}

defineExpose({ setSlugFromTitle, saveSlug })
</script>

<template>
  <div class="slug-field">
    <div class="slug-label-row">
      <span class="slug-label">Slug</span>
    </div>
    <div class="slug-input-row">
      <input
        v-model="slug"
        class="slug-input mono"
        :class="{ 'slug-input--readonly': !editable }"
        type="text"
        :readonly="!editable"
        :title="!editable ? 'Click to edit the slug' : undefined"
        @click="onFieldClick"
        @input="onManualEdit"
        @blur="saveSlug"
      >
    </div>
  </div>
</template>

<style scoped>
.slug-field {
  margin-bottom: 14px;
}

.slug-label-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.slug-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.slug-input-row {
  display: flex;
  gap: 6px;
}

.slug-input {
  flex: 1;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--fg-0);
  outline: none;
  overflow: hidden;
  text-overflow: ellipsis;
}

.slug-input:focus {
  border-color: var(--brand-2);
}

.slug-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.slug-input--readonly {
  cursor: pointer;
  color: var(--fg-2);
}

.slug-input--readonly:hover {
  border-color: var(--brand-2);
}

.slug-input--readonly:focus {
  border-color: var(--line);
}
</style>
