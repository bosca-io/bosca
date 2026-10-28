<script setup lang="ts">
import gql from 'graphql-tag'
import type { OverflowMenuItem } from '@bosca/ui'

withDefaults(defineProps<{
  accent?: string
}>(), {
  accent: '#5ec5ff',
})

/** Lets pages open the menu programmatically (e.g. the command palette's ?new=1 trigger). */
const open = defineModel<boolean>('open', { default: false })

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()

const templatesGql = gql`
  query GetGuideTemplates {
    content {
      guideTemplates {
        all {
          metadata {
            id
            version
            name
          }
        }
      }
    }
  }
`

// addGuide is the only creation path: it builds the guide's document and
// first step from the template. Creating the metadata directly (with only
// a guide input) leaves the guide without a document, which breaks the
// intro editor's saves. setReady: true moves the new guide out of the
// pending workflow state so it lands in the editor as an editable draft.
const addGuideGql = gql`
  mutation AddGuide($templateId: UUID!, $templateVersion: Int!) {
    content {
      metadata {
        addGuide(templateId: $templateId, templateVersion: $templateVersion, setReady: true) {
          id
        }
      }
    }
  }
`

interface TemplateInfo {
  id: string
  version: number
  name: string
}

const templates = ref<TemplateInfo[]>([])
const templatesLoading = ref(true)
const creating = ref(false)

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      content: {
        guideTemplates: {
          all: Array<{ metadata: TemplateInfo | null }>
        }
      }
    }>(templatesGql)
    templates.value = result.content.guideTemplates.all
      .map(t => t.metadata)
      .filter((m): m is TemplateInfo => m != null)
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch (e) {
    console.error('Failed to load guide templates', e)
  } finally {
    templatesLoading.value = false
  }
})

const menuItems = computed<OverflowMenuItem[]>(() => {
  if (templatesLoading.value) {
    return [{ id: 'loading', label: 'Loading templates…', disabled: true }]
  }
  if (templates.value.length === 0) {
    return [{ id: 'empty', label: 'No templates available', disabled: true }]
  }
  return templates.value.map(t => ({
    id: t.id,
    label: t.name,
    icon: 'route',
  }))
})

async function onSelect(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template || creating.value) return
  creating.value = true
  try {
    const result = await gqlMutation<{
      content: { metadata: { addGuide: { id: string } } }
    }>(addGuideGql, {
      templateId: template.id,
      templateVersion: template.version,
    })
    const newId = result?.content?.metadata?.addGuide?.id
    if (newId) {
      await router.push(getEditorRoute(newId, 'bosca/v-guide'))
    }
  } catch (e) {
    console.error('Failed to create guide', e)
    toast.error('Failed to create guide')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <OverflowMenu
    v-model:open="open"
    :items="menuItems"
    @select="onSelect"
  >
    <template #default="{ toggle }">
      <Button
        primary
        icon="plus"
        size="sm"
        :accent="accent"
        :disabled="creating"
        @click="toggle">
        New Guide
      </Button>
    </template>
  </OverflowMenu>
</template>
