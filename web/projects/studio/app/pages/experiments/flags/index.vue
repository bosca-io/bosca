<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const flagsGql = gql`
  query GetFeatureFlags($limit: Int!, $offset: Long!) {
    featureFlags {
      all(limit: $limit, offset: $offset) {
        id key name description type status created modified
      }
    }
  }
`

const addFlagGql = gql`
  mutation AddFlag($flag: FeatureFlagInput!) {
    featureFlags { add(flag: $flag) { id } }
  }
`

interface Flag {
  id: string; key: string; name: string; description: string; status: string; type: string
  created: string; modified: string
}

const offset = ref(0)
const limit = ref(50)

const { data: flagsData, status: flagsStatus } = useAsyncQuery<{
  featureFlags: { all: Flag[] }
}>('flags-list', flagsGql, { limit, offset })

const flags = computed(() => flagsData.value?.featureFlags?.all ?? [])

const showCreate = ref(false)
useCreateFromQuery(() => { showCreate.value = true })
const newKey = ref('')
const newName = ref('')
const creating = ref(false)

const STATUS_COLORS: Record<string, string> = {
  ENABLED: '#34d99a', DISABLED: '#6c7388', DRAFT: '#5ec5ff', ARCHIVED: '#3a4256',
}

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: 'minmax(150px, 1fr)' },
  { key: 'name', label: 'Name', width: 'minmax(150px, 1fr)' },
  { key: 'type', label: 'Type', width: '100px' },
  { key: 'status', label: 'Status', width: '100px' },
]

async function handleCreate() {
  creating.value = true
  try {
    const result = await gqlMutation<{ featureFlags: { add: { id: string } } }>(addFlagGql, {
      flag: {
        key: newKey.value,
        name: newName.value || newKey.value,
        type: 'BOOLEAN',
        variations: [
          { key: 'off', name: 'Off', description: '', value: false },
          { key: 'on', name: 'On', description: '', value: true },
        ],
        defaultVariationKey: 'off',
      },
    })
    showCreate.value = false
    toast.success('Flag created')
    router.push(`/experiments/flags/${result.featureFlags.add.id}`)
  } catch { toast.error('Failed to create flag') }
  finally { creating.value = false }
}

</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Experiments', 'Feature Flags')"
        title="Feature Flags"
        :subtitle="`${flags.length} flags`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Flag</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Feature Flags">
      <GlassTable
        :columns="columns"
        :rows="flags"
        :loading="flagsStatus === 'pending' && flags.length === 0"
        empty-text="No feature flags."
        arrow
        @row-click="(row: Flag) => router.push(`/experiments/flags/${row.id}`)"
      >
        <template #col-key="{ row }"><span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.key }}</span></template>
        <template #col-name="{ row }">{{ row.name }}</template>
        <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
        <template #col-status="{ row }"><Badge :color="STATUS_COLORS[row.status] ?? '#6c7388'">{{ row.status }}</Badge></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Feature Flag"
      icon="flag"
      :accent="accent"
      @close="showCreate = false">
      <TextInput
        v-model="newKey"
        label="Key"
        mono
        placeholder="feature.flag.key"
        autofocus />
      <TextInput v-model="newName" label="Name" placeholder="Display name (optional)" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newKey.trim() || creating"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped></style>
