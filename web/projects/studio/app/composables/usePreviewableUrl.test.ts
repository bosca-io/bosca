import { describe, it, expect, vi, beforeEach } from 'vitest'
import { usePreviewableUrl, resetPreviewableUrlCache } from './usePreviewableUrl'

const mockQuery = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
}))

function stubConfig(url: string | null) {
  mockQuery.mockResolvedValue({
    configurations: { configuration: url ? { value: { value: url } } : null },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  resetPreviewableUrlCache()
  vi.stubGlobal('open', vi.fn())
})

describe('usePreviewableUrl', () => {
  it('canPreview is false before load', () => {
    const preview = usePreviewableUrl()
    expect(preview.canPreview.value).toBe(false)
  })

  it('canPreview is true when preview.url configuration exists', async () => {
    stubConfig('https://example.com/{slug}')
    const preview = usePreviewableUrl()
    await preview.load()
    expect(preview.canPreview.value).toBe(true)
  })

  it('canPreview stays false when configuration is missing', async () => {
    stubConfig(null)
    const preview = usePreviewableUrl()
    await preview.load()
    expect(preview.canPreview.value).toBe(false)
  })

  it('loads the configuration only once across callers', async () => {
    stubConfig('https://example.com/{slug}')
    const a = usePreviewableUrl()
    await a.load()
    const b = usePreviewableUrl()
    await b.load()
    expect(mockQuery).toHaveBeenCalledTimes(1)
    expect(b.canPreview.value).toBe(true)
  })

  it('substitutes slug, id and languageTag placeholders', async () => {
    stubConfig('https://example.com/{languageTag}/{slug}?id={id}')
    const preview = usePreviewableUrl()
    await preview.load()
    const url = preview.buildPreviewUrl({ id: 'abc', slug: 'my-doc', languageTag: 'en-US' })
    expect(url).toBe('https://example.com/en-US/my-doc?id=abc')
  })

  it('substitutes empty strings for missing slug and languageTag', async () => {
    stubConfig('https://example.com/{languageTag}/{slug}/{id}')
    const preview = usePreviewableUrl()
    await preview.load()
    const url = preview.buildPreviewUrl({ id: 'abc', slug: null, languageTag: null })
    expect(url).toBe('https://example.com///abc')
  })

  it('openPreview opens the resolved url in a new tab', async () => {
    stubConfig('https://example.com/{slug}')
    const preview = usePreviewableUrl()
    await preview.load()
    preview.openPreview({ id: 'abc', slug: 'my-doc', languageTag: 'en-US' })
    expect(window.open).toHaveBeenCalledWith('https://example.com/my-doc', '_blank', 'noopener')
  })

  it('openPreview does not open a tab when unconfigured', async () => {
    stubConfig(null)
    const preview = usePreviewableUrl()
    await preview.load()
    preview.openPreview({ id: 'abc' })
    expect(window.open).not.toHaveBeenCalled()
  })

  it('treats a failed configuration query as preview unavailable', async () => {
    mockQuery.mockRejectedValue(new Error('boom'))
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const preview = usePreviewableUrl()
    await preview.load()
    expect(preview.canPreview.value).toBe(false)
    consoleSpy.mockRestore()
  })
})
