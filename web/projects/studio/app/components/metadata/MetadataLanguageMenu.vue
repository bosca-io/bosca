<script setup lang="ts">
/**
 * Header language menu for metadata editors. Lists every platform language
 * (from the languages catalog): languages that already have a variant navigate
 * to it, languages without one create a variant (after confirmation) and then
 * navigate. Navigation is a full page load so editor collaboration state
 * (Yjs document, Hocuspocus provider) fully resets for the other variant.
 */
import gql from 'graphql-tag'
import type { OverflowMenuItem } from '@bosca/ui'

interface Variant {
  id: string
  languageTag: string
  name: string
}

const props = defineProps<{
  metadataId: string
  /** Variants are created against the root metadata when editing a variant. */
  parentId?: string | null
  metadataVersion: number
  languageTag?: string | null
  variants: Variant[]
  /** Editor route prefix, e.g. `/cms/editor/`. */
  urlPrefix: string
  accent?: string
}>()

const { mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const languageManager = useLanguage()

const addVariantGql = gql`
  mutation AddEditorLanguageVariant($id: UUID!, $version: Int!, $languageTag: String!) {
    content { metadata { addLanguageVariant(id: $id, version: $version, languageTag: $languageTag, setReady: true) { id } } }
  }
`

const triggerLabel = computed(() => {
  if (!props.languageTag) return 'Language'
  const language = languageManager.getLanguage(props.languageTag)
  return language ? language.name : props.languageTag
})

const variantByTag = computed(() => {
  const map = new Map<string, Variant>()
  for (const v of props.variants) {
    if (v.languageTag) map.set(v.languageTag.toLowerCase(), v)
  }
  return map
})

const menuItems = computed<OverflowMenuItem[]>(() => {
  const existing: OverflowMenuItem[] = []
  const missing: OverflowMenuItem[] = []
  const seenTags = new Set<string>()

  for (const lang of languageManager.languagesRef.value) {
    const tagLower = lang.tag.toLowerCase()
    if (seenTags.has(tagLower)) continue
    seenTags.add(tagLower)
    const variant = variantByTag.value.get(tagLower)
    if (variant) {
      existing.push({
        id: `goto:${variant.id}`,
        label: `${lang.tag} — ${lang.name}`,
        icon: 'languages',
        disabled: variant.id === props.metadataId,
      })
    } else {
      missing.push({ id: `create:${lang.tag}`, label: `${lang.tag} — ${lang.name}`, icon: 'plus' })
    }
  }

  // Variants in languages missing from the platform catalog must stay reachable.
  for (const v of props.variants) {
    if (!v.languageTag || seenTags.has(v.languageTag.toLowerCase())) continue
    existing.push({
      id: `goto:${v.id}`,
      label: `${v.languageTag} — ${v.name}`,
      icon: 'languages',
      disabled: v.id === props.metadataId,
    })
  }

  const items: OverflowMenuItem[] = [...existing]
  if (missing.length) {
    if (items.length) items.push({ id: 'separator-missing', label: '', separator: true })
    items.push(...missing)
  }
  items.push({ id: 'separator-custom', label: '', separator: true })
  items.push({ id: 'custom', label: 'Custom Language…', icon: 'plus' })
  return items
})

const pendingCreate = ref<{ tag: string; name: string } | null>(null)
const creating = ref(false)
const customModalOpen = ref(false)

const existingLanguages = computed(() => props.variants.map(v => v.languageTag))

function navigate(id: string) {
  window.location.href = `${props.urlPrefix}${id}`
}

function onSelect(id: string) {
  if (id.startsWith('goto:')) {
    navigate(id.slice('goto:'.length))
  } else if (id.startsWith('create:')) {
    const tag = id.slice('create:'.length)
    const language = languageManager.getLanguage(tag)
    pendingCreate.value = { tag, name: language?.name ?? tag }
  } else if (id === 'custom') {
    customModalOpen.value = true
  }
}

async function onConfirmCreate() {
  const pending = pendingCreate.value
  if (!pending || creating.value) return
  creating.value = true
  try {
    const result = await gqlMutation<{
      content: { metadata: { addLanguageVariant: { id: string } } }
    }>(addVariantGql, {
      id: props.parentId || props.metadataId,
      version: props.metadataVersion,
      languageTag: pending.tag,
    })
    const newId = result?.content?.metadata?.addLanguageVariant?.id
    toast.success(`Language variant "${pending.tag}" created`)
    pendingCreate.value = null
    if (newId) navigate(newId)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create language variant')
  } finally {
    creating.value = false
  }
}

function onCustomCreated(newId: string) {
  customModalOpen.value = false
  if (newId) navigate(newId)
}
</script>

<template>
  <OverflowMenu :items="menuItems" @select="onSelect">
    <template #default="{ toggle }">
      <Button
        size="sm"
        icon="languages"
        :title="`Language variants (${variants.length || 1})`"
        @click="toggle">
        {{ triggerLabel }}
      </Button>
    </template>
  </OverflowMenu>

  <ConfirmModal
    v-if="pendingCreate"
    :title="`Create ${pendingCreate.name} (${pendingCreate.tag}) variant?`"
    subtitle="A copy of this content will be created for translation."
    confirm-label="Create Variant"
    :loading="creating"
    @close="pendingCreate = null"
    @confirm="onConfirmCreate"
  />

  <AddLanguageVariantModal
    v-if="customModalOpen"
    :metadata-id="parentId || metadataId"
    :metadata-version="metadataVersion"
    :existing-languages="existingLanguages"
    :accent="accent"
    @close="customModalOpen = false"
    @created="onCustomCreated"
  />
</template>
