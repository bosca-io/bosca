import type { Server } from 'node:http'

declare global {
   
  var __E2E_MOCK_SERVER__: Server | undefined
}

export default async function globalTeardown() {
  const server = globalThis.__E2E_MOCK_SERVER__
  if (!server) return
  await new Promise<void>((resolve, reject) => {
    server.close(err => (err ? reject(err) : resolve()))
  })
}
