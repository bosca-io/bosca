<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: rolesData, status: rolesStatus, refresh: refreshRoles } = useK8sRoles({ cluster: clusterId })
const { data: bindingsData, status: bindingsStatus, refresh: refreshBindings } = useK8sRoleBindings({ cluster: clusterId })
const { data: saData, status: saStatus, refresh: refreshSas } = useK8sServiceAccounts({ cluster: clusterId })

const ROLES = computed(() => rolesData.value ?? [])
const ROLE_BINDINGS = computed(() => bindingsData.value ?? [])
const SERVICE_ACCOUNTS = computed(() => saData.value ?? [])
const isLoading = computed(() => rolesStatus.value === 'pending' || bindingsStatus.value === 'pending' || saStatus.value === 'pending')

function refreshAll() {
  refreshRoles(); refreshBindings(); refreshSas()
}

// Live refresh — RBAC changes (roles, bindings, service accounts)
// re-run the list queries for all three tabs.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['Role', 'ClusterRole', 'RoleBinding', 'ClusterRoleBinding', 'ServiceAccount'],
  onChange: refreshAll,
})

type Tab = 'sas' | 'roles' | 'bindings'
const tab = ref<Tab>('roles')
const tabs = computed(() => ['Roles', 'Role Bindings', 'Service Accounts'])
const tabToVal: Record<string, Tab> = { 'Roles': 'roles', 'Role Bindings': 'bindings', 'Service Accounts': 'sas' }
const valToTab: Record<Tab, string> = { roles: 'Roles', bindings: 'Role Bindings', sas: 'Service Accounts' }

const search = ref('')

const filteredRoles = computed(() => ROLES.value.filter(r => !search.value || `${r.name} ${r.desc}`.toLowerCase().includes(search.value.toLowerCase())))
const filteredBindings = computed(() => ROLE_BINDINGS.value.filter(b => !search.value || `${b.name} ${b.role}`.toLowerCase().includes(search.value.toLowerCase())))
const filteredSas = computed(() => SERVICE_ACCOUNTS.value.filter(s => nsFilter.matches(s.ns) && (!search.value || s.name.toLowerCase().includes(search.value.toLowerCase()))))

const rolesCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 1.5fr)' },
  { key: 'kind', label: 'Kind', width: '140px' },
  { key: 'ns', label: 'Namespace', width: '110px', muted: true },
  { key: 'rules', label: 'Rules', width: '70px', align: 'right' },
  { key: 'bindings', label: 'Bindings', width: '80px', align: 'right' },
  { key: 'builtin', label: 'Built-in', width: '90px' },
  { key: 'desc', label: 'Description', width: 'minmax(220px, 2fr)', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const bindingsCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 1.5fr)' },
  { key: 'kind', label: 'Kind', width: '150px' },
  { key: 'role', label: 'Role', width: 'minmax(180px, 1.2fr)' },
  { key: 'subjects', label: 'Subjects', width: 'minmax(220px, 2fr)' },
  { key: 'ns', label: 'Namespace', width: '110px', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const sasCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 1.5fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'pods', label: 'Pods', width: '70px', align: 'right' },
  { key: 'secrets', label: 'Secrets', width: '80px', align: 'right' },
  { key: 'bindings', label: 'Bindings', width: '80px', align: 'right' },
  { key: 'irsa', label: 'IRSA / IAM', width: 'minmax(260px, 2fr)', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Access Control')"
        title="Access Control"
        :subtitle="`${ROLES.length} roles · ${ROLE_BINDINGS.length} bindings · ${SERVICE_ACCOUNTS.length} service accounts`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'roles'"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refreshAll">Refresh</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter…" class="search" />
      <K8sNamespaceFilter v-if="tab === 'sas'" />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'roles'" glass>
      <GlassTable
        :columns="rolesCols"
        :rows="filteredRoles"
        row-key="id"
        :loading="rolesStatus === 'pending'"
        loading-text="Loading roles…"
        empty-text="No roles match.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'ClusterRole' ? '#a78bff' : '#5ec5ff'">{{ row.kind }}</Badge>
        </template>
        <template #col-builtin="{ row }">
          <Badge v-if="row.builtin" color="#6c7388">built-in</Badge>
          <span v-else class="dim">—</span>
        </template>
        <template #col-desc="{ row }"><span class="dim">{{ row.desc }}</span></template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else-if="tab === 'bindings'" glass>
      <GlassTable
        :columns="bindingsCols"
        :rows="filteredBindings"
        row-key="id"
        :loading="bindingsStatus === 'pending'"
        loading-text="Loading bindings…"
        empty-text="No bindings match.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'ClusterRoleBinding' ? '#a78bff' : '#5ec5ff'">{{ row.kind }}</Badge>
        </template>
        <template #col-role="{ row }"><span class="mono">{{ row.role }}</span></template>
        <template #col-subjects="{ row }">
          <div class="subj">
            <span v-for="s in row.subjects" :key="`${s.kind}-${s.name}`" class="subj-pill mono">
              {{ s.kind === 'Group' ? '👥' : s.kind === 'User' ? '👤' : '⚙' }} {{ s.name }}{{ s.ns ? `@${s.ns}` : '' }}
            </span>
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else glass>
      <GlassTable
        :columns="sasCols"
        :rows="filteredSas"
        row-key="id"
        :loading="saStatus === 'pending'"
        loading-text="Loading service accounts…"
        empty-text="No service accounts match.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-irsa="{ row }">
          <span v-if="row.irsa" class="mono dim">{{ row.irsa }}</span>
          <span v-else class="dim">—</span>
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.subj { display: flex; flex-wrap: wrap; gap: 4px; }
.subj-pill {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 7px;
  border-radius: 4px;
  border: 1px solid var(--line);
  background: var(--bg-2);
  font-size: 11px;
  color: var(--fg-2);
}
</style>
