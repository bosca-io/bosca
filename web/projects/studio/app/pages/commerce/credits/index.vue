<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { CompanyCreditInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface CreditRow {
  id: string
  number: string
  description: string | null
  balance: string
  paid: string
  expires: string | null
  created: string
}

const listGql = gql`
  query CommerceCompanyCredits($id: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      company(id: $id) {
        id
        credits(offset: $offset, limit: $limit) {
          id
          number
          description
          balance
          paid
          expires
          created
        }
      }
    }
  }
`
const { rows: credits, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<CreditRow>(
  'commerce-company-credits', listGql, { id: companyId },
  d => (d as { ecom?: { company?: { credits?: CreditRow[] } | null } })?.ecom?.company?.credits,
)

// Accounts for the optional "restrict to one account" binding.
const accountsGql = gql`query CommerceCreditAccounts($companyId: UUID!) { ecom { accounts(companyId: $companyId, offset: 0, limit: 500) { id type } } }`
const { data: acctData } = useAsyncQuery<{ ecom: { accounts: { id: string; type: string }[] } }>(
  'commerce-credit-accounts', accountsGql, { companyId },
)
const accountOptions = computed<SelectOption[]>(() => [
  { value: '', label: 'Any account (unrestricted)' },
  ...(acctData.value?.ecom?.accounts ?? []).map(a => ({ value: a.id, label: `${a.type} · ${a.id.slice(0, 8)}` })),
])

const columns: GlassTableColumn[] = [
  { key: 'number', label: 'Number', width: 'minmax(160px, 1.5fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
  { key: 'balance', label: 'Balance', width: '110px', align: 'right' },
  { key: 'paid', label: 'Spent', width: '110px', align: 'right' },
  { key: 'expires', label: 'Expires', width: '120px', muted: true },
]

const showIssue = ref(false)
const form = reactive({ description: '', balance: '', expires: '', accountId: '' })
const saving = ref(false)
const error = ref('')
const lastIssued = ref('')

function openIssue() {
  form.description = ''
  form.balance = ''
  form.expires = ''
  form.accountId = ''
  error.value = ''
  showIssue.value = true
}

// The redeemable number is generated server-side (legacy behavior), so it's omitted from the input
// and read back from the result.
const issueGql = gql`mutation IssueCompanyCredit($id: UUID!, $input: CompanyCreditInput!) { ecom { companies { company(id: $id) { addCredit(input: $input) { id number } } } } }`

async function handleIssue() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.balance.trim()) { error.value = 'An initial balance is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: CompanyCreditInput = {
      description: form.description.trim() || null,
      balance: form.balance.trim(),
      expires: localInputToIso(form.expires),
      accountId: form.accountId || null,
    }
    const result = await mutation<{ ecom: { companies: { company: { addCredit: { id: string; number: string } } } } }>(issueGql, {
      id: selectedId.value,
      input,
    })
    lastIssued.value = result?.ecom?.companies?.company?.addCredit?.number ?? ''
    showIssue.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to issue credit'
  } finally {
    saving.value = false
  }
}

function fmtDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : 'Never'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Store Credit')"
        title="Store Credit"
        :subtitle="selectedId ? `${credits.length} credit instruments` : 'Select a company'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedId"
            @click="openIssue">Issue Credit</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load store credit — {{ loadError.message }}</div>
    <div v-if="lastIssued" class="issued-note">
      Issued credit <code class="mono">{{ lastIssued }}</code> — give this number to the recipient.
      <button type="button" class="dismiss" @click="lastIssued = ''">×</button>
    </div>

    <div v-if="!selectedId" class="state">Select a company to view its store credit.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="credits"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No store credit issued yet. These are numbered gift/store-credit instruments spendable as COMPANY_CREDIT.">
      <template #col-number="{ row }"><code class="mono">{{ row.number }}</code></template>
      <template #col-description="{ row }">{{ row.description || '—' }}</template>
      <template #col-balance="{ row }">{{ formatMoney(row.balance) }}</template>
      <template #col-paid="{ row }">{{ formatMoney(row.paid) }}</template>
      <template #col-expires="{ row }">{{ fmtDate(row.expires) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="credits.length"
      :has-more="hasMore" />

    <Modal
      v-if="showIssue"
      title="Issue Store Credit"
      icon="credit-card"
      :accent="accent"
      @close="showIssue = false">
      <div class="form-stack">
        <p class="hint">Issues a numbered gift/store-credit instrument, spendable as a COMPANY_CREDIT payment. The redeemable number is generated automatically; spend is tracked (balance vs. spent).</p>
        <TextInput
          v-model="form.balance"
          label="Initial balance"
          placeholder="50.00"
          mono />
        <TextInput v-model="form.description" label="Description (optional)" placeholder="Holiday gift card" />
        <div class="form-row">
          <DateInput v-model="form.expires" label="Expires (optional)" type="datetime-local" />
          <Select v-model="form.accountId" label="Restrict to account" :options="accountOptions" />
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showIssue = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleIssue">{{ saving ? 'Issuing…' : 'Issue' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.issued-note { display: flex; align-items: center; gap: 8px; background: var(--bg-3); border-left: 3px solid var(--ok, #4ade80); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.dismiss { margin-left: auto; background: none; border: none; color: var(--fg-3); font-size: 16px; cursor: pointer; line-height: 1; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 180px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
