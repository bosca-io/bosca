import gql from 'graphql-tag'
import type { Query } from '~/types/graphql'

interface LogoVariant {
  dark?: string
  light?: string
}

interface AppOverridesValue {
  title?: string
  hideTitle?: boolean
  logo?: {
    expanded?: LogoVariant
    collapsed?: LogoVariant
  }
}

const overridesQuery = gql`
  query GetAppOverrides {
    configurations {
      configuration(key: "admin.overrides") {
        value
      }
    }
  }
`

/**
 * Reads tenant branding overrides from the `admin.overrides` configuration.
 * The same key is used by the administration app so a single setting drives
 * both surfaces. Logo slugs are resolved against the backend image endpoint
 * (proxied by Nuxt), with dark/light variants selected from the active theme.
 */
export function useAppOverrides() {
  const { useAsyncQuery } = useGraphQL()
  const { tweaks } = useTweaks()

  const { data, error } = useAsyncQuery<Query>('app-overrides', overridesQuery)

  // The branding query is public and must succeed on every page. A
  // silent failure here used to hide the entire custom logo because
  // `hasOverrides` would just stay `false`. Surfacing the error so
  // it's visible in dev / log aggregation makes regressions in the
  // SSR data path (auth context lost, network down, schema changes)
  // discoverable instead of invisible.
  watch(error, (err) => {
    if (err) {
      console.error('[useAppOverrides] failed to load admin.overrides:', err)
    }
  }, { immediate: true })

  const overrides = computed<AppOverridesValue | null>(() => {
    const value = data.value?.configurations?.configuration?.value
    return (value as AppOverridesValue | null | undefined) ?? null
  })

  function resolveLogo(collapsed: boolean): string | null {
    const o = overrides.value
    if (!o?.logo) return null
    const variant = (collapsed ? o.logo.collapsed : o.logo.expanded) ?? o.logo.expanded
    if (!variant) return null
    const slug = (tweaks.value.theme === 'dark' ? variant.dark : variant.light) ?? variant.light
    return slug ? `/content/image/${slug}` : null
  }

  const expandedLogo = computed(() => resolveLogo(false))
  const collapsedLogo = computed(() => resolveLogo(true))
  const title = computed(() => overrides.value?.title ?? null)
  const hideTitle = computed(() => overrides.value?.hideTitle === true)
  const hasOverrides = computed(() => Boolean(expandedLogo.value || collapsedLogo.value || title.value))

  return { overrides, expandedLogo, collapsedLogo, title, hideTitle, hasOverrides }
}
