<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const resourceId = computed(() => route.params.id as string)
const isNew = computed(() => resourceId.value === 'new')

const resourceGql = gql`
  query GetAgentResource($id: UUID!) {
    ai { agentResources { resource(id: $id) {
      id key name description configuration
      staticText metadataId documentMetadataId documentVersion contentMetadataId
      scriptId graphqlOperation graphqlInputTransform graphqlOutputTransform
      gitRepositoryId gitPath lastSyncError
    } } }
  }
`
const addGql = gql`mutation AddAgentResource($resource: AgentResourceInput!) { ai { agentResources { add(resource: $resource) { id } } } }`
const editGql = gql`mutation EditAgentResource($id: UUID!, $resource: AgentResourceInput!) { ai { agentResources { edit(id: $id, resource: $resource) { id } } } }`

interface AiResource {
  id: string; key: string; name: string; description: string | null; configuration: unknown
  staticText: string | null; metadataId: string | null; documentMetadataId: string | null; documentVersion: number | null
  contentMetadataId: string | null; scriptId: string | null
  graphqlOperation: string | null; graphqlInputTransform: string | null; graphqlOutputTransform: string | null
  gitRepositoryId: string | null; gitPath: string | null; lastSyncError: string | null
}

const { data, refresh } = useAsyncQuery<{ ai: { agentResources: { resource: AiResource | null } } }>(
  'ai-agent-resource-detail', resourceGql, isNew.value ? { id: '00000000-0000-0000-0000-000000000000' } : { id: resourceId },
)
const resource = computed(() => isNew.value ? null : data.value?.ai?.agentResources?.resource)
const syncError = computed(() => resource.value?.lastSyncError ?? null)
const gitLinked = computed(() => !!(resource.value?.gitRepositoryId && resource.value?.gitPath))
const showLinkModal = ref(false)
function onLinked() { showLinkModal.value = false; refresh() }

const IMPL_TYPES = [
  { value: 'staticText', label: 'Static text' },
  { value: 'metadata', label: 'Metadata (object as JSON)' },
  { value: 'documentMetadata', label: 'Metadata document body' },
  { value: 'contentMetadata', label: 'Metadata file / blob content' },
  { value: 'script', label: 'Script output' },
  { value: 'graphqlOperation', label: 'GraphQL operation output' },
]

const key = ref('')
const name = ref('')
const description = ref('')
const configuration = ref('{}')
const implType = ref('staticText')
const staticText = ref('')
const metadataId = ref('')
const documentMetadataId = ref('')
const documentVersion = ref('')
const contentMetadataId = ref('')
const scriptId = ref('')
const graphqlOperation = ref('')
const graphqlInputTransform = ref('')
const graphqlOutputTransform = ref('')
const saving = ref(false)

function deriveImplType(r: AiResource): string {
  if (r.metadataId) return 'metadata'
  if (r.documentMetadataId) return 'documentMetadata'
  if (r.contentMetadataId) return 'contentMetadata'
  if (r.scriptId) return 'script'
  if (r.graphqlOperation) return 'graphqlOperation'
  return 'staticText'
}

watch(resource, (r) => {
  if (!r) return
  key.value = r.key; name.value = r.name; description.value = r.description ?? ''
  configuration.value = r.configuration ? JSON.stringify(r.configuration, null, 2) : '{}'
  staticText.value = r.staticText ?? ''
  metadataId.value = r.metadataId ?? ''
  documentMetadataId.value = r.documentMetadataId ?? ''
  documentVersion.value = r.documentVersion != null ? String(r.documentVersion) : ''
  contentMetadataId.value = r.contentMetadataId ?? ''
  scriptId.value = r.scriptId ?? ''
  graphqlOperation.value = r.graphqlOperation ?? ''
  graphqlInputTransform.value = r.graphqlInputTransform ?? ''
  graphqlOutputTransform.value = r.graphqlOutputTransform ?? ''
  implType.value = deriveImplType(r)
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    const input: Record<string, unknown> = {
      key: key.value, name: name.value, description: description.value,
      configuration: JSON.parse(configuration.value || '{}'),
    }
    // Set exactly the selected implementation variant; leave the rest null (the
    // backend enforces the exactly-one XOR).
    switch (implType.value) {
      case 'staticText': input.staticText = staticText.value || null; break
      case 'metadata': input.metadataId = metadataId.value || null; break
      case 'documentMetadata':
        input.documentMetadataId = documentMetadataId.value || null
        input.documentVersion = documentVersion.value ? Number(documentVersion.value) : null
        break
      case 'contentMetadata': input.contentMetadataId = contentMetadataId.value || null; break
      case 'script': input.scriptId = scriptId.value || null; break
      case 'graphqlOperation':
        input.graphqlOperation = graphqlOperation.value || null
        input.graphqlInputTransform = graphqlInputTransform.value || null
        input.graphqlOutputTransform = graphqlOutputTransform.value || null
        break
    }
    if (isNew.value) {
      const r = await gqlMutation<{ ai: { agentResources: { add: { id: string } } } }>(addGql, { resource: input })
      toast.success('Resource created')
      router.replace(`/ai/agent-resources/${r.ai.agentResources.add.id}`)
    } else {
      await gqlMutation(editGql, { id: resourceId.value, resource: input })
      toast.success('Resource saved')
      refresh()
    }
  } catch {
    toast.error('Failed to save')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'Agent Resources', isNew ? 'New' : name || '…')" :title="isNew ? 'New Agent Resource' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew && !gitLinked && resource"
            size="sm"
            icon="git-branch"
            :accent="accent"
            @click="showLinkModal = true">
            Link to Git
          </Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || !key.trim() || !name.trim()"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="syncError" class="sync-error-banner">
      <strong>Git sync error:</strong> {{ syncError }}
    </div>
    <div v-else-if="gitLinked" class="git-linked-banner">
      <Icon name="git" :size="12" :color="accent" />
      Linked to Git: <code class="mono">{{ resource?.gitPath }}</code>
    </div>

    <div class="form-layout">
      <SectionCard title="Configuration" padded>
        <div class="form-grid">
          <TextInput
            v-model="key"
            label="Key"
            mono
            :disabled="!isNew" />
          <TextInput v-model="name" label="Name" />
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          class="description-field" />
      </SectionCard>

      <SectionCard title="Implementation" padded>
        <Select
          v-model="implType"
          :options="IMPL_TYPES"
          label="Source"
          :accent="accent" />

        <div class="impl-fields">
          <Textarea
            v-if="implType === 'staticText'"
            v-model="staticText"
            label="Static text"
            :rows="8"
            mono
            placeholder="Inline content returned to MCP clients…" />

          <TextInput
            v-else-if="implType === 'metadata'"
            v-model="metadataId"
            label="Metadata ID"
            mono
            placeholder="metadata UUID" />

          <div v-else-if="implType === 'documentMetadata'" class="form-grid">
            <TextInput
              v-model="documentMetadataId"
              label="Document metadata ID"
              mono
              placeholder="metadata UUID" />
            <TextInput v-model="documentVersion" label="Document version (optional)" placeholder="active version" />
          </div>

          <TextInput
            v-else-if="implType === 'contentMetadata'"
            v-model="contentMetadataId"
            label="Content metadata ID"
            mono
            placeholder="metadata UUID" />

          <TextInput
            v-else-if="implType === 'script'"
            v-model="scriptId"
            label="Script ID"
            mono
            placeholder="script UUID" />

          <div v-else-if="implType === 'graphqlOperation'" class="graphql-fields">
            <CodeEditor v-model="graphqlOperation" language="graphql" :rows="8" />
            <TextInput v-model="graphqlInputTransform" label="Input transform (optional, JSONPath-style)" mono />
            <TextInput v-model="graphqlOutputTransform" label="Output transform (optional, JSONPath-style)" mono />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Configuration (JSON)" padded>
        <CodeEditor v-model="configuration" language="json" :rows="8" />
      </SectionCard>
    </div>

    <LinkToGitModal
      v-if="showLinkModal && resource"
      entity-type="AGENT_RESOURCE"
      :entity-id="resource.id"
      :entity-key="resource.key"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked" />
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.description-field { margin-top: 14px; }
.impl-fields { margin-top: 14px; }
.graphql-fields { display: flex; flex-direction: column; gap: 12px; }
.sync-error-banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  max-width: 900px;
}
.git-linked-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in oklch, var(--brand-2) 6%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  font-size: 12.5px;
  color: var(--fg-2);
  max-width: 900px;
}
.git-linked-banner code { color: var(--fg-1); }
</style>
