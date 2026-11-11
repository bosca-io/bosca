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
  query GetDocumentTemplates {
    content {
      collections {
        root {
          id
        }
      }
      documentTemplates {
        all {
          defaultAttributes
          metadata {
            id
            version
            name
            attributes
          }
        }
      }
      guideTemplates {
        all {
          steps {
            metadata { id }
            modules { metadata { id } }
          }
        }
      }
    }
  }
`

// setReady: true moves the new document out of the pending workflow state so
// it lands in the editor as an editable draft.
const addDocumentGql = gql`
  mutation AddDocument($parentCollectionId: UUID!, $templateId: UUID!, $templateVersion: Int!) {
    content {
      metadata {
        addDocument(parentCollectionId: $parentCollectionId, templateId: $templateId, templateVersion: $templateVersion, setReady: true) {
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
  attributes: Record<string, unknown> | null
}

interface DocumentTemplateEntry {
  defaultAttributes: Record<string, unknown> | null
  metadata: TemplateInfo | null
}

interface GuideTemplateEntry {
  steps: Array<{
    metadata: { id: string } | null
    modules: Array<{ metadata: { id: string } | null }>
  }>
}

// Platform modules seed their own document templates and stamp a `type` on
// them: WorkOps uses "WorkOps Spec" / "WorkOps Requirement" (workops migration
// V34) and Feeds uses "Feed Item" (feeds migration V4). Those documents are
// created by their module, not from the CMS. Template IDs can differ between
// environments, so match on the type.
function isSystemTemplate(entry: DocumentTemplateEntry): boolean {
  const type = entry.defaultAttributes?.type
  return typeof type === 'string' && (type.startsWith('WorkOps ') || type === 'Feed Item')
}

// Templates a guide uses for its steps or modules. Step documents are created
// inside their guide, so they don't belong in the top-level New Document menu.
function guideStepTemplateIds(guides: GuideTemplateEntry[]): Set<string> {
  const ids = new Set<string>()
  for (const guide of guides) {
    for (const step of guide.steps) {
      if (step.metadata) ids.add(step.metadata.id)
      for (const mod of step.modules) {
        if (mod.metadata) ids.add(mod.metadata.id)
      }
    }
  }
  return ids
}

const templates = ref<TemplateInfo[]>([])
const rootCollectionId = ref<string>('')
const templatesLoading = ref(true)
const creating = ref(false)

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      content: {
        collections: { root: { id: string } }
        documentTemplates: { all: DocumentTemplateEntry[] }
        guideTemplates: { all: GuideTemplateEntry[] }
      }
    }>(templatesGql)
    rootCollectionId.value = result.content.collections.root.id
    const stepIds = guideStepTemplateIds(result.content.guideTemplates.all)
    templates.value = result.content.documentTemplates.all
      .filter(t => !isSystemTemplate(t))
      .map(t => t.metadata)
      .filter((m): m is TemplateInfo => m != null)
      .filter(m => m.attributes?.['template.type.sub'] === undefined)
      .filter(m => !stepIds.has(m.id))
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch (e) {
    console.error('Failed to load document templates', e)
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
    icon: 'file',
  }))
})

async function onSelect(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template || !rootCollectionId.value || creating.value) return
  creating.value = true
  try {
    const result = await gqlMutation<{
      content: { metadata: { addDocument: { id: string } } }
    }>(addDocumentGql, {
      parentCollectionId: rootCollectionId.value,
      templateId: template.id,
      templateVersion: template.version,
    })
    const newId = result?.content?.metadata?.addDocument?.id
    if (newId) {
      await router.push(getEditorRoute(newId, 'bosca/v-document'))
    }
  } catch (e) {
    console.error('Failed to create document', e)
    toast.error('Failed to create document')
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
        New Document
      </Button>
    </template>
  </OverflowMenu>
</template>
