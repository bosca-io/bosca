<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- account selector (payments are listed per account) ---
interface AccountOpt { id: string; type: string; credit: string; customers: { profile: { name: string } }[] }
const accountsGql = gql`query CommercePayAccounts($companyId: UUID!) { ecom { accounts(companyId: $companyId, offset: 0, limit: 500) { id type credit customers { profile { name } } } } }`
const { data: acctData } = useAsyncQuery<{ ecom: { accounts: AccountOpt[] } }>(
  'commerce-pay-accounts', accountsGql, { companyId },
)
const accounts = computed(() => acctData.value?.ecom?.accounts ?? [])
function accountLabel(a: AccountOpt): string {
  const names = a.customers.map(c => c.profile?.name).filter(Boolean).join(', ')
  return `${names || a.type} · ${formatMoney(a.credit)} credit`
}
const accountOptions = computed<SelectOption[]>(() => accounts.value.map(a => ({ value: a.id, label: accountLabel(a) })))
const selectedAccountId = ref<string | null>(null)
watch(accounts, (list) => {
  if (!list.length) { selectedAccountId.value = null; return }
  if (!selectedAccountId.value || !list.some(a => a.id === selectedAccountId.value)) selectedAccountId.value = list[0]!.id
}, { immediate: true })

interface PaymentRow {
  id: string
  transactionType: string
  type: string
  amount: string
  currency: string
  nonRefundableAmount: string
  refundedAmount: string
  complete: boolean
  voided: string | null
  refunded: string | null
  note: string | null
  created: string
}
const accountId = computed(() => selectedAccountId.value ?? undefined)
const listGql = gql`
  query CommercePayments($accountId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      payments(accountId: $accountId, offset: $offset, limit: $limit) {
        id
        transactionType
        type
        amount
        currency
        nonRefundableAmount
        refundedAmount
        complete
        voided
        refunded
        note
        created
      }
    }
  }
`
const { rows: payments, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<PaymentRow>(
  'commerce-payments', listGql, { accountId }, d => (d as { ecom?: { payments?: PaymentRow[] } })?.ecom?.payments,
)

const columns: GlassTableColumn[] = [
  { key: 'tx', label: 'Transaction', width: 'minmax(180px, 1.5fr)' },
  { key: 'amount', label: 'Amount', width: '110px', align: 'right' },
  { key: 'refunded', label: 'Refunded', width: '110px', align: 'right', muted: true },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'created', label: 'When', width: '150px', muted: true },
]

function refundable(p: PaymentRow): number {
  return Number(p.amount) - Number(p.nonRefundableAmount) - Number(p.refundedAmount)
}
function statusOf(p: PaymentRow): { label: string; color: string } {
  if (p.voided) return { label: 'Voided', color: '#888' }
  if (Number(p.refundedAmount) > 0) return { label: 'Refunded', color: '#f59e0b' }
  if (p.complete) return { label: 'Complete', color: accent.value }
  return { label: 'Pending', color: '#888' }
}

const rowActions = (p: PaymentRow): OverflowMenuItem[] => {
  if (p.transactionType !== 'PAYMENT' || p.voided) return []
  const actions: OverflowMenuItem[] = []
  if (p.complete && refundable(p) > 0) {
    actions.push({ id: 'refund', label: 'Refund', icon: 'undo' })
    actions.push({ id: 'refundCredit', label: 'Refund to store credit', icon: 'credit-card' })
  }
  if (!p.complete) actions.push({ id: 'void', label: 'Void', icon: 'x', danger: true })
  return actions
}

// --- shared op modal ---
const showOp = ref(false)
const opMode = ref<'refund' | 'refundCredit' | 'void'>('refund')
const opTarget = ref<PaymentRow | null>(null)
const opAmount = ref('')
const opReason = ref('')
const opSaving = ref(false)
const opError = ref('')

function onRowAction(payload: { action: string; row: PaymentRow }) {
  opTarget.value = payload.row
  opMode.value = payload.action as 'refund' | 'refundCredit' | 'void'
  opAmount.value = opMode.value === 'void' ? '' : refundable(payload.row).toFixed(2)
  opReason.value = ''
  opError.value = ''
  showOp.value = true
}

const refundGql = gql`mutation RefundPayment($id: UUID!, $amount: Money!, $reason: String) { ecom { payments { payment(id: $id) { refund(amount: $amount, reason: $reason) { id } } } } }`
const refundCreditGql = gql`mutation RefundToCredit($id: UUID!, $amount: Money!, $reason: String) { ecom { payments { payment(id: $id) { refundToAccountCredit(amount: $amount, reason: $reason) { id } } } } }`
const voidGql = gql`mutation VoidPayment($id: UUID!, $reason: String) { ecom { payments { payment(id: $id) { void(reason: $reason) { id } } } } }`

const opTitle = computed(() =>
  opMode.value === 'refund' ? 'Refund Payment' : opMode.value === 'refundCredit' ? 'Refund to Store Credit' : 'Void Payment',
)

async function handleOp() {
  if (!opTarget.value) return
  if (opMode.value !== 'void' && !opAmount.value.trim()) { opError.value = 'Amount is required.'; return }
  opSaving.value = true
  opError.value = ''
  try {
    const reason = opReason.value.trim() || null
    if (opMode.value === 'refund') await mutation(refundGql, { id: opTarget.value.id, amount: opAmount.value.trim(), reason })
    else if (opMode.value === 'refundCredit') await mutation(refundCreditGql, { id: opTarget.value.id, amount: opAmount.value.trim(), reason })
    else await mutation(voidGql, { id: opTarget.value.id, reason })
    showOp.value = false
    await refresh()
  } catch (e: unknown) {
    opError.value = e instanceof Error ? e.message : 'Operation failed'
  } finally {
    opSaving.value = false
  }
}

function fmtDate(iso: string): string { return iso ? new Date(iso).toLocaleString() : '—' }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Payments')"
        title="Payments"
        :subtitle="selectedAccountId ? `${payments.length} transactions` : 'Select an account'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <div class="pick-wide"><Select
            v-model="selectedAccountId"
            placeholder="Account…"
            :options="accountOptions"
            size="sm" /></div>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load payments — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!accounts.length" class="state">This company has no billing accounts yet.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="payments"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No payments for this account."
      :row-actions="rowActions"
      @row-action="onRowAction">
      <template #col-tx="{ row }">
        <span class="tx">{{ row.transactionType }}</span> <span class="muted">· {{ row.type }}</span>
      </template>
      <template #col-amount="{ row }">{{ formatMoney(row.amount, row.currency) }}</template>
      <template #col-refunded="{ row }">
        <span v-if="Number(row.refundedAmount) > 0">{{ formatMoney(row.refundedAmount, row.currency) }}</span><span v-else>—</span>
      </template>
      <template #col-status="{ row }"><Badge :color="statusOf(row).color">{{ statusOf(row).label }}</Badge></template>
      <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
    </GlassTable>
    <ListPager
      v-if="selectedAccountId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="payments.length"
      :has-more="hasMore" />

    <Modal
      v-if="showOp"
      :title="opTitle"
      icon="credit-card"
      :accent="accent"
      @close="showOp = false">
      <div class="form-stack">
        <p v-if="opMode === 'refund'" class="hint">Refunds to the original payment method. Refundable: <strong>{{ formatMoney(refundable(opTarget!).toFixed(2), opTarget!.currency) }}</strong>.</p>
        <p v-else-if="opMode === 'refundCredit'" class="hint">Refunds to the account's store credit balance (atomic). Refundable: <strong>{{ formatMoney(refundable(opTarget!).toFixed(2), opTarget!.currency) }}</strong>.</p>
        <p v-else class="hint">Voids an uncaptured payment.</p>
        <TextInput
          v-if="opMode !== 'void'"
          v-model="opAmount"
          label="Amount"
          placeholder="0.00"
          mono />
        <TextInput v-model="opReason" label="Reason (optional)" />
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showOp = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="opSaving"
          @click="handleOp">{{ opSaving ? 'Working…' : opTitle.split(' ')[0] }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.tx { font-weight: 500; }
.muted { color: var(--fg-3); }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 160px; }
.pick-wide { width: 260px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
