import { PUBLIC_ROUTES } from '../utils/public-routes'

export default defineEventHandler((e) => {
  const siteUrl = useRuntimeConfig(e).public.siteUrl
  setHeader(e, 'Content-Type', 'application/xml; charset=utf-8')
  const urls = PUBLIC_ROUTES
    .map(path => `  <url><loc>${siteUrl}${path === '/' ? '' : path}</loc></url>`)
    .join('\n')
  return `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls}
</urlset>
`
})
