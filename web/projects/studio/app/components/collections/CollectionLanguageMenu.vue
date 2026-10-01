<script setup lang="ts">
/**
 * Header language menu for the collection editor. Collections localize via
 * language-variant children of the same collection (addressed by
 * `?languageTag=`), so unlike metadata there is no separate document per
 * language. Lists every platform language: the base language and existing
 * variants navigate, missing languages create a variant after confirmation.
 */
import type { OverflowMenuItem } from '@bosca/ui'

const props = defineProps<{
  /** The base collection's language tag. */
  baseTag: string
  /** Language tags that already have a variant. */
  variantTags: string[]
  /** The language currently bound in the editor. */
  currentTag: string
  accent?: string
}>()

const emit = defineEmits<{
  select: [tag: string]
  create: [tag: string]
}>()

const languageManager = useLanguage()

const triggerLabel = computed(() => {
  const language = languageManager.getLanguage(props.currentTag)
  return language ? language.name : props.currentTag
})

const existingTags = computed(() => {
  const tags = new Set<string>()
  tags.add(props.baseTag.toLowerCase())
  for (const tag of props.variantTags) tags.add(tag.toLowerCase())
  return tags
})

const menuItems = computed<OverflowMenuItem[]>(() => {
  const existing: OverflowMenuItem[] = []
  const missing: OverflowMenuItem[] = []
  const seenTags = new Set<string>()

  for (const lang of languageManager.languagesRef.value) {
    const tagLower = lang.tag.toLowerCase()
    if (seenTags.has(tagLower)) continue
    seenTags.add(tagLower)
    if (existingTags.value.has(tagLower)) {
      existing.push({
        id: `goto:${lang.tag}`,
        label: `${lang.tag} — ${lang.name}`,
        icon: 'languages',
        disabled: tagLower === props.currentTag.toLowerCase(),
      })
    } else {
      missing.push({ id: `create:${lang.tag}`, label: `${lang.tag} — ${lang.name}`, icon: 'plus' })
    }
  }

  // Existing variants in languages missing from the platform catalog must stay
  // reachable.
  for (const tag of [props.baseTag, ...props.variantTags]) {
    if (!tag || seenTags.has(tag.toLowerCase())) continue
    seenTags.add(tag.toLowerCase())
    existing.push({
      id: `goto:${tag}`,
      label: tag,
      icon: 'languages',
      disabled: tag.toLowerCase() === props.currentTag.toLowerCase(),
    })
  }

  const items: OverflowMenuItem[] = [...existing]
  if (missing.length) {
    if (items.length) items.push({ id: 'separator-missing', label: '', separator: true })
    items.push(...missing)
  }
  return items
})

const pendingCreate = ref<{ tag: string; name: string } | null>(null)

function onItemSelect(id: string) {
  if (id.startsWith('goto:')) {
    emit('select', id.slice('goto:'.length))
  } else if (id.startsWith('create:')) {
    const tag = id.slice('create:'.length)
    const language = languageManager.getLanguage(tag)
    pendingCreate.value = { tag, name: language?.name ?? tag }
  }
}

function onConfirmCreate() {
  const pending = pendingCreate.value
  if (!pending) return
  pendingCreate.value = null
  emit('create', pending.tag)
}
</script>

<template>
  <OverflowMenu :items="menuItems" @select="onItemSelect">
    <template #default="{ toggle }">
      <Button
        size="sm"
        icon="languages"
        :title="`Language variants (${variantTags.length + 1})`"
        @click="toggle">
        {{ triggerLabel }}
      </Button>
    </template>
  </OverflowMenu>

  <ConfirmModal
    v-if="pendingCreate"
    :title="`Create ${pendingCreate.name} (${pendingCreate.tag}) variant?`"
    subtitle="A language variant of this collection will be created for translation."
    confirm-label="Create Variant"
    @close="pendingCreate = null"
    @confirm="onConfirmCreate"
  />
</template>
