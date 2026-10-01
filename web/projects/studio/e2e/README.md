# Studio E2E Suite

Playwright suite covering the Kubernetes subsystem.

## Run

From the workspace root, change to `web/projects/studio` before running the suite:

```bash
cd web/projects/studio
pnpm test:e2e            # all kubernetes specs
pnpm test:e2e --headed   # watch the browser
pnpm test:e2e --debug    # inspector
```

## Architecture

- `playwright.config.ts` boots `nuxt dev` on port 3000 with one test-only
  env var set: `API_URL=http://127.0.0.1:18080`, which points the studio's
  `/graphql` proxy and SSR profile lookup at a mock backend. There is **no**
  auth bypass — the suite logs in for real.
- `e2e/global-setup.ts` starts that mock backend before any specs run.
  `e2e/global-teardown.ts` shuts it down.
- `e2e/fixtures/mock-server.ts` is the mock backend. Each spec calls
  `setMocks(request, { K8sClusters: {...}, ... })` in `beforeEach` to
  register the queries it cares about, and `resetMocks(request)` in
  `afterEach` to clear. Identity/auth operations are answered from the
  seeded admin and are unaffected by `setMocks`/`resetMocks`.
- Tests run serially (`workers: 1`, `fullyParallel: false`) because the
  mock backend holds a single shared dispatch table.

## Auth

The suite authenticates as a seeded `admin` / `password` account through the
real login flow instead of bypassing auth:

- The `setup` project (`e2e/auth.setup.ts`) drives the actual `/auth/login`
  UI, then saves the resulting session (cookies + storage) to
  `e2e/.auth/admin.json` (gitignored).
- The `chromium` project `dependencies: ['setup']` and reuses that storage
  state, so every spec runs as a genuinely logged-in user — exercising the
  client auth init, the SSR profile lookup in `server/middleware/auth.ts`,
  and the persona routing in `app/middleware/auth.global.ts`.
- `mock-server.ts` implements just enough of the backend auth surface for
  this: the `LoginPassword` mutation (validates the seeded credentials and
  returns a far-future token), a cookie-gated `GET /api/v1/profiles/me`, and
  the `GetCurrentPrincipal` / `GetCurrentProfiles` / `GetCurrentGroups` /
  `StudioAccess` / `MyPersonas` identity queries. The admin is a member of the
  `administrators` group, which grants access to every subsystem.

## What's covered

- **navigation.spec.ts** — every `/kubernetes/...` page mounts and
  renders its breadcrumb without console errors or 5xx responses.
- **overview.spec.ts** — the overview header reflects the loaded
  cluster name, and the four stat-tile labels are present.

## What's deferred (marked `test.skip`)

- Data-dependent assertions on the overview page (control vs worker
  breakdown, namespace topology, recent events). The K8sNodes /
  K8sWorkloads / K8sEvents queries skip during SSR because their
  `cluster` variable is null at first render; the CSR refetch isn't
  consistently happening in the timing window Playwright observes.
- Cluster switcher popover interaction and Apply manifest modal — the
  selectors need a pass against the actual rendered DOM.

These should be reactivated as part of a follow-up that either marks
the dependent queries `server: false` or explicitly refreshes them
once the cluster id resolves.
