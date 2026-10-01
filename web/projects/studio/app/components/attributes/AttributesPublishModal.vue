<script setup lang="ts">
interface PublishMetadata {
  id: string
  name: string
  public: boolean
  publicContent: boolean
  publicSupplementary: boolean
  version: number
  content: { type: string } | null
  workflow: { state: string; pending: string | null }
  [key: string]: unknown
}

interface PublishMetadataResult {
  content: { metadata: PublishMetadata | null }
}

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()

const props = defineProps<{
  metadataIds: string[]
}>()

const open = defineModel<boolean>('open')

const metadatas = ref<PublishMetadata[]>([])
const loading = ref(false)

const getMetadataGql = `
  query GetMetadataForPublish($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        name
        public
        publicContent
        publicSupplementary
        version
        content { type }
        workflow { state pending }
      }
    }
  }
`

async function load() {
  if (!open.value || !props.metadataIds.length) return
  loading.value = true
  metadatas.value = []
  try {
    for (const id of props.metadataIds) {
      const result = await gqlQuery<PublishMetadataResult>(getMetadataGql, { id })
      const m = result?.content?.metadata
      if (m) metadatas.value.push(m)
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

watch(() => open.value, (v) => { if (v) load() })
onMounted(() => { if (open.value) load() })

const STATE_COLORS: Record<string, string> = {
  draft: '#6c7388',
  review: '#5ec5ff',
  translate: '#ffb547',
  scheduled: '#a78bff',
  published: '#34d99a',
  archived: '#3a4256',
}

async function onToggle(m: PublishMetadata, field: string, value: boolean) {
  const mutations: Record<string, string> = {
    public: `mutation($id: UUID!, $public: Boolean!) { content { metadata { setPublic(id: $id, public: $public) { id } } } }`,
    publicContent: `mutation($id: UUID!, $public: Boolean!) { content { metadata { setPublicContent(id: $id, public: $public) { id } } } }`,
    publicSupplementary: `mutation($id: UUID!, $public: Boolean!) { content { metadata { setPublicSupplementary(id: $id, public: $public) { id } } } }`,
  }
  try {
    await gqlMutation(mutations[field]!, { id: m.id, public: value })
    m[field] = value
  } catch (e) {
    console.error('Toggle failed', e)
  }
}

const transitionGql = `
  mutation($id: UUID!, $version: Int!, $state: String!, $status: String!) {
    content { transitions { beginTransition(request: { metadataId: $id, version: $version, stateId: $state, status: $status }) } }
  }
`

async function onPublish(m: PublishMetadata) {
  try {
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'published', status: 'Published from studio' })
    m.workflow.state = 'published'
  } catch (e) {
    console.error('Publish failed', e)
  }
}

async function onUnpublish(m: PublishMetadata) {
  try {
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'draft', status: 'Unpublished from studio' })
    m.workflow.state = 'draft'
  } catch (e) {
    console.error('Unpublish failed', e)
  }
}
</script>

<template>
  <Modal
    v-if="open"
    title="Publish Settings"
    width="600px"
    @close="open = false"
  >
    <div v-if="loading" class="publish-loading">Loading…</div>
    <div v-else class="publish-list">
      <div v-for="m in metadatas" :key="m.id" class="publish-item">
        <div class="publish-item-header">
          <span class="publish-item-name">{{ m.name }}</span>
          <Badge v-if="m.workflow.pending" color="var(--info)">{{ m.workflow.pending }}</Badge>
          <Badge :color="STATE_COLORS[m.workflow.state] ?? STATE_COLORS.draft">{{ m.workflow.state }}</Badge>
        </div>
        <div class="publish-toggles">
          <Switch :model-value="m.public" label="Public" @update:model-value="onToggle(m, 'public', $event)" />
          <Switch :model-value="m.publicContent" label="Public Content" @update:model-value="onToggle(m, 'publicContent', $event)" />
          <Switch
            v-if="m.content?.type?.startsWith('image/')"
            :model-value="m.publicSupplementary"
            label="Public Supplementary"
            @update:model-value="onToggle(m, 'publicSupplementary', $event)"
          />
        </div>
        <div class="publish-action-row">
          <span class="spacer" />
          <Button
            v-if="m.workflow.state !== 'published'"
            size="sm"
            primary
            @click="onPublish(m)">Publish</Button>
          <Button v-else size="sm" @click="onUnpublish(m)">Unpublish</Button>
        </div>
      </div>
      <div v-if="!metadatas.length" class="publish-empty">No content selected.</div>
    </div>
  </Modal>
</template>

<style scoped>
.publish-loading {
  padding: 20px;
  text-align: center;
  color: var(--fg-3);
}

.publish-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.publish-item {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.publish-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.publish-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.publish-item-name {
  flex: 1;
  font-weight: 600;
  font-size: 13px;
  color: var(--fg-0);
}

.publish-toggles {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.publish-action-row {
  display: flex;
  align-items: center;
  margin-top: 12px;
}

.spacer { flex: 1; }

.publish-empty {
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
  padding: 12px;
}
</style>
