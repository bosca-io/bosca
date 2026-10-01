import { expect, test } from '@playwright/test'

import { SAMPLE_CLUSTERS } from '../fixtures/graphql'
import { resetMocks, setMocks } from '../fixtures/mock-server'

/**
 * Subsystem-level navigation smoke. Every kubernetes page should at
 * least render its breadcrumb without crashing. Each page returns an
 * empty backend by default; the test asserts the breadcrumb is present
 * to confirm the route resolved and the page mounted.
 */
const PAGES = [
  '/kubernetes/overview',
  '/kubernetes/nodes',
  '/kubernetes/events',
  '/kubernetes/workloads',
  '/kubernetes/pods',
  '/kubernetes/jobs',
  '/kubernetes/scaling',
  '/kubernetes/network',
  '/kubernetes/gateways',
  '/kubernetes/config',
  '/kubernetes/storage',
  '/kubernetes/namespaces',
  '/kubernetes/crds',
  '/kubernetes/access',
  '/kubernetes/helm',
  '/kubernetes/helm/catalog',
  '/kubernetes/helm/repos',
  '/kubernetes/certs',
  '/kubernetes/cnpg',
  '/kubernetes/settings/clusters',
]

test.describe('kubernetes navigation smoke', () => {
  test.beforeEach(async ({ request }) => {
    await setMocks(request, {
      K8sClusters: { kubernetes: { clusters: SAMPLE_CLUSTERS } },
    })
  })

  test.afterEach(async ({ request }) => {
    await resetMocks(request)
  })

  for (const path of PAGES) {
    test(`${path} renders without console errors`, async ({ page }) => {
      const consoleErrors: string[] = []
      page.on('console', m => {
        if (m.type() === 'error') consoleErrors.push(m.text())
      })
      const responseErrors: string[] = []
      page.on('response', r => {
        if (r.status() >= 500 && !r.url().includes('/_nuxt/')) {
          responseErrors.push(`${r.status()} ${r.url()}`)
        }
      })

      await page.goto(path)
      // Breadcrumb "Kubernetes" is on every page header.
      await expect(page.getByText('Kubernetes').first()).toBeVisible({ timeout: 10_000 })

      // Filter Nuxt/Vite dev-mode noise the tests don't care about.
      const real = consoleErrors.filter(e =>
        !e.includes('[Vue warn]')
        && !e.includes('Hydration')
        && !e.includes('GraphQL'))
      expect(real, `console errors on ${path}`).toEqual([])
      expect(responseErrors, `5xx on ${path}`).toEqual([])
    })
  }
})
