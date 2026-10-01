import { defineConfig, devices } from '@playwright/test'

import { MOCK_API_PORT, STORAGE_STATE } from './e2e/fixtures/mock-server'

// Ubuntu 26.04 isn't yet supported by Playwright's chromium download
// (the matrix only covers up to 24.04). System Google Chrome is
// present at /usr/bin/google-chrome so we use the `chrome` channel,
// which delegates to the OS-installed browser. To run against the
// bundled chromium on a supported OS, change `channel: 'chrome'` to
// remove the channel option.
const SUPPORTS_BUNDLED_CHROMIUM = false

const MOCK_API_URL = `http://127.0.0.1:${MOCK_API_PORT}`

const browserUse = SUPPORTS_BUNDLED_CHROMIUM
  ? { ...devices['Desktop Chrome'] }
  : { ...devices['Desktop Chrome'], channel: 'chrome' }

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/fixtures/**', '**/global-*.ts'],
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  // Tests share a single mock backend so parallel workers would race on
  // the dispatch map. Force serial execution; the suite is small.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // Logs in as the seeded admin once through the real /auth/login UI and
    // saves the session; the spec project depends on it and reuses the state.
    {
      name: 'setup',
      testMatch: /.*\.setup\.ts/,
      use: browserUse,
    },
    {
      name: 'chromium',
      testMatch: /.*\.spec\.ts/,
      dependencies: ['setup'],
      use: { ...browserUse, storageState: STORAGE_STATE },
    },
  ],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
    env: {
      // Routes the studio's /graphql proxy (and the SSR profile lookup) at the
      // mock backend booted by globalSetup. Without this every SSR query 502s.
      // The suite authenticates for real against the mock — there is NO auth
      // bypass; see e2e/auth.setup.ts.
      API_URL: MOCK_API_URL,
    },
  },
})
