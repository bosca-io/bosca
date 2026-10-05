<script setup lang="ts">
import gql from 'graphql-tag'
import { SlugInput, type SelectOption } from '@bosca/ui'

withDefaults(defineProps<{
  accent?: string
  languageTag?: string
}>(), { accent: '#5ec5ff', languageTag: '' })

const emit = defineEmits<{
  close: []
  created: [id: string]
}>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()

const name = ref('')
const slug = ref('')
const slugInputRef = ref<InstanceType<typeof SlugInput> | null>(null)
const selectedTemplate = ref('')
const selectedCollection = ref('')
const creating = ref(false)
const error = ref('')

const slugAvailableGql = gql`
  query CheckDataSlug($slug: String!) {
    content {
      slugAvailable(slug: $slug)
    }
  }
`

async function checkSlugAvailable(slugValue: string): Promise<boolean> {
  const result = await gqlQuery<{
    content: { slugAvailable: boolean }
  }>(slugAvailableGql, { slug: slugValue })
  return result.content.slugAvailable
}

interface TemplateMeta {
  metadata: { id: string; name: string; version: number } | null
}

const templates = ref<SelectOption[]>([])
const templateVersions = ref<Map<string, number>>(new Map())
const templatesLoading = ref(true)

const templatesGql = gql`
  query GetDataTemplates {
    content {
      dataTemplates {
        all {
          metadata {
            id
            name
            version
          }
        }
      }
    }
  }
`

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      content: {
        dataTemplates: {
          all: TemplateMeta[]
        }
      }
    }>(templatesGql)
    const all = result.content.dataTemplates.all
    templates.value = all
      .filter(t => t.metadata != null)
      .map(t => ({
        value: t.metadata!.id,
        label: t.metadata!.name,
      }))
    for (const t of all) {
      if (t.metadata) {
        templateVersions.value.set(t.metadata.id, t.metadata.version)
      }
    }
    if (templates.value.length === 1) {
      selectedTemplate.value = templates.value[0]!.value
    }
  } finally {
    templatesLoading.value = false
  }
})

async function searchCollections(query: string): Promise<SelectOption[]> {
  const searchGql = gql`
    query SearchDataCollections($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
      search {
        search(query: {
          query: $query
          filter: [$filter]
          storageSystemName: "Admin Search Index"
          limit: $limit
          offset: $offset
        }) {
          documents {
            collection {
              id
              name
              type
            }
          }
        }
      }
    }
  `
  const result = await gqlQuery<{
    search: {
      search: {
        documents: Array<{ collection: { id: string; name: string; type: string } | null }>
      }
    }
  }>(searchGql, { query, filter: '_type = "collection"', limit: 20, offset: 0 })

  return result.search.search.documents
    .filter(d => d.collection != null)
    .map(d => ({
      value: d.collection!.id,
      label: d.collection!.name,
      icon: d.collection!.type === 'folder' ? 'folder' : 'boxes',
    }))
}

const addDataGql = gql`
  mutation AddData($parentCollectionId: UUID, $templateId: UUID!, $templateVersion: Int!) {
    content {
      metadata {
        addData(
          parentCollectionId: $parentCollectionId
          templateId: $templateId
          templateVersion: $templateVersion
          setReady: false
        ) {
          id
          name
        }
      }
    }
  }
`

const renameGql = gql`
  mutation RenameData($id: UUID!, $name: String!) {
    content {
      metadata {
        setMetadataName(id: $id, name: $name) {
          id
        }
      }
    }
  }
`

const setSlugGql = gql`
  mutation SetDataSlug($id: UUID!, $slug: String!) {
    content {
      metadata {
        setMetadataSlug(id: $id, slug: $slug)
      }
    }
  }
`

const slugAvailable = computed(() => slugInputRef.value?.available)

const canCreate = computed(() =>
  name.value.trim() !== ''
  && selectedTemplate.value !== ''
  && slugAvailable.value !== false
  && !creating.value,
)

async function create() {
  if (!canCreate.value) return
  creating.value = true
  error.value = ''
  try {
    const version = templateVersions.value.get(selectedTemplate.value) ?? 1
    const result = await gqlMutation<{
      content: { metadata: { addData: { id: string; name: string } } }
    }>(addDataGql, {
      parentCollectionId: selectedCollection.value || null,
      templateId: selectedTemplate.value,
      templateVersion: version,
    })
    const dataId = result.content.metadata.addData.id
    const dataName = result.content.metadata.addData.name

    if (name.value.trim() !== dataName) {
      await gqlMutation(renameGql, { id: dataId, name: name.value.trim() })
    }
    if (slug.value) {
      await gqlMutation(setSlugGql, { id: dataId, slug: slug.value })
    }
    emit('created', dataId)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create data'
  } finally {
    creating.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  }
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    create()
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')" @keydown="onKeydown">
      <div class="modal-box" @click.stop>
        <div class="modal-header">
          <span
            class="modal-icon"
            :style="{
              background: `color-mix(in oklch, ${accent} 16%, var(--bg-2))`,
              border: `1px solid color-mix(in oklch, ${accent} 28%, transparent)`,
            }">
            <Icon name="database" :size="14" :color="accent" />
          </span>
          <div class="modal-header-text">
            <div class="modal-title">New data</div>
            <div class="modal-subtitle">Create a data item from a template</div>
          </div>
          <button class="modal-close" @click="emit('close')">
            <Icon name="x" :size="14" color="var(--fg-3)" />
          </button>
        </div>

        <div class="modal-body">
          <TextInput
            v-model="name"
            label="Name"
            placeholder="Data name"
            icon="database"
            autofocus
          />

          <SlugInput
            ref="slugInputRef"
            v-model="slug"
            label="Slug"
            :source="name"
            :on-validate="checkSlugAvailable"
          />

          <div class="field-grid">
            <div class="modal-field">
              <div class="field-label">Template</div>
              <Select
                v-model="selectedTemplate"
                :options="templates"
                :loading="templatesLoading"
                searchable
                placeholder="Select template…"
                icon="database"
                :accent="accent"
              />
            </div>
            <div class="modal-field">
              <div class="field-label">Collection <span class="optional-hint">(optional)</span></div>
              <Select
                v-model="selectedCollection"
                searchable
                :on-search="searchCollections"
                placeholder="Search collections…"
                icon="folder"
                :accent="accent"
              />
            </div>
          </div>

          <div v-if="error" class="error-msg">
            <Icon name="alert" :size="13" color="#ff5d6c" />
            {{ error }}
          </div>
        </div>

        <div class="modal-footer">
          <span class="shortcut-hint">
            <Icon name="key" :size="11" color="var(--fg-3)" />
            <span class="mono">&#8984; &#9166;</span> to create
          </span>
          <span class="spacer" />
          <Button size="sm" @click="emit('close')">Cancel</Button>
          <Button
            primary
            size="sm"
            :accent="accent"
            icon="plus"
            :disabled="!canCreate"
            @click="create"
          >
            {{ creating ? 'Creating…' : 'Create data' }}
          </Button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: color-mix(in oklch, #000 55%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.modal-box {
  width: min(540px, 100%);
  max-height: 90vh;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.modal-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-header-text {
  flex: 1;
}

.modal-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.modal-subtitle {
  font-size: 11.5px;
  color: var(--fg-3);
}

.modal-close {
  color: var(--fg-3);
  padding: 6px;
}

.modal-body {
  flex: 1;
  overflow: visible;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.modal-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.optional-hint {
  font-weight: 400;
  color: var(--fg-4);
}

.error-msg {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-size: 12.5px;
  color: #ff5d6c;
  background: color-mix(in oklch, #ff5d6c 8%, var(--bg-2));
  border: 1px solid color-mix(in oklch, #ff5d6c 24%, transparent);
  border-radius: var(--r-sm);
}

.modal-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
  display: flex;
  align-items: center;
  gap: 10px;
}

.shortcut-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.spacer {
  flex: 1;
}
</style>
