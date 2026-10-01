<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const { tiles: statTiles } = useDashboardStats('audience.profiles', { accent })
const toast = useToast()

const searchQuery = ref('')
const offset = ref(0)
const limit = ref(15)
const createModalOpen = ref(false)
const creating = ref(false)

const newProfile = reactive({
  name: '',
  slug: '',
  visibility: 'USER',
})

const filter = '_type = "profile" AND contentType = "bosca/v-profile-generic"'

const listGql = gql`
  query GetAllProfiles($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          profile {
            id
            name
            type
            created
            lastLogin
            deletedAt
            organizations {
              id
              attributes
              memberCount
            }
            attributes {
              typeId
              attributes
            }
            principal {
              verified
            }
          }
        }
        estimatedHits
      }
    }
  }
`

const addProfileGql = gql`
  mutation AddProfile($profile: ProfileInput!) {
    profiles {
      add(profile: $profile, linkToPrincipal: false) {
        id
        name
      }
    }
  }
`

interface SearchProfile {
  id: string
  name: string
  type: string
  created: string
  lastLogin: string | null
  deletedAt: string | null
  organizations: Array<{
    id: string
    attributes: Record<string, unknown>
    memberCount: number
  }>
  attributes: Array<{
    typeId: string
    attributes: Record<string, unknown> | null
  }>
  principal: {
    verified: boolean
  } | null
}

const { data, status } = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ profile: SearchProfile | null }>
      estimatedHits: number
    }
  }
}>('profiles', listGql, {
  query: searchQuery,
  filter,
  limit,
  offset,
})

const profiles = computed<SearchProfile[]>(() =>
  data.value?.search?.search?.documents
    ?.map(d => d.profile)
    ?.filter((p): p is SearchProfile => p != null) ?? [],
)

const totalHits = computed(() => data.value?.search?.search?.estimatedHits ?? 0)
const totalPages = computed(() => Math.ceil(totalHits.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const isLoading = computed(() => status.value === 'pending')

function getEmail(p: SearchProfile): string {
  const email = p.attributes
    .find(attribute => attribute.typeId === 'bosca.profiles.email')
    ?.attributes?.email
  return typeof email === 'string' ? email : ''
}

function getOrgName(p: SearchProfile): string {
  return (p.organizations[0]?.attributes?.name as string | undefined) ?? ''
}

type ProfileStatus = 'deleted' | 'verified' | 'unverified' | 'no account'

function getStatus(p: SearchProfile): ProfileStatus {
  if (p.deletedAt) return 'deleted'
  if (!p.principal) return 'no account'
  return p.principal.verified ? 'verified' : 'unverified'
}

function getLastLogin(p: SearchProfile): string {
  if (!p.lastLogin) return '--'
  const ms = Date.now() - new Date(p.lastLogin).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h`
  const days = Math.floor(hrs / 24)
  return `${days}d`
}

function statusClass(s: ProfileStatus) {
  if (s === 'deleted') return 'status-deleted'
  if (s === 'verified') return 'status-verified'
  if (s === 'unverified') return 'status-unverified'
  return 'status-none'
}

function goToPage(page: number) {
  offset.value = (page - 1) * limit.value
}

function openProfileDetail(row: SearchProfile) {
  router.push(`/audience/profiles/${row.id}`)
}

function openCreate() {
  newProfile.name = ''
  newProfile.slug = ''
  newProfile.visibility = 'USER'
  createModalOpen.value = true
}

async function onCreate() {
  if (!newProfile.name.trim()) return
  creating.value = true
  try {
    const result = await gqlMutation<{ profiles: { add: { id: string } } }>(addProfileGql, {
      profile: {
        name: newProfile.name.trim(),
        slug: newProfile.slug.trim() || null,
        visibility: newProfile.visibility,
        attributes: [],
      },
    })
    const id = result.profiles.add.id
    toast.success('Profile created')
    createModalOpen.value = false
    router.push(`/audience/profiles/${id}`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create profile')
  } finally {
    creating.value = false
  }
}

const profileColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'org', label: 'Organization', width: '1fr', muted: true },
  { key: 'lastLogin', label: 'Last login', width: '90px', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Profiles')"
        title="Profiles"
        :subtitle="`${totalHits.toLocaleString()} total`"
        :tabs="['All']"
        active-tab="All"
      >
        <template #actions>
          <Button icon="filter" size="sm">Filter</Button>
          <Button icon="globe" size="sm">Export</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Profile</Button>
        </template>
      </PageHeader>
    </template>

    <StatGrid v-if="statTiles.length" :columns="statTiles.length">
      <StatTile
        v-for="tile in statTiles"
        :key="tile.label"
        :label="tile.label"
        :value="tile.value"
        :sub="tile.sub"
        :accent="tile.accent"
      />
    </StatGrid>

    <div class="search-bar">
      <SearchInput v-model="searchQuery" placeholder="Search profiles…" />
    </div>

    <SectionCard title="Profiles" glass>
      <template #right>
        <span v-if="totalPages > 0" class="mono page-label">
          page {{ currentPage }} of {{ totalPages.toLocaleString() }}
        </span>
      </template>

      <GlassTable
        :columns="profileColumns"
        :rows="profiles"
        :loading="isLoading && profiles.length === 0"
        row-key="id"
        empty-text="No profiles found."
        arrow
        @row-click="(row: SearchProfile) => openProfileDetail(row)"
      >
        <template #col-name="{ row }">
          <div class="name-cell">
            <Avatar :name="row.name" :size="28" />
            <div>
              <div class="profile-name">{{ row.name }}</div>
              <div class="profile-email">{{ getEmail(row as SearchProfile) }}</div>
            </div>
          </div>
        </template>
        <template #col-org="{ row }">
          {{ getOrgName(row as SearchProfile) || '--' }}
        </template>
        <template #col-lastLogin="{ row }">
          <span class="mono tabular">{{ getLastLogin(row as SearchProfile) }}</span>
        </template>
        <template #col-status="{ row }">
          <span :class="['status-inline', statusClass(getStatus(row as SearchProfile))]">
            <span class="dot" />
            {{ getStatus(row as SearchProfile) }}
          </span>
        </template>
      </GlassTable>

      <Pagination
        v-if="totalPages > 1"
        :page="currentPage"
        :total-pages="totalPages"
        @prev="goToPage(currentPage - 1)"
        @next="goToPage(currentPage + 1)"
      />
    </SectionCard>

    <Modal
      v-if="createModalOpen"
      title="New Profile"
      icon="user"
      :accent="accent"
      @close="createModalOpen = false"
    >
      <TextInput v-model="newProfile.name" label="Name" placeholder="Profile name" />
      <TextInput v-model="newProfile.slug" label="Slug" placeholder="Optional slug" />
      <Select
        v-model="newProfile.visibility"
        label="Visibility"
        :options="[
          { value: 'PUBLIC', label: 'Public' },
          { value: 'FRIENDS', label: 'Friends' },
          { value: 'FRIENDS_OF_FRIENDS', label: 'Friends of Friends' },
          { value: 'USER', label: 'User' },
          { value: 'SYSTEM', label: 'System' },
        ]"
      />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="createModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="creating || !newProfile.name.trim()"
          @click="onCreate">
          {{ creating ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.search-bar {
  margin-bottom: 14px;
}

.page-label {
  font-size: 11px;
  color: var(--fg-3);
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: normal;
}

.profile-name {
  font-weight: 500;
  color: var(--fg-0);
}

.profile-email {
  font-size: 11.5px;
  color: var(--fg-3);
}

.spacer {
  flex: 1;
}

.status-inline {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
}

.status-verified { color: var(--ok); }
.status-deleted { color: var(--err); }
.status-unverified { color: var(--warn, #e3b341); }
.status-none { color: var(--fg-3); }

.dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
</style>
