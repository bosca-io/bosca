import type { Page, Route } from '@playwright/test'

/**
 * Intercepts every `POST /graphql` request from the studio. The dispatch
 * map is keyed by the GraphQL `query NAME { ... }` keyword and returns
 * the canned data. The studio's useGraphQL helper doesn't send a
 * separate `operationName` field, so we sniff the keyword out of the
 * query body.
 *
 * Usage:
 *
 *   await mockGraphQL(page, {
 *     K8sClusters: { kubernetes: { clusters: [...] } },
 *     K8sNodes:    { kubernetes: { nodes: [...] } },
 *   })
 *
 * Any operation the dispatch map doesn't know about returns an empty
 * payload (`{ data: {} }`) so unanticipated queries don't blow up the
 * test — they just render the page's empty state.
 */
export async function mockGraphQL(page: Page, byOperation: Record<string, unknown>) {
  await page.route('**/graphql', async (route: Route) => {
    const body = route.request().postDataJSON() as { query?: string; operationName?: string }
    const opName = body.operationName
      ?? Object.keys(byOperation).find(k => body.query?.match(new RegExp(`\\bquery\\s+${k}\\b`)))
      ?? ''
    const payload = byOperation[opName] ?? {}
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data: payload }),
    })
  })
}

/**
 * Convenience fixtures matching the shapes the kubernetes composables
 * already adapt from. These mirror the GraphQL wire format defined in
 * `core-kubernetes/.../graphql`.
 */
export const SAMPLE_CLUSTERS = [
  {
    id: 'prod-us', name: 'prod-us-east-1', provider: 'EKS', region: 'us-east-1',
    environment: 'production', version: 'v1.30.4', health: 'ok', nodes: 12, pods: 248,
  },
  {
    id: 'staging', name: 'staging-us-east-1', provider: 'EKS', region: 'us-east-1',
    environment: 'staging', version: 'v1.31.0', health: 'warn', nodes: 4, pods: 64,
  },
]

export const SAMPLE_NODES = [
  {
    name: 'ip-10-0-1-12', role: 'control-plane', instance: 'm6i.xlarge', zone: 'us-east-1a',
    status: 'Ready', cpu: 32, memory: 41, pods: 24, age: '60d', version: 'v1.30.4', taints: [],
    labels: { 'node-role.kubernetes.io/control-plane': '', 'node.kubernetes.io/instance-type': 'm6i.xlarge' },
  },
  {
    name: 'ip-10-0-2-141', role: 'worker', instance: 'm6i.2xlarge', zone: 'us-east-1a',
    status: 'Ready', cpu: 68, memory: 73, pods: 32, age: '45d', version: 'v1.30.4', taints: [],
    labels: { 'karpenter.sh/nodepool': 'general', 'node.kubernetes.io/instance-type': 'm6i.2xlarge' },
  },
]

export const SAMPLE_WORKLOADS = [
  {
    id: 'wl-1', kind: 'DEPLOYMENT', name: 'checkout-api', namespace: 'checkout',
    ready: 6, want: 6, status: 'OK', image: 'ghcr.io/acme/checkout-api:1.0.0',
    age: '8d', cpu: 1.2, memory: 1.4, restarts: 0, strategy: 'RollingUpdate',
  },
  {
    id: 'wl-2', kind: 'DEPLOYMENT', name: 'catalog-search', namespace: 'search',
    ready: 5, want: 6, status: 'WARN', image: 'ghcr.io/acme/catalog-search:0.9.3',
    age: '3h', cpu: 2.4, memory: 3.1, restarts: 2, strategy: 'RollingUpdate',
  },
]

export const SAMPLE_EVENTS = [
  {
    id: '1', namespace: 'search', involvedObject: 'Pod/catalog-search-7f8d9',
    reason: 'BackOff', message: 'Back-off restarting failed container', level: 'WARN',
    timestamp: '2026-05-16T17:00:00Z',
  },
]
