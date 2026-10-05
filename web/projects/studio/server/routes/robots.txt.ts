export default defineEventHandler((e) => {
  setHeader(e, 'Content-Type', 'text/plain; charset=utf-8')
  return `User-agent: *
Disallow: /
`
})
