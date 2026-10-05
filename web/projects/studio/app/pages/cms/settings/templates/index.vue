<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const templatesGql = gql`
  query GetTemplates {
    content {
      documentTemplates { all { metadata { id name } } }
      dataTemplates { all { metadata { id name } } }
      guideTemplates { all { metadata { id name } } }
      collections { templates { all { metadata { id name } } } }
    }
  }
`

const addTemplateGql = gql`
  mutation AddTemplate($input: MetadataInput!) {
    content { metadata { add(metadata: $input, setReady: true) { id } } }
  }
`

interface TemplateEntry { metadata: { id: string; name: string } }
interface TemplatesData {
  content: {
    documentTemplates: { all: TemplateEntry[] }
    dataTemplates: { all: TemplateEntry[] }
    guideTemplates: { all: TemplateEntry[] }
    collections: { templates: { all: TemplateEntry[] } }
  }
}

const { data, status } = useAsyncQuery<TemplatesData>('cms-templates', templatesGql, {})

const allTemplates = computed(() => [
  ...(data.value?.content?.documentTemplates?.all ?? []).map((t) => ({ id: t.metadata.id, name: t.metadata.name, type: 'Document' })),
  ...(data.value?.content?.dataTemplates?.all ?? []).map((t) => ({ id: t.metadata.id, name: t.metadata.name, type: 'Data' })),
  ...(data.value?.content?.guideTemplates?.all ?? []).map((t) => ({ id: t.metadata.id, name: t.metadata.name, type: 'Guide' })),
  ...(data.value?.content?.collections?.templates?.all ?? []).map((t) => ({ id: t.metadata.id, name: t.metadata.name, type: 'Collection' })),
])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
]

const showCreate = ref(false)
const newName = ref('New Template')
const newType = ref('Document')
const creating = ref(false)

const TEMPLATE_TYPES = [
  { value: 'Document', label: 'Document' },
  { value: 'Data', label: 'Data' },
  { value: 'Guide', label: 'Guide' },
  { value: 'Collection', label: 'Collection' },
]

const CONTENT_TYPES: Record<string, string> = {
  Document: 'bosca/v-document-template',
  Data: 'bosca/v-data-template',
  Guide: 'bosca/v-guide-template',
  Collection: 'bosca/v-collection-template',
}

function buildTemplateInput(name: string, type: string): Record<string, unknown> {
  const base: Record<string, unknown> = {
    contentType: CONTENT_TYPES[type],
    languageTag: 'en',
    name,
    attributes: { 'editor.type': 'Template', 'template.type': type },
  }

  if (type === 'Document') {
    base.documentTemplate = {
      attributes: [],
      content: { document: { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 } }, { type: 'paragraph' }] } },
      schema: { content: 'heading block+' },
      configuration: { content: 'heading block+' },
      containers: [],
      defaultAttributes: { type: 'Item' },
    }
  } else if (type === 'Data') {
    base.dataTemplate = { attributes: [], defaultAttributes: {} }
  } else if (type === 'Guide') {
    base.documentTemplate = { attributes: [], content: {}, configuration: {}, containers: [], defaultAttributes: {} }
    base.guideTemplate = { rrule: '', steps: [], type: 'LINEAR' }
  } else if (type === 'Collection') {
    base.collectionTemplate = { attributes: [], filters: { filters: [] }, configuration: {}, defaultAttributes: {}, ordering: [] }
  }

  return base
}

async function handleCreate() {
  creating.value = true
  try {
    const input = buildTemplateInput(newName.value, newType.value)
    const result = await gqlMutation<{ content: { metadata: { add: { id: string } } } }>(addTemplateGql, { input })
    const newId = result.content.metadata.add.id
    showCreate.value = false
    toast.success('Template created')
    router.push(`/cms/settings/templates/${newId}`)
  } catch {
    toast.error('Failed to create template')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Templates')"
        title="Templates"
        :subtitle="`${allTemplates.length} templates`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Template</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Templates">
      <GlassTable
        :columns="columns"
        :rows="allTemplates"
        :loading="status === 'pending' && allTemplates.length === 0"
        empty-text="No templates."
        arrow
        @row-click="(r: { id: string }) => router.push(`/cms/settings/templates/${r.id}`)">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Template"
      icon="wand"
      :accent="accent"
      @close="showCreate = false">
      <TextInput
        v-model="newName"
        label="Name"
        placeholder="Template name"
        autofocus />
      <Select v-model="newType" :options="TEMPLATE_TYPES" label="Template Type" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || creating"
          @click="handleCreate">
          {{ creating ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>
