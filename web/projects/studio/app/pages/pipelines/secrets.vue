<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * Node secrets: named, AES/GCM-encrypted credentials a pipeline node
 * references by name and resolves at execution. The value is write-only — it is encrypted at rest and
 * never returned, so this page lists names + timestamps and lets an admin set (create/replace) or
 * delete a secret.
 */

const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation } = useGraphQL()
const toast = useToast()

interface SecretRow {
  name: string
  createdAt: string
  modifiedAt: string
}

const secrets = ref<SecretRow[]>([])
const loading = ref(false)
const newName = ref('')
const newValue = ref('')
const saving = ref(false)
const deleting = ref<string | null>(null)

const SECRETS_QUERY = gql`
  query GetPipelineSecrets {
    pipelines { secrets { name createdAt modifiedAt } }
  }
`
const SET_SECRET_MUTATION = gql`
  mutation SetPipelineSecret($name: String!, $value: String!) {
    pipelines { setSecret(name: $name, value: $value) { name } }
  }
`
const DELETE_SECRET_MUTATION = gql`
  mutation DeletePipelineSecret($name: String!) {
    pipelines { deleteSecret(name: $name) }
  }
`

async function refresh() {
  loading.value = true
  try {
    const res = await gqlQuery<{ pipelines: { secrets: SecretRow[] } }>(SECRETS_QUERY)
    secrets.value = res?.pipelines?.secrets ?? []
  }
  catch {
    toast.error('Failed to load secrets')
  }
  finally {
    loading.value = false
  }
}

const canSave = computed(() => newName.value.trim().length > 0 && newValue.value.length > 0)

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    await mutation(SET_SECRET_MUTATION, { name: newName.value.trim(), value: newValue.value })
    toast.success(`Secret "${newName.value.trim()}" saved`)
    newName.value = ''
    newValue.value = ''
    await refresh()
  }
  catch {
    toast.error('Failed to save the secret')
  }
  finally {
    saving.value = false
  }
}

async function remove(secret: SecretRow) {
  deleting.value = secret.name
  try {
    await mutation(DELETE_SECRET_MUTATION, { name: secret.name })
    toast.success(`Secret "${secret.name}" deleted`)
    await refresh()
  }
  catch {
    toast.error('Failed to delete the secret')
  }
  finally {
    deleting.value = null
  }
}

onMounted(refresh)

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1.5fr)' },
  { key: 'modifiedAt', label: 'Updated', width: '120px' },
  { key: 'actions', label: '', width: '120px', align: 'right' as const },
])

function formatDate(d: string): string {
  return new Date(d).toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Pipelines', 'Secrets')"
        title="Secrets"
        subtitle="Named, encrypted credentials a node resolves at execution — values are write-only"
      >
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            @click="refresh()"
          >
            Refresh
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      title="Add or replace a secret"
      subtitle="The value is encrypted at rest and never returned. Setting an existing name replaces its value."
      padded
    >
      <div class="add-secret">
        <TextInput
          :model-value="newName"
          label="Name"
          mono
          placeholder="e.g. slack-webhook-token"
          @update:model-value="(v: string) => newName = v"
        />
        <TextInput
          :model-value="newValue"
          label="Value"
          type="password"
          placeholder="the secret value"
          @update:model-value="(v: string) => newValue = v"
        />
        <Button
          variant="primary"
          icon="check"
          :loading="saving"
          :disabled="!canSave || saving"
          @click="save()"
        >
          Save
        </Button>
      </div>
    </SectionCard>

    <SectionCard
      title="Secrets"
      subtitle="Existing node secrets — names and timestamps only."
    >
      <GlassTable
        :columns="columns"
        :rows="secrets"
        :arrow="false"
        :loading="loading"
        loading-text="Loading secrets…"
        empty-text="No secrets defined."
      >
        <template #col-name="{ row }">
          <span class="secret-name">{{ row.name }}</span>
        </template>
        <template #col-modifiedAt="{ row }">
          {{ formatDate(row.modifiedAt) }}
        </template>
        <template #col-actions="{ row }">
          <Button
            size="xs"
            variant="ghost"
            icon="x"
            :loading="deleting === row.name"
            @click="remove(row)"
          >
            Delete
          </Button>
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.add-secret {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.add-secret > :first-child {
  flex: 1 1 240px;
}
.add-secret > :nth-child(2) {
  flex: 1 1 240px;
}
.secret-name {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--text, #e2e8f0);
}
</style>
