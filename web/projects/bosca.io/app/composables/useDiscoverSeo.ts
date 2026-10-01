/**
 * Shared head setup for the public /discover marketing pages. They are
 * standalone landing pages, so they opt out of the global "— Bosca Docs"
 * title template, set their family's social-card image, and emit
 * BreadcrumbList structured data for their place in the family.
 */
export function useDiscoverSeo(breadcrumbs: { name: string, path: string }[], ogImage: string) {
  const siteUrl = useRuntimeConfig().public.siteUrl

  useHead({
    titleTemplate: null,
    script: [
      {
        type: 'application/ld+json',
        innerHTML: JSON.stringify({
          '@context': 'https://schema.org',
          '@type': 'BreadcrumbList',
          'itemListElement': breadcrumbs.map((crumb, i) => ({
            '@type': 'ListItem',
            'position': i + 1,
            'name': crumb.name,
            'item': `${siteUrl}${crumb.path === '/' ? '' : crumb.path}`
          }))
        })
      }
    ]
  })

  useSeoMeta({ ogImage: `${siteUrl}${ogImage}` })
}
