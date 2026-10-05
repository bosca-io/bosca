<script setup lang="ts">
import gql from 'graphql-tag'

definePageMeta({ middleware: 'commerce-admin' })

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const companyId = computed(() => route.params.id as string)

const detailGql = gql`
  query CommerceCompany($id: UUID!) {
    ecom {
      company(id: $id) {
        id
        lengthUnit
        weightUnit
        organization { id name }
        profile { id name }
        created
        modified
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  ecom: {
    company: {
      id: string
      lengthUnit: string
      weightUnit: string
      organization: { id: string; name: string }
      profile: { id: string; name: string }
      created: string
      modified: string
    } | null
  }
}>('commerce-company', detailGql, { id: companyId })

const company = computed(() => data.value?.ecom?.company ?? null)

function fmt(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}

// --- fulfillment units ---
// The authoritative storage units for this company's product/container dimensions and weights.
// Products span fulfillment centers, so the unit is per-company (carriers convert from it at label time).
const unitsForm = reactive({ lengthUnit: 'INCHES', weightUnit: 'POUNDS' })
const savingUnits = ref(false)
const unitsSaved = ref(false)
const unitsError = ref('')

// Seed the form from the company once it loads (and re-seed after a successful save refresh).
watch(company, (c) => {
  if (!c) return
  unitsForm.lengthUnit = c.lengthUnit
  unitsForm.weightUnit = c.weightUnit
}, { immediate: true })

const unitsDirty = computed(() =>
  !!company.value
  && (unitsForm.lengthUnit !== company.value.lengthUnit || unitsForm.weightUnit !== company.value.weightUnit),
)

const setUnitsGql = gql`
  mutation SetCompanyUnits($id: UUID!, $lengthUnit: LengthUnit!, $weightUnit: WeightUnit!) {
    ecom { companies { company(id: $id) { setUnits(lengthUnit: $lengthUnit, weightUnit: $weightUnit) { id lengthUnit weightUnit } } } }
  }
`

async function saveUnits() {
  if (!company.value) return
  savingUnits.value = true
  unitsSaved.value = false
  unitsError.value = ''
  try {
    await mutation(setUnitsGql, {
      id: company.value.id,
      lengthUnit: unitsForm.lengthUnit,
      weightUnit: unitsForm.weightUnit,
    })
    unitsSaved.value = true
    await refresh()
  } catch (e: unknown) {
    unitsError.value = e instanceof Error ? e.message : 'Failed to save units'
  } finally {
    savingUnits.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="company"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Companies', { label: company.organization.name })"
        :title="company.organization.name"
        subtitle="Company" />
    </template>

    <div v-if="status === 'pending'" class="state">Loading…</div>
    <div v-else-if="!company" class="state">Company not found.</div>
    <SectionCard v-else title="Identity">
      <dl class="kv">
        <dt>Organization</dt><dd>{{ company.organization.name }}</dd>
        <dt>Profile</dt><dd>{{ company.profile.name }}</dd>
        <dt>Created</dt><dd>{{ fmt(company.created) }}</dd>
        <dt>Modified</dt><dd>{{ fmt(company.modified) }}</dd>
      </dl>
      <p class="hint">
        Name, branding, and membership live on the organization profile (the Audience subsystem).
        Catalogs, products, stores, and the rest of commerce hang off this company.
      </p>
    </SectionCard>

    <SectionCard v-if="company" title="Fulfillment Units" padded>
      <p class="hint">
        The units this company stores product and container dimensions and weights in. Every product,
        container, and packed box across all fulfillment centers uses these; carriers convert from them
        when a label is purchased.
      </p>
      <div class="units-row">
        <Select v-model="unitsForm.lengthUnit" label="Dimensions" :options="LENGTH_UNIT_OPTIONS" />
        <Select v-model="unitsForm.weightUnit" label="Weight" :options="WEIGHT_UNIT_OPTIONS" />
      </div>
      <div class="actions">
        <span v-if="unitsSaved && !unitsDirty" class="saved">Saved.</span>
        <span v-if="unitsError" class="form-error">{{ unitsError }}</span>
        <Button
          primary
          size="sm"
          :accent="accent"
          :disabled="savingUnits || !unitsDirty"
          @click="saveUnits">
          {{ savingUnits ? 'Saving…' : 'Save units' }}
        </Button>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.kv { display: grid; grid-template-columns: 160px 1fr; gap: 8px 16px; margin: 0 0 12px; }
.kv dt { color: var(--fg-3); font-size: 12.5px; }
.kv dd { margin: 0; font-size: 13px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 12px; }
.units-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; max-width: 420px; }
.actions { display: flex; align-items: center; gap: 12px; justify-content: flex-end; margin-top: 14px; }
.saved { color: var(--ok, #4ade80); font-size: 12px; }
.form-error { color: var(--err); font-size: 12px; }
</style>
