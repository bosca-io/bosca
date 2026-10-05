<script setup lang="ts">
import gql from 'graphql-tag'
import type { OverflowMenuItem } from '@bosca/ui'

withDefaults(defineProps<{
  accent?: string
}>(), {
  accent: '#5ec5ff',
})

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()

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
          metadata {
            id
            version
            name
            attributes
          }
        }
      }
    }
  }
`

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

const templates = ref<TemplateInfo[]>([])
const rootCollectionId = ref<string>('')
const loading = ref(false)

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      content: {
        collections: { root: { id: string } }
        documentTemplates: {
          all: Array<{ metadata: TemplateInfo | null }>
        }
      }
    }>(templatesGql)
    rootCollectionId.value = result.content.collections.root.id
    templates.value = result.content.documentTemplates.all
      .map(t => t.metadata)
      .filter((m): m is TemplateInfo => m != null)
      .filter(m => m.attributes?.['template.type.sub'] === undefined)
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch (e) {
    console.error('Failed to load document templates', e)
  }
})

const menuItems = computed<OverflowMenuItem[]>(() =>
  templates.value.map(t => ({
    id: t.id,
    label: t.name,
    icon: 'file',
  })),
)

async function onSelect(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template || !rootCollectionId.value) return
  loading.value = true
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
      await router.push(`/cms/editor/${newId}`)
    }
  } catch (e) {
    console.error('Failed to create document', e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <OverflowMenu
    :items="menuItems"
    @select="onSelect"
  >
    <template #default="{ toggle }">
      <Button
        primary
        icon="plus"
        size="sm"
        :accent="accent"
        :disabled="loading || templates.length === 0"
        @click="toggle">
        New Document
      </Button>
    </template>
  </OverflowMenu>
</template>
