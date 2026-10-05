<script setup lang="ts">
import gql from 'graphql-tag'

const props = withDefaults(defineProps<{
  accent?: string
  projects: Array<{ id: string; key: string; name: string }>
  defaultProjectId?: string
}>(), {
  accent: '#a78bff',
  defaultProjectId: '',
})

const emit = defineEmits<{
  close: []
  created: [specId: string]
}>()

const { mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const submitting = ref(false)
const specName = ref('')
const selectedProjectId = ref(props.defaultProjectId || '')
const parentSpecKey = ref('')

watch(() => props.projects, (ps) => {
  if (!selectedProjectId.value && ps.length && ps[0]) selectedProjectId.value = ps[0].id
}, { immediate: true })

const createMetadataGql = gql`
  mutation CreateMetadata($metadata: MetadataInput!) {
    content {
      metadata {
        add(metadata: $metadata) {
          id
        }
      }
    }
  }
`

const createSpecGql = gql`
  mutation CreateSpec($input: CreateWorkOpsSpecInput!) {
    workOps { specs { create(input: $input) { id key } } }
  }
`

const specByKeyGql = gql`
  query SpecByKey($key: String!) {
    workOps { specs { specByKey(key: $key) { id } } }
  }
`

async function submit() {
  if (!specName.value.trim() || !selectedProjectId.value) return
  submitting.value = true
  try {
    const metaResult = await gqlMutation<{ content: { metadata: { add: { id: string } } } }>(
      createMetadataGql,
      { metadata: { name: specName.value.trim(), contentType: 'bosca/v-document', languageTag: 'en' } },
    )
    const metadataId = metaResult.content.metadata.add.id

    let parentSpecId: string | undefined
    if (parentSpecKey.value.trim()) {
      const parentResult = await gqlQuery<{ workOps: { specs: { specByKey: { id: string } | null } } }>(
        specByKeyGql, { key: parentSpecKey.value.trim().toUpperCase() },
      )
      parentSpecId = parentResult.workOps?.specs?.specByKey?.id
      if (!parentSpecId) {
        toast.error('Parent spec not found')
        submitting.value = false
        return
      }
    }

    const specResult = await gqlMutation<{ workOps: { specs: { create: { id: string; key: string } } } }>(
      createSpecGql,
      {
        input: {
          metadataId,
          projectId: selectedProjectId.value,
          parentSpecId: parentSpecId || null,
        },
      },
    )
    const spec = specResult.workOps.specs.create
    toast.success(`Spec ${spec.key} created`)
    emit('created', spec.id)
    emit('close')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create spec')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Modal
    title="New Spec"
    icon="file-text"
    :accent="accent"
    @close="emit('close')">
    <div class="form-stack">
      <TextInput
        v-model="specName"
        label="Name"
        placeholder="e.g. Authentication Flow Redesign"
        autofocus
        @keydown.enter.meta="submit"
        @keydown.enter.ctrl="submit"
      />
      <Select
        v-model="selectedProjectId"
        label="Project"
        :options="projects.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` }))"
      />
      <TextInput
        v-model="parentSpecKey"
        label="Parent Spec (optional)"
        placeholder="e.g. PROJ-S1"
      />
    </div>
    <template #footer>
      <span class="shortcut-hint">
        <span class="mono">⌘ ↵</span> to create
      </span>
      <span class="spacer" />
      <Button size="sm" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!specName.trim() || !selectedProjectId || submitting"
        @click="submit"
      >
        {{ submitting ? 'Creating…' : 'Create Spec' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.shortcut-hint {
  font-size: 11px;
  color: var(--fg-3);
  display: flex;
  align-items: center;
  gap: 4px;
}

.spacer { flex: 1; }
</style>
