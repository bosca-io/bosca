<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const createModalOpen = ref(false)
const creating = ref(false)

const newGroup = reactive({
  name: '',
  description: '',
  type: 'SMALL_GROUP',
  visibility: 'PUBLIC',
})

const groupsGql = gql`
  query GetCommunityGroups($limit: Int!, $offset: Long!) {
    community {
      all {
        groups(limit: $limit, offset: $offset) {
          id
          name
          description
          type
          visibility
        }
        total
      }
    }
  }
`

const createGroupGql = gql`
  mutation CreateCommunityGroup(
    $name: String!
    $description: String!
    $type: CommunityGroupType!
    $visibility: CommunityVisibility!
  ) {
    community {
      createGroup(
        name: $name
        description: $description
        type: $type
        visibility: $visibility
      ) {
        id
        name
      }
    }
  }
`

interface Group {
  id: string
  name: string
  description: string | null
  type: string
  visibility: string
}

const { data, status, refresh } = useAsyncQuery<{
  community: {
    all: {
      groups: Group[]
      total: number
    }
  }
}>('community-groups', groupsGql, { limit: 25, offset: 0 })

const groups = computed(() => data.value?.community?.all?.groups ?? [])
const total = computed(() => data.value?.community?.all?.total ?? 0)

function openCreate() {
  newGroup.name = ''
  newGroup.description = ''
  newGroup.type = 'SMALL_GROUP'
  newGroup.visibility = 'PUBLIC'
  createModalOpen.value = true
}

async function onCreate() {
  if (!newGroup.name.trim()) return
  creating.value = true
  try {
    await gqlMutation<{ community: { createGroup: { id: string } } }>(createGroupGql, {
      name: newGroup.name.trim(),
      description: newGroup.description.trim(),
      type: newGroup.type,
      visibility: newGroup.visibility,
    })
    toast.success('Community group created')
    createModalOpen.value = false
    await refresh()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Failed to create community group'
    toast.error(message)
  } finally {
    creating.value = false
  }
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Community')"
        title="Community"
        :subtitle="`${total} groups`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate"
          >
            New Group
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Groups">
      <GlassTable
        :columns="columns"
        :rows="groups"
        :loading="status === 'pending' && groups.length === 0"
        empty-text="No community groups."
        arrow
        @row-click="(row: any) => router.push(`/audience/community/${row.id}`)"
      >
        <template #col-name="{ row }">
          <span class="group-name">{{ row.name }}</span>
        </template>
        <template #col-type="{ row }">
          <Badge :color="accent">{{ row.type }}</Badge>
        </template>
        <template #col-visibility="{ row }">{{ row.visibility }}</template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="createModalOpen"
      title="New Community Group"
      icon="users"
      :accent="accent"
      @close="createModalOpen = false"
    >
      <TextInput v-model="newGroup.name" label="Name" placeholder="Group name" />
      <TextInput v-model="newGroup.description" label="Description" placeholder="Group description" />
      <Select
        v-model="newGroup.type"
        label="Type"
        :options="[
          { value: 'SMALL_GROUP', label: 'Small Group' },
          { value: 'FAMILY', label: 'Family' },
          { value: 'CUSTOM', label: 'Custom' },
        ]"
      />
      <Select
        v-model="newGroup.visibility"
        label="Visibility"
        :options="[
          { value: 'PUBLIC', label: 'Public' },
          { value: 'PRIVATE', label: 'Private' },
          { value: 'HIDDEN', label: 'Hidden' },
        ]"
      />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="createModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="creating || !newGroup.name.trim()"
          @click="onCreate"
        >
          {{ creating ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.group-name {
  font-weight: 500;
  color: var(--fg-0);
}

.spacer {
  flex: 1;
}
</style>
