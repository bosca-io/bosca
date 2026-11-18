export class ImageUrlNotAllowed extends Error {
    constructor() {
        super('Image URL is not allowed')
    }
}

type SupportedUrlPrefix = URL | 'http:' | 'https:'

export function parseSupportedUrls(prefixes: string[]): SupportedUrlPrefix[] {
    return prefixes.flatMap<SupportedUrlPrefix>(prefix => {
        const value = prefix.trim()
        if (!value) return []
        // A scheme-only prefix explicitly permits any host using that scheme.
        if (value.toLowerCase() === 'http://') return ['http:']
        if (value.toLowerCase() === 'https://') return ['https:']
        try {
            const url = new URL(value)
            if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) {
                throw new Error('unsupported URL prefix')
            }
            return [url]
        } catch {
            console.error('Ignoring invalid SUPPORTED_URLS entry')
            return []
        }
    })
}

export function isSupportedUrl(url: URL, prefixes: SupportedUrlPrefix[]): boolean {
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return false
    return prefixes.some(prefix => {
        if (typeof prefix === 'string') return url.protocol === prefix
        return url.origin === prefix.origin
            && url.pathname.startsWith(prefix.pathname)
            && (!prefix.search || url.search.startsWith(prefix.search))
    })
}

/** Checks the allowlist before every request, including redirects. */
export async function fetchSupportedImage(url: URL, prefixes: SupportedUrlPrefix[], fetcher: typeof fetch = fetch): Promise<Response> {
    let current = url
    for (let redirects = 0; redirects <= 20; redirects++) {
        if (!isSupportedUrl(current, prefixes)) throw new ImageUrlNotAllowed()
        const response = await fetcher(current, {redirect: 'manual'})
        if (![301, 302, 303, 307, 308].includes(response.status)) return response
        const location = response.headers.get('Location')
        if (!location) return response
        await response.body?.cancel()
        if (redirects === 20) throw new Error('Too many image redirects')
        current = new URL(location, current)
    }
    throw new Error('Too many image redirects')
}
