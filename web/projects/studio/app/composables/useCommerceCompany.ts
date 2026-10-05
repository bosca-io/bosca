import gql from 'graphql-tag'

export interface CommerceCompany {
  id: string
  name: string
  /** The unit this company's product/container dimensions are stored in ('INCHES' | 'CENTIMETERS'). */
  lengthUnit: string
  /** The unit this company's product/container weights are stored in ('POUNDS' | 'KILOGRAMS'). */
  weightUnit: string
}

const companiesGql = gql`
  query CommerceCompanies($offset: Int!, $limit: Int!) {
    ecom {
      companies(offset: $offset, limit: $limit) {
        id
        lengthUnit
        weightUnit
        organization { id name }
      }
    }
  }
`

const STORAGE_KEY = 'bosca:commerce:company'

/**
 * The Commerce section's active-company selector. Most ecom admin reads are
 * company-scoped, so the chosen company id is shared across the section via Nuxt
 * shared state and persisted to localStorage so it survives reloads. The company
 * list is fetched client-side (the selector is an interactive concern, and this
 * keeps the persisted selection authoritative over an SSR default).
 */
export function useCommerceCompany() {
  const { useAsyncQuery } = useGraphQL()
  const selectedId = useState<string | null>('commerce:company', () => null)

  // Hydrate the persisted selection before the default-selection watcher runs.
  if (import.meta.client && selectedId.value === null) {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved) selectedId.value = saved
    } catch { /* storage unavailable */ }
  }

  const { data, status, refresh } = useAsyncQuery<{
    ecom: { companies: Array<{ id: string; lengthUnit: string; weightUnit: string; organization: { id: string; name: string } }> }
  }>('commerce-companies', companiesGql, { offset: 0, limit: 100 }, { server: false })

  const companies = computed<CommerceCompany[]>(() =>
    (data.value?.ecom?.companies ?? []).map(c => ({
      id: c.id,
      name: c.organization.name,
      lengthUnit: c.lengthUnit,
      weightUnit: c.weightUnit,
    })),
  )

  // Persist whenever the selection changes (client only).
  watch(selectedId, (id) => {
    if (!import.meta.client) return
    try {
      if (id) localStorage.setItem(STORAGE_KEY, id)
      else localStorage.removeItem(STORAGE_KEY)
    } catch { /* storage unavailable */ }
  })

  // Default to the first company once the list loads when nothing valid is selected.
  watch(companies, (list) => {
    if (!list.length) return
    if (!selectedId.value || !list.some(c => c.id === selectedId.value)) {
      selectedId.value = list[0]!.id
    }
  }, { immediate: true })

  const selected = computed<CommerceCompany | null>(() =>
    companies.value.find(c => c.id === selectedId.value) ?? null,
  )

  return { companies, selected, selectedId, status, refresh }
}
