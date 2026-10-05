import gql from 'graphql-tag'

const previewUrlGql = gql`
  query GetPreviewableUrl {
    configurations {
      configuration(key: "preview.url") {
        value
      }
    }
  }
`

export interface PreviewableItem {
  id: string
  slug?: string | null
  languageTag?: string | null
}

// Deployment-level configuration; fetched once and shared across all callers.
const previewUrlTemplate = ref<string | null>(null)
const loadState = ref<'idle' | 'loading' | 'loaded'>('idle')

/** Test-only: clears the module-scoped configuration cache. */
export function resetPreviewableUrlCache() {
  previewUrlTemplate.value = null
  loadState.value = 'idle'
}

/**
 * Resolves the `preview.url` configuration and opens content previews in a
 * new tab. The configuration value is a URL template supporting `{slug}`,
 * `{id}` and `{languageTag}` placeholders. When the configuration is absent,
 * `canPreview` stays false and callers should hide their preview affordance.
 */
export function usePreviewableUrl() {
  const { query: gqlQuery } = useGraphQL()
  const toast = useToast()

  async function load() {
    if (loadState.value !== 'idle') return
    loadState.value = 'loading'
    try {
      const result = await gqlQuery<{
        configurations: { configuration: { value: { value?: string } | null } | null }
      }>(previewUrlGql, {})
      previewUrlTemplate.value = result.configurations?.configuration?.value?.value || null
    } catch (e: unknown) {
      // Missing or unreadable configuration just means previews are unavailable.
      console.error('Failed to load preview.url configuration', e)
      previewUrlTemplate.value = null
    } finally {
      loadState.value = 'loaded'
    }
  }

  const canPreview = computed(() => !!previewUrlTemplate.value)

  function buildPreviewUrl(item: PreviewableItem): string | null {
    const template = previewUrlTemplate.value
    if (!template) return null
    return template
      .replace('{slug}', item.slug || '')
      .replace('{id}', item.id)
      .replace('{languageTag}', item.languageTag || '')
  }

  function openPreview(item: PreviewableItem) {
    const url = buildPreviewUrl(item)
    if (!url) {
      toast.error('Preview is not configured')
      return
    }
    window.open(url, '_blank', 'noopener')
  }

  return { load, canPreview, buildPreviewUrl, openPreview }
}
