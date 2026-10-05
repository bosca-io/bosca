import gql from 'graphql-tag'
import type { NavGroup } from '~~/shared/types'

const dashboardsGql = gql`
  query GetDashboardsForNav {
    analytics { dashboards { all { key name } } }
  }
`

export function useDashboardNav() {
  const { useAsyncQuery } = useGraphQL()
  const { data, status, refresh } = useAsyncQuery<{ analytics: { dashboards: { all: { key: string; name: string }[] } } }>(
    'nav-dashboards', dashboardsGql, {}, { server: false },
  )

  const dashboardNavGroups = computed<NavGroup[] | undefined>(() => {
    const dashboards = data.value?.analytics?.dashboards?.all ?? []
    if (dashboards.length === 0) return undefined
    return [{
      group: 'Dashboards',
      items: dashboards.map(d => ({ id: `dashboard?key=${d.key}`, label: d.name, icon: 'dashboard' })),
    }]
  })

  const dashboardsLoading = computed(() => status.value === 'pending')
  const dashboardsEmpty = computed(() => status.value === 'success' && !dashboardNavGroups.value)

  return { dashboardNavGroups, dashboardsLoading, dashboardsEmpty, refresh }
}
