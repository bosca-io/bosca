<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()

const offset = ref(0)
const limit = ref(25)
const includeDeleted = ref(false)

const principalsGql = gql`
  query GetAllPrincipals($limit: Int!, $offset: Long!, $includeDeleted: Boolean!) {
    security {
      principals {
        all(limit: $limit, offset: $offset, includeDeleted: $includeDeleted) {
          id verified primaryProfileId deletedAt
          credentials { type identifier }
          profiles { id name }
          groups { id name type }
        }
      }
    }
  }
`

interface Principal {
  id: string; verified: boolean; primaryProfileId: string | null; deletedAt: string | null
  credentials: Array<{ type: string; identifier: string }>
  profiles: Array<{ id: string; name: string }>
  groups: Array<{ id: string; name: string; type: string }>
}

const { data, status } = useAsyncQuery<{
  security: { principals: { all: Principal[] } }
}>('principals-list', principalsGql, { limit, offset, includeDeleted })

const principals = computed(() => data.value?.security?.principals?.all ?? [])

function openPrincipalDetail(row: Principal) {
  router.push(`/system/security/principals/${row.id}`)
}

const columns: GlassTableColumn[] = [
  { key: 'identifier', label: 'Identifier', width: 'minmax(200px, 2fr)' },
  { key: 'profiles', label: 'Profiles', width: '1fr' },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'groups', label: 'Groups', width: '1fr', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'Principals')"
        title="Principals"
        :subtitle="`${principals.length} principals`" />
    </template>

    <SectionCard title="Principals">
      <template #right>
        <Switch v-model="includeDeleted" label="Include deleted" :accent="accent" />
      </template>
      <GlassTable
        :columns="columns"
        :rows="principals"
        :loading="status === 'pending' && principals.length === 0"
        row-key="id"
        arrow
        empty-text="No principals."
        @row-click="(row: Principal) => openPrincipalDetail(row)">
        <template #col-identifier="{ row }">
          <div>
            <span class="mono" style="font-size: 12.5px; color: var(--fg-0)">{{ (row as Principal).credentials?.[0]?.identifier ?? row.id }}</span>
          </div>
        </template>
        <template #col-profiles="{ row }">
          <div style="display: flex; gap: 4px; flex-wrap: wrap">
            <Badge v-for="p in (row as Principal).profiles" :key="p.id" :color="p.id === (row as Principal).primaryProfileId ? accent : 'var(--fg-3)'">{{ p.name }}</Badge>
          </div>
        </template>
        <template #col-status="{ row }">
          <Badge v-if="(row as Principal).deletedAt" color="var(--err)">Deleted</Badge>
          <Badge v-else :color="(row as Principal).verified ? '#34d99a' : '#ffb547'">{{ (row as Principal).verified ? 'Verified' : 'Unverified' }}</Badge>
        </template>
        <template #col-groups="{ row }">
          {{ (row as Principal).groups?.filter(g => g.type === 'SYSTEM').map(g => g.name).join(', ') || '—' }}
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>
