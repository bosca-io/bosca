<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const { tiles: statTiles } = useDashboardStats('audience.organizations', { accent })
const toast = useToast()

const searchQuery = ref('')
const offset = ref(0)
const limit = ref(12)
const createModalOpen = ref(false)
const creating = ref(false)

const newOrg = reactive({
  name: '',
  slug: '',
  visibility: 'PUBLIC',
})

const filter = '_type = "profile" AND contentType = "bosca/v-profile-organization"'

const organizationsGql = gql`
  query GetAllOrgs($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
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

const addOrgGql = gql`
  mutation AddOrganization($organization: OrganizationInput!, $profile: ProfileInput!) {
    organizations {
      add(organization: $organization, profile: $profile) {
        id
        name
      }
    }
  }
`

interface OrgProfile {
  id: string
  name: string
  type: string
  created: string
  organizations: Array<{
    id: string
    attributes: Record<string, unknown>
    memberCount: number
  }>
  attributes: Array<{
    typeId: string
    attributes: Record<string, unknown>
  }>
  principal: { verified: boolean } | null
}

const { data, status } = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ profile: OrgProfile | null }>
      estimatedHits: number
    }
  }
}>('organizations', organizationsGql, {
  query: searchQuery,
  filter,
  limit,
  offset,
})

const orgs = computed<OrgProfile[]>(() =>
  data.value?.search?.search?.documents
    ?.map(d => d.profile)
    ?.filter((p): p is OrgProfile => p != null) ?? [],
)

const totalHits = computed(() => data.value?.search?.search?.estimatedHits ?? 0)
const totalPages = computed(() => Math.ceil(totalHits.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const isLoading = computed(() => status.value === 'pending')

function getCountry(org: OrgProfile): string {
  const attr = org.attributes.find(a => a.typeId === 'bosca.profiles.country')
  return (attr?.attributes as Record<string, unknown> | undefined)?.country as string
    || (org.organizations[0]?.attributes?.country as string | undefined)
    || ''
}

function goToPage(page: number) {
  offset.value = (page - 1) * limit.value
}

function openOrgDetail(row: OrgProfile) {
  const orgId = row.organizations[0]?.id
  if (orgId) {
    router.push(`/audience/organizations/${orgId}`)
  }
}

function openCreate() {
  newOrg.name = ''
  newOrg.slug = ''
  newOrg.visibility = 'PUBLIC'
  createModalOpen.value = true
}

async function onCreate() {
  if (!newOrg.name.trim()) return
  creating.value = true
  try {
    const result = await gqlMutation<{ organizations: { add: { id: string } } }>(addOrgGql, {
      organization: {
        name: newOrg.name.trim(),
        slug: newOrg.slug.trim() || null,
        visibility: newOrg.visibility,
        attributes: {},
        systemAttributes: {},
        domains: [],
        signupEmails: [],
        signupTokens: [],
      },
      profile: {
        name: newOrg.name.trim(),
        slug: newOrg.slug.trim() || null,
        visibility: newOrg.visibility,
        attributes: [],
      },
    })
    const id = result.organizations.add.id
    toast.success('Organization created')
    createModalOpen.value = false
    router.push(`/audience/organizations/${id}`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create organization')
  } finally {
    creating.value = false
  }
}

const orgColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Organization', width: 'minmax(200px, 2fr)' },
  { key: 'country', label: 'Country', width: '1fr', muted: true },
  { key: 'members', label: 'Members', width: '90px', align: 'right', muted: true },
  { key: 'created', label: 'Created', width: '120px', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Organizations')"
        title="Organizations"
        :subtitle="`${totalHits} total`"
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
            @click="openCreate">New Organization</Button>
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
      <SearchInput v-model="searchQuery" placeholder="Search organizations…" />
    </div>

    <SectionCard title="Organizations" glass>
      <template #right>
        <span v-if="totalPages > 0" class="mono page-label">
          page {{ currentPage }} of {{ totalPages }}
        </span>
      </template>

      <GlassTable
        :columns="orgColumns"
        :rows="orgs"
        :loading="isLoading && orgs.length === 0"
        row-key="id"
        empty-text="No organizations found."
        arrow
        @row-click="(row: OrgProfile) => openOrgDetail(row)"
      >
        <template #col-name="{ row }">
          <div class="org-name-cell">
            <OrgMark :name="row.name" :size="32" />
            <div>
              <div class="org-name">{{ row.name }}</div>
              <div class="mono org-domain">{{ row.name.toLowerCase().replace(/[\s&]+/g, '-') }}.bosca.io</div>
            </div>
          </div>
        </template>
        <template #col-country="{ row }">
          {{ getCountry(row as OrgProfile) || '--' }}
        </template>
        <template #col-members="{ row }">
          <span class="tabular">{{ row.organizations[0]?.memberCount ?? 0 }}</span>
        </template>
        <template #col-created="{ row }">
          {{ new Date(row.created).toLocaleDateString() }}
        </template>
        <template #col-status="{ row }">
          <Badge :color="row.principal?.verified ? 'var(--ok)' : 'var(--warn)'">
            {{ row.principal?.verified ? 'Verified' : 'Unverified' }}
          </Badge>
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
      title="New Organization"
      icon="building"
      :accent="accent"
      @close="createModalOpen = false"
    >
      <TextInput v-model="newOrg.name" label="Name" placeholder="Organization name" />
      <TextInput v-model="newOrg.slug" label="Slug" placeholder="Optional slug" />
      <Select
        v-model="newOrg.visibility"
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
          :disabled="creating || !newOrg.name.trim()"
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

.spacer { flex: 1; }

.page-label { font-size: 11px; color: var(--fg-3); }

.org-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: normal;
}

.org-name {
  font-weight: 500;
  color: var(--fg-0);
}

.org-domain {
  font-size: 11px;
  color: var(--fg-3);
}
</style>
