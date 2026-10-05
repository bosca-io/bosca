<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// Optional store filter, populated from the active company. The audit log has no company column, so
// company selection only scopes the store dropdown — store-bound entries filter by storeId.
const storesGql = gql`query CommerceAuditStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-audit-stores', storesGql, { companyId },
)
const storeOptions = computed<SelectOption[]>(() => [
  { value: '', label: 'Any store' },
  ...(storeData.value?.ecom?.stores ?? []).map(s => ({ value: s.id, label: s.name })),
])

const entityType = ref('')
const storeFilter = ref('')
const action = ref('')

// Empty filters resolve to null (matches all) — never undefined, which would skip the query entirely.
const entityTypeArg = computed(() => entityType.value || null)
const storeArg = computed(() => storeFilter.value || null)
const actionArg = computed(() => action.value.trim() || null)

interface AuditRow {
  id: string
  entityType: string
  entityId: string
  action: string
  principalId: string | null
  profileId: string | null
  storeId: string | null
  before: unknown
  after: unknown
  details: unknown
  created: string
}

const listGql = gql`
  query CommerceAudit($entityType: String, $storeId: UUID, $action: String, $offset: Int!, $limit: Int!) {
    ecom {
      audit(entityType: $entityType, storeId: $storeId, action: $action, offset: $offset, limit: $limit) {
        id
        entityType
        entityId
        action
        principalId
        profileId
        storeId
        before
        after
        details
        created
      }
    }
  }
`
const { rows: entries, hasMore, offset, pageSize, status, error: loadError } = usePagedList<AuditRow>(
  'commerce-audit', listGql, { entityType: entityTypeArg, storeId: storeArg, action: actionArg },
  d => (d as { ecom?: { audit?: AuditRow[] } })?.ecom?.audit,
)

const columns: GlassTableColumn[] = [
  { key: 'created', label: 'When', width: '160px', muted: true },
  { key: 'entity', label: 'Entity', width: 'minmax(180px, 1.5fr)' },
  { key: 'action', label: 'Action', width: '140px' },
  { key: 'actor', label: 'Actor', width: '120px', muted: true },
  { key: 'store', label: 'Store', width: '110px', muted: true },
]

const selected = ref<AuditRow | null>(null)

function pretty(v: unknown): string {
  if (v == null) return '—'
  try { return JSON.stringify(v, null, 2) } catch { return String(v) }
}
function fmtDate(iso: string): string { return iso ? new Date(iso).toLocaleString() : '—' }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Audit Log')"
        title="Audit Log"
        :subtitle="`${entries.length} entries`">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <div class="pick"><Select v-model="entityType" :options="AUDIT_ENTITY_TYPE_OPTIONS" size="sm" /></div>
      <div class="pick"><Select
        v-model="storeFilter"
        :options="storeOptions"
        size="sm"
        :disabled="!storeOptions.length" /></div>
      <div class="pick"><SearchInput v-model="action" placeholder="Action (e.g. created)…" max-width="100%" /></div>
    </div>

    <div v-if="loadError" class="query-error">Couldn't load the audit log — {{ loadError.message }}</div>

    <GlassTable
      :columns="columns"
      :rows="entries"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No audit entries match these filters."
      @row-click="(row) => selected = row">
      <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
      <template #col-entity="{ row }">
        <span class="ent">{{ row.entityType }}</span> <code class="mono">{{ row.entityId.slice(0, 8) }}</code>
      </template>
      <template #col-action="{ row }"><Badge :color="accent">{{ row.action }}</Badge></template>
      <template #col-actor="{ row }">
        <code v-if="row.principalId" class="mono">{{ row.principalId.slice(0, 8) }}</code><span v-else>system</span>
      </template>
      <template #col-store="{ row }">
        <code v-if="row.storeId" class="mono">{{ row.storeId.slice(0, 8) }}</code><span v-else>—</span>
      </template>
    </GlassTable>
    <ListPager
      v-model:offset="offset"
      :page-size="pageSize"
      :count="entries.length"
      :has-more="hasMore" />

    <Modal
      v-if="selected"
      :title="`${selected.entityType} · ${selected.action}`"
      icon="history"
      :accent="accent"
      @close="selected = null">
      <div class="detail">
        <dl class="kv">
          <dt>Entity</dt><dd><code class="mono">{{ selected.entityId }}</code></dd>
          <dt>When</dt><dd>{{ fmtDate(selected.created) }}</dd>
          <dt>Actor</dt><dd><code v-if="selected.principalId" class="mono">{{ selected.principalId }}</code><span v-else>system</span></dd>
          <dt v-if="selected.storeId">Store</dt><dd v-if="selected.storeId"><code class="mono">{{ selected.storeId }}</code></dd>
        </dl>
        <template v-if="selected.before != null">
          <h4>Before</h4><pre class="json">{{ pretty(selected.before) }}</pre>
        </template>
        <template v-if="selected.after != null">
          <h4>After</h4><pre class="json">{{ pretty(selected.after) }}</pre>
        </template>
        <template v-if="selected.details != null">
          <h4>Details</h4><pre class="json">{{ pretty(selected.details) }}</pre>
        </template>
      </div>
    </Modal>
  </PageShell>
</template>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.ent { font-weight: 500; }
.detail { display: flex; flex-direction: column; gap: 12px; }
.detail h4 { margin: 6px 0 0; font-size: 12px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.04em; }
.kv { display: grid; grid-template-columns: 90px 1fr; gap: 6px 16px; margin: 0; }
.kv dt { color: var(--fg-3); font-size: 12.5px; }
.kv dd { margin: 0; font-size: 13px; }
.json { background: var(--bg-3); border: 1px solid var(--line); border-radius: 6px; padding: 10px; font-size: 12px; overflow-x: auto; max-height: 280px; overflow-y: auto; margin: 0; }
.pick { width: 200px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
