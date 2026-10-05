<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { companies, selected, selectedId, status } = useCommerceCompany()
const { useAsyncQuery } = useGraphQL()

const companyOptions = computed<SelectOption[]>(() =>
  companies.value.map(c => ({ value: c.id, label: c.name })),
)

// One company-scoped read drives the whole checklist — every step is derived from real data, never
// a stored flag. All fields here are ungated merchandising/admin reads (the page is admin-gated).
const companyId = computed(() => selectedId.value ?? undefined)
const setupGql = gql`
  query CommerceSetup($companyId: UUID!) {
    ecom {
      catalogs(companyId: $companyId) { id }
      manufacturers(companyId: $companyId, offset: 0, limit: 100) { id }
      products(companyId: $companyId, offset: 0, limit: 500) { id type }
      paymentProviders(companyId: $companyId) { id }
      shippingProviders(companyId: $companyId) { id }
      stores(companyId: $companyId) { id }
    }
  }
`
interface SetupData {
  ecom: {
    catalogs: { id: string }[]
    manufacturers: { id: string }[]
    products: { id: string; type: string }[]
    paymentProviders: { id: string }[]
    shippingProviders: { id: string }[]
    stores: { id: string }[]
  }
}
const { data, status: setupStatus } = useAsyncQuery<SetupData>('commerce-setup', setupGql, { companyId })

interface SetupStep {
  key: string
  label: string
  done: boolean
  count?: number
  to: string
  why: string
}

const steps = computed<SetupStep[]>(() => {
  const e = data.value?.ecom
  if (!e) return []
  const products = e.products ?? []
  return [
    {
      key: 'company',
      label: 'Create a company',
      done: true,
      count: companies.value.length,
      to: '/commerce/companies',
      why: 'The organization everything else is scoped to. You have one selected.',
    },
    {
      key: 'catalog',
      label: 'Create a catalog',
      done: e.catalogs.length > 0,
      count: e.catalogs.length,
      to: '/commerce/catalogs',
      why: 'Products are priced and sold inside a catalog.',
    },
    {
      key: 'manufacturer',
      label: 'Add a manufacturer',
      done: e.manufacturers.length > 0,
      to: '/commerce/manufacturers',
      why: 'Every product references a manufacturer.',
    },
    {
      key: 'product',
      label: 'Add a product',
      done: products.length > 0,
      count: products.length,
      to: '/commerce/products',
      why: 'The thing you sell. Name and rich content live on its backing document.',
    },
    {
      key: 'shipping-product',
      label: 'Add a shipping product, then price it',
      done: products.some(p => p.type === 'SHIPPING'),
      to: '/commerce/products',
      why: 'A product of type Shipping becomes a store’s shipping line — add it on Pricing to the catalog the store will use.',
    },
    {
      key: 'payment-provider',
      label: 'Register a payment provider',
      done: e.paymentProviders.length > 0,
      count: e.paymentProviders.length,
      to: '/commerce/providers',
      why: 'A store settles payments through it.',
    },
    {
      key: 'shipping-provider',
      label: 'Register a shipping provider',
      done: e.shippingProviders.length > 0,
      count: e.shippingProviders.length,
      to: '/commerce/providers',
      why: 'Quotes shipping rates at checkout and backs fulfillment centers.',
    },
    {
      key: 'store',
      label: 'Create a store',
      done: e.stores.length > 0,
      count: e.stores.length,
      to: '/commerce/stores',
      why: 'Binds the catalog, payment provider, and shipping line — nothing sells without a store.',
    },
  ]
})

const doneCount = computed(() => steps.value.filter(s => s.done).length)
const allDone = computed(() => steps.value.length > 0 && doneCount.value === steps.value.length)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Overview')"
        title="Commerce"
        subtitle="Products, catalogs, pricing, and orders" />
    </template>

    <SectionCard title="Active company" padded>
      <p class="hint">
        Commerce data is scoped to a company. Choose the company you're administering —
        the selection persists across the section.
      </p>
      <div class="company-row">
        <Select
          v-model="selectedId"
          placeholder="Select a company…"
          :options="companyOptions" />
      </div>
      <p v-if="status === 'pending'" class="hint">Loading companies…</p>
      <p v-else-if="!companies.length" class="hint">
        No companies yet — create one on the <NuxtLink to="/commerce/companies" class="link">Companies</NuxtLink> page to begin.
      </p>
      <p v-else-if="selected" class="hint">
        Administering <strong>{{ selected.name }}</strong>.
      </p>
    </SectionCard>

    <SectionCard v-if="selectedId" title="Setup checklist">
      <template #right>
        <span class="progress" :class="{ complete: allDone }">
          {{ allDone ? 'All set' : `${doneCount}/${steps.length}` }}
        </span>
      </template>
      <p v-if="setupStatus === 'pending' && !steps.length" class="cl-loading">Checking setup…</p>
      <div v-else class="checklist">
        <button
          v-for="step in steps"
          :key="step.key"
          type="button"
          class="step"
          :class="{ done: step.done }"
          @click="navigateTo(step.to)">
          <span class="marker">
            <Icon v-if="step.done" name="circle-check" :size="18" />
            <span v-else class="ring" />
          </span>
          <span class="body">
            <span class="label">
              {{ step.label }}
              <span v-if="step.count" class="count">· {{ step.count }}</span>
            </span>
            <span class="why">{{ step.why }}</span>
          </span>
          <Icon name="chevron-right" :size="16" class="go" />
        </button>
      </div>
      <p v-if="allDone" class="hint done-note">
        This company is fully set up. The checklist tracks live data, so it stays accurate as things change.
      </p>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.hint {
  color: var(--fg-3);
  font-size: 13px;
  margin: 0 0 10px;
}

.link { color: var(--fg-1); text-decoration: underline; }

.company-row {
  max-width: 360px;
  margin-bottom: 8px;
}

.progress {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-3);
  padding: 2px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
}
.progress.complete { color: var(--ok, #4ade80); border-color: color-mix(in oklch, var(--ok, #4ade80) 50%, transparent); }

.cl-loading { color: var(--fg-3); font-size: 13px; padding: 14px 16px; margin: 0; }

.checklist { display: flex; flex-direction: column; }

.step {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  text-align: left;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--line);
  padding: 12px 16px;
  cursor: pointer;
  color: inherit;
  font: inherit;
}
.step:last-child { border-bottom: none; }
.step:hover { background: var(--bg-2); }

.marker { display: inline-flex; width: 18px; justify-content: center; color: var(--fg-4); }
.step.done .marker { color: var(--ok, #4ade80); }

.ring {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1.5px solid var(--fg-4, #888);
}

.body { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.label { font-size: 13px; font-weight: 500; }
.count { color: var(--fg-3); font-weight: 400; }
.step.done .label { color: var(--fg-2); }
.why { font-size: 12px; color: var(--fg-3); }

.go { color: var(--fg-4); flex-shrink: 0; }

.done-note { margin: 12px 16px 4px; }
</style>
