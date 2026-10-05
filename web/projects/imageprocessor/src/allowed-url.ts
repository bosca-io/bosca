export class ImageUrlNotAllowed extends Error {
    constructor() {
        super('Image URL is not allowed')
    }
}

export function parseSupportedUrls(prefixes: string[]): URL[] {
    return prefixes.flatMap(prefix => {
        if (!prefix.trim()) return []
        try {
            const url = new URL(prefix.trim())
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

export function isSupportedUrl(url: URL, prefixes: URL[]): boolean {
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return false
    return prefixes.some(prefix => url.origin === prefix.origin
        && url.pathname.startsWith(prefix.pathname)
        && (!prefix.search || url.search.startsWith(prefix.search)))
}

/** Checks the allowlist before every request, including redirects. */
export async function fetchSupportedImage(url: URL, prefixes: URL[], fetcher: typeof fetch = fetch): Promise<Response> {
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
