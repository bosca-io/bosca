import type { Server } from 'node:http'

import { MOCK_API_PORT, startMockServer } from './fixtures/mock-server'

declare global {
   
  var __E2E_MOCK_SERVER__: Server | undefined
}

/**
 * Boots a single mock backend on a fixed port before Playwright spawns
 * spec workers. The dev server (launched via webServer in
 * playwright.config.ts) reads `API_URL=http://127.0.0.1:MOCK_API_PORT`
 * and proxies both SSR and CSR `/graphql` calls to it.
 *
 * Using a fixed port (vs an ephemeral one we'd have to thread through
 * the config) avoids the chicken-and-egg where playwright.config.ts
 * loads before globalSetup runs and so can't read a dynamic URL.
 */
export default async function globalSetup() {
  const { server } = await startMockServer(MOCK_API_PORT)
  globalThis.__E2E_MOCK_SERVER__ = server
}
