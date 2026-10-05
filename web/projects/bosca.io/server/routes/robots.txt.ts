export default defineEventHandler((e) => {
  const siteUrl = useRuntimeConfig(e).public.siteUrl
  setHeader(e, 'Content-Type', 'text/plain; charset=utf-8')
  // Keep crawlers focused on content rather than account and API endpoints.
  return `User-agent: *
Allow: /
Disallow: /auth/
Disallow: /api/
Disallow: /graphql
Disallow: /oauth2/

Sitemap: ${siteUrl}/sitemap.xml
`
})
