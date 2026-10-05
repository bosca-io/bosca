<script setup lang="ts">
import gql from 'graphql-tag'
import type { AccountInput, AccountType, AccountAddressInput, AddressType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const accountId = computed(() => route.params.id as string)

const detailGql = gql`
  query CommerceAccount($id: UUID!) {
    ecom {
      account(id: $id) {
        id
        type
        credit
        created
        modified
        company { id }
        customers { id profile { id name } }
        addresses { id type preferred address1 address2 city state country zip phone note }
      }
    }
  }
`
interface AccountDetail {
  id: string
  type: string
  credit: string
  created: string
  modified: string
  company: { id: string }
  customers: { id: string; profile: { id: string; name: string } }[]
  addresses: {
    id: string; type: string; preferred: boolean
    address1: string; address2: string | null; city: string; state: string; country: string; zip: string
    phone: string; note: string | null
  }[]
}
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { account: AccountDetail | null } }>(
  'commerce-account', detailGql, { id: accountId },
)
const account = computed(() => data.value?.ecom?.account ?? null)

// --- edit type ---
const typeForm = reactive({ type: 'CONSUMER' })
const savingType = ref(false)
const typeSaved = ref(false)
watch(account, (a) => { if (a) typeForm.type = a.type }, { immediate: true })

const editGql = gql`mutation EditAccount($id: UUID!, $input: AccountInput!) { ecom { accounts { account(id: $id) { edit(input: $input) { id type } } } } }`
async function saveType() {
  if (!account.value) return
  savingType.value = true
  typeSaved.value = false
  try {
    // customerIds:[] is safe — edit only ever ADDS attachments, never detaches.
    const input: AccountInput = { companyId: account.value.company.id, type: typeForm.type as AccountType, customerIds: [] }
    await mutation(editGql, { id: account.value.id, input })
    typeSaved.value = true
    await refresh()
  } finally {
    savingType.value = false
  }
}

// --- add credit ---
const showCredit = ref(false)
const creditForm = reactive({ amount: '', note: '' })
const creditSaving = ref(false)
const creditError = ref('')
const addCreditGql = gql`mutation AddAccountCredit($id: UUID!, $amount: Money!, $note: String) { ecom { accounts { account(id: $id) { addCredit(amount: $amount, note: $note) { id credit } } } } }`

function openCredit() { creditForm.amount = ''; creditForm.note = ''; creditError.value = ''; showCredit.value = true }
async function handleCredit() {
  if (!account.value) return
  if (!creditForm.amount.trim()) { creditError.value = 'Amount is required.'; return }
  creditSaving.value = true
  creditError.value = ''
  try {
    await mutation(addCreditGql, { id: account.value.id, amount: creditForm.amount.trim(), note: creditForm.note.trim() || null })
    showCredit.value = false
    await refresh()
  } catch (e: unknown) {
    creditError.value = e instanceof Error ? e.message : 'Failed to add credit'
  } finally {
    creditSaving.value = false
  }
}

// --- add address ---
const showAddr = ref(false)
const addrForm = reactive({
  type: 'SHIPPING', preferred: false,
  address1: '', address2: '', city: '', state: '', country: '', zip: '', phone: '', note: '',
})
const addrSaving = ref(false)
const addrError = ref('')
const addAddrGql = gql`mutation AddAccountAddress($id: UUID!, $input: AccountAddressInput!) { ecom { accounts { account(id: $id) { addAddress(input: $input) { id } } } } }`

function openAddr() {
  Object.assign(addrForm, { type: 'SHIPPING', preferred: false, address1: '', address2: '', city: '', state: '', country: '', zip: '', phone: '', note: '' })
  addrError.value = ''
  showAddr.value = true
}
async function handleAddr() {
  if (!account.value) return
  if (!addrForm.address1.trim() || !addrForm.city.trim() || !addrForm.state.trim() || !addrForm.country.trim() || !addrForm.zip.trim() || !addrForm.phone.trim()) {
    addrError.value = 'Address, city, state, country, zip, and phone are required.'
    return
  }
  addrSaving.value = true
  addrError.value = ''
  try {
    const input: AccountAddressInput = {
      type: addrForm.type as AddressType, preferred: addrForm.preferred,
      address1: addrForm.address1.trim(), address2: addrForm.address2.trim() || null,
      city: addrForm.city.trim(), state: addrForm.state.trim(), country: addrForm.country.trim(),
      zip: addrForm.zip.trim(), phone: addrForm.phone.trim(), note: addrForm.note.trim() || null,
    }
    await mutation(addAddrGql, { id: account.value.id, input })
    showAddr.value = false
    await refresh()
  } catch (e: unknown) {
    addrError.value = e instanceof Error ? e.message : 'Failed to add address'
  } finally {
    addrSaving.value = false
  }
}

// --- delete ---
const showDelete = ref(false)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteAccount($id: UUID!) { ecom { accounts { account(id: $id) { delete } } } }`
async function handleDelete() {
  if (!account.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: account.value.id })
    router.push('/commerce/accounts')
  } catch {
    deleting.value = false
  }
}

function fmt(iso?: string): string { return iso ? new Date(iso).toLocaleString() : '—' }
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="account"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Accounts', { label: `${account.type} account` })"
        :title="`${account.type} account`"
        :subtitle="`${formatMoney(account.credit)} credit · ${account.customers.length} customers`">
        <template #actions>
          <Button icon="trash" size="sm" @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load account — {{ loadError.message }}</div>
    <div v-if="status === 'pending' && !account" class="state">Loading…</div>
    <div v-else-if="!account" class="state">Account not found.</div>
    <template v-else>
      <SectionCard title="Account" padded>
        <div class="form-stack">
          <Select v-model="typeForm.type" label="Type" :options="ACCOUNT_TYPE_OPTIONS" />
          <div class="actions">
            <span v-if="typeSaved" class="saved">Saved.</span>
            <Button
              primary
              size="sm"
              :accent="accent"
              :disabled="savingType"
              @click="saveType">{{ savingType ? 'Saving…' : 'Save' }}</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Credit" padded>
        <template #right><Button
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openCredit">Add credit</Button></template>
        <p class="balance">{{ formatMoney(account.credit) }}</p>
        <p class="hint">Stored balance, spendable as ACCOUNT_CREDIT at checkout. Additions are audited; spend happens at checkout.</p>
      </SectionCard>

      <SectionCard title="Customers" padded>
        <ul v-if="account.customers.length" class="plain-list">
          <li v-for="c in account.customers" :key="c.id">{{ c.profile?.name }}</li>
        </ul>
        <p v-else class="hint">No customers attached.</p>
      </SectionCard>

      <SectionCard title="Addresses" padded>
        <template #right><Button
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openAddr">Add address</Button></template>
        <div v-if="account.addresses.length" class="addr-list">
          <div v-for="a in account.addresses" :key="a.id" class="addr">
            <div class="addr-head">
              <Badge :color="accent">{{ a.type }}</Badge>
              <span v-if="a.preferred" class="preferred">Preferred</span>
            </div>
            <div class="addr-body">
              {{ a.address1 }}<span v-if="a.address2">, {{ a.address2 }}</span><br>
              {{ a.city }}, {{ a.state }} {{ a.zip }} · {{ a.country }}<br>
              <span class="muted">{{ a.phone }}</span>
            </div>
          </div>
        </div>
        <p v-else class="hint">No saved addresses.</p>
      </SectionCard>

      <SectionCard title="Details" padded>
        <dl class="kv">
          <dt>Created</dt><dd>{{ fmt(account.created) }}</dd>
          <dt>Modified</dt><dd>{{ fmt(account.modified) }}</dd>
        </dl>
      </SectionCard>
    </template>

    <Modal
      v-if="showCredit"
      title="Add Credit"
      icon="plus"
      :accent="accent"
      @close="showCredit = false">
      <div class="form-stack">
        <TextInput
          v-model="creditForm.amount"
          label="Amount"
          placeholder="25.00"
          mono />
        <TextInput v-model="creditForm.note" label="Note (optional)" placeholder="Goodwill credit" />
        <p v-if="creditError" class="form-error">{{ creditError }}</p>
      </div>
      <template #footer>
        <Button @click="showCredit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="creditSaving"
          @click="handleCredit">{{ creditSaving ? 'Adding…' : 'Add credit' }}</Button>
      </template>
    </Modal>

    <Modal
      v-if="showAddr"
      title="Add Address"
      icon="pin"
      :accent="accent"
      @close="showAddr = false">
      <div class="form-stack">
        <div class="form-row">
          <Select v-model="addrForm.type" label="Type" :options="ADDRESS_TYPE_OPTIONS" />
          <label class="toggle"><Switch v-model="addrForm.preferred" :accent="accent" /> Preferred for its type</label>
        </div>
        <TextInput v-model="addrForm.address1" label="Address" placeholder="Street address" />
        <TextInput v-model="addrForm.address2" label="Address line 2" placeholder="Suite, unit (optional)" />
        <div class="form-row">
          <TextInput v-model="addrForm.city" label="City" />
          <TextInput v-model="addrForm.state" label="State / region" />
        </div>
        <div class="form-row">
          <TextInput v-model="addrForm.country" label="Country" placeholder="US" />
          <TextInput v-model="addrForm.zip" label="Zip / postal code" />
        </div>
        <TextInput v-model="addrForm.phone" label="Phone" />
        <TextInput v-model="addrForm.note" label="Note (optional)" />
        <p v-if="addrError" class="form-error">{{ addrError }}</p>
      </div>
      <template #footer>
        <Button @click="showAddr = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="addrSaving"
          @click="handleAddr">{{ addrSaving ? 'Adding…' : 'Add address' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Account"
      subtitle="Soft-deletes the billing account."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete this {{ account?.type }} account?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.actions { display: flex; align-items: center; gap: 12px; justify-content: flex-end; }
.saved { color: var(--ok, #4ade80); font-size: 12px; }
.balance { font-size: 22px; font-weight: 600; margin: 0 0 6px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.plain-list { margin: 0; padding-left: 18px; font-size: 13px; }
.plain-list li { margin: 2px 0; }
.addr-list { display: flex; flex-direction: column; gap: 12px; }
.addr { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
.addr-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.preferred { font-size: 11px; color: var(--ok, #4ade80); }
.addr-body { font-size: 13px; line-height: 1.5; }
.muted { color: var(--fg-3); }
.kv { display: grid; grid-template-columns: 160px 1fr; gap: 8px 16px; margin: 0; }
.kv dt { color: var(--fg-3); font-size: 12.5px; }
.kv dd { margin: 0; font-size: 13px; }
</style>
