import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http'

/**
 * Tiny HTTP server that the Nuxt dev server proxies to as if it were
 * the real Bosca backend. Used by the Playwright E2E suite so SSR
 * `/graphql` calls (which Playwright's `page.route` cannot intercept)
 * still return canned data.
 *
 * It implements just enough of the real auth surface for the studio to
 * authenticate a single seeded account (`admin` / `password`) through the
 * genuine login → cookie → SSR profile-lookup → persona-routing flow.
 * The E2E suite logs in for real (see `e2e/auth.setup.ts`) rather than
 * bypassing auth, so this server must answer the identity queries the
 * client and SSR make.
 *
 * Lifecycle:
 *   * `e2e/global-setup.ts` boots one instance on {@link MOCK_API_PORT}.
 *   * `e2e/auth.setup.ts` drives the real /auth/login UI as the admin and
 *     saves the resulting cookies to {@link STORAGE_STATE}; every spec
 *     reuses that storage state.
 *   * Tests call `setMocks(page.request, { K8sClusters: {...} })` in
 *     `beforeEach` to install per-spec data queries.
 *   * `e2e/global-teardown.ts` closes the server.
 *
 * Endpoints:
 *   * `POST /graphql` — auth/identity operations (LoginPassword,
 *     GetCurrentPrincipal/Profiles/Groups, StudioAccess, MyPersonas) are
 *     answered from the seeded admin identity; everything else falls
 *     through to the per-spec dispatch map, keyed by `query NAME`.
 *   * `POST /__test__/mock` — replaces the dispatch map.
 *   * `POST /__test__/reset` — clears the dispatch map.
 *   * `GET /api/v1/profiles/me` — returns the admin profile when a `_bat`
 *     session cookie is present, else the zero-UUID "unauthenticated"
 *     sentinel so the SSR auth gate behaves like production.
 */

/** Fixed port — see global-setup.ts for why we don't pick a free port at runtime. */
export const MOCK_API_PORT = 18080

/** Seeded test account. The login UI in `auth.setup.ts` types these. */
export const ADMIN_EMAIL = 'admin'
export const ADMIN_PASSWORD = 'password'

/** Where `auth.setup.ts` writes the authenticated storage state. */
export const STORAGE_STATE = 'e2e/.auth/admin.json'

// ---------------------------------------------------------------------------
// Seeded admin identity
// ---------------------------------------------------------------------------

const ADMIN_PRINCIPAL_ID = '22222222-2222-2222-2222-222222222222'
const ADMIN_PROFILE_ID = '11111111-1111-1111-1111-111111111111'
const ADMIN_GROUP_ID = '33333333-3333-3333-3333-333333333333'
const ZERO_UUID = '00000000-0000-0000-0000-000000000000'

/** Far-future expiry (2100-01-01) so the client never schedules a refresh. */
const TOKEN_EXPIRES_AT = 4102444800
const TOKEN_ISSUED_AT = 1700000000

/**
 * A structurally-valid JWT so the auth client's expiry fallback
 * (`extractMetadataFromJwt`) agrees with the explicit `expiresAt` below,
 * regardless of which path it reads on re-init from the saved cookie.
 */
function makeAdminToken(): string {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url')
  const payload = Buffer.from(JSON.stringify({
    sub: ADMIN_PRINCIPAL_ID,
    iat: TOKEN_ISSUED_AT,
    exp: TOKEN_EXPIRES_AT,
  })).toString('base64url')
  return `${header}.${payload}.e2e`
}

const ADMIN_TOKEN = makeAdminToken()

const ADMIN_PRINCIPAL = {
  id: ADMIN_PRINCIPAL_ID,
  verified: true,
  primaryProfileId: ADMIN_PROFILE_ID,
}

const ADMIN_PROFILE = {
  id: ADMIN_PROFILE_ID,
  name: 'Admin',
  type: 'GENERIC',
  visibility: 'USER',
  slug: 'admin',
  isPrimary: true,
  attributes: [] as unknown[],
}

const ADMIN_GROUPS = [
  { id: ADMIN_GROUP_ID, name: 'administrators', description: 'Administrators', type: 'SYSTEM' },
]

let dispatch: Record<string, unknown> = {}

function send(res: ServerResponse, code: number, body: unknown) {
  res.writeHead(code, { 'content-type': 'application/json' })
  res.end(JSON.stringify(body))
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = []
    req.on('data', c => chunks.push(c as Buffer))
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8')
      try { resolve(JSON.parse(text)) } catch { resolve({}) }
    })
  })
}

/** True when the request carries the admin Bearer token or `_bat` cookie. */
function isAuthenticated(req: IncomingMessage): boolean {
  const auth = req.headers['authorization']
  if (typeof auth === 'string' && /^Bearer\s+.+/.test(auth)) return true
  const cookie = req.headers['cookie']
  return typeof cookie === 'string' && /(?:^|;\s*)_bat=[^;]+/.test(cookie)
}

/** Pulls the GraphQL operation name out of a `query NAME` / `mutation NAME` body. */
function operationName(query: string | undefined): string {
  return query?.match(/\b(?:query|mutation)\s+(\w+)/)?.[1] ?? ''
}

/**
 * Answers the fixed auth/identity operations the studio issues during the
 * real login + SSR + persona-routing flow. Returns `null` for anything
 * else so the caller falls back to the per-spec dispatch map.
 */
function handleIdentityOperation(
  op: string,
  variables: Record<string, unknown>,
  req: IncomingMessage,
): unknown | null {
  switch (op) {
    case 'LoginPassword': {
      if (variables.identifier === ADMIN_EMAIL && variables.password === ADMIN_PASSWORD) {
        return {
          data: {
            security: {
              login: {
                password: {
                  principal: ADMIN_PRINCIPAL,
                  profile: ADMIN_PROFILE,
                  token: { token: ADMIN_TOKEN, expiresAt: TOKEN_EXPIRES_AT, issuedAt: TOKEN_ISSUED_AT },
                  refreshToken: 'e2e-admin-refresh-token',
                },
              },
            },
          },
        }
      }
      return { errors: [{ message: 'Invalid credentials' }] }
    }
    case 'GetCurrentPrincipal':
      return {
        data: {
          security: {
            principals: { current: isAuthenticated(req) ? ADMIN_PRINCIPAL : null },
          },
        },
      }
    case 'GetCurrentProfiles':
      return { data: { profiles: { current: isAuthenticated(req) ? [ADMIN_PROFILE] : [] } } }
    case 'GetCurrentGroups':
      return {
        data: {
          security: {
            principals: { current: { groups: isAuthenticated(req) ? ADMIN_GROUPS : [] } },
          },
        },
      }
    case 'StudioAccess':
      return {
        data: {
          security: {
            principals: {
              current: isAuthenticated(req) ? { groups: [{ name: 'administrators' }] } : null,
            },
          },
          profiles: { current: isAuthenticated(req) ? [ADMIN_PROFILE] : [] },
        },
      }
    case 'MyPersonas':
      // The admin reaches every subsystem via the `administrators` group, so no
      // explicit studio personas are needed.
      return { data: { profiles: { studioPersonas: { byProfile: [] } } } }
    default:
      return null
  }
}

export function startMockServer(port = 0): Promise<{ url: string; server: Server }> {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      const url = req.url || ''
      if (req.method === 'POST' && url.startsWith('/graphql')) {
        const body = await readJson(req) as { query?: string; variables?: Record<string, unknown> }
        const op = operationName(body.query)
        const identity = handleIdentityOperation(op, body.variables ?? {}, req)
        if (identity !== null) return send(res, 200, identity)
        return send(res, 200, { data: dispatch[op] ?? {} })
      }
      if (req.method === 'POST' && url === '/__test__/mock') {
        dispatch = await readJson(req)
        return send(res, 200, { ok: true })
      }
      if (req.method === 'POST' && url === '/__test__/reset') {
        dispatch = {}
        return send(res, 200, { ok: true })
      }
      if (req.method === 'GET' && url.startsWith('/api/v1/profiles/me')) {
        // The SSR auth gate forwards the browser cookie. A valid session sees
        // the admin profile; anything else gets the zero-UUID sentinel so the
        // gate redirects exactly as it would in production.
        const profile = isAuthenticated(req)
          ? { id: ADMIN_PROFILE_ID, editor: true }
          : { id: ZERO_UUID }
        return send(res, 200, [profile])
      }
      send(res, 404, { error: `unhandled ${req.method} ${url}` })
    })
    server.listen(port, '127.0.0.1', () => {
      const addr = server.address()
      const actualPort = typeof addr === 'object' && addr ? addr.port : port
      resolve({ url: `http://127.0.0.1:${actualPort}`, server })
    })
  })
}

const MOCK_BASE = `http://127.0.0.1:${MOCK_API_PORT}`

/**
 * Test-side helper. Posts a fresh dispatch map to the mock server so
 * subsequent `/graphql` calls return the canned data. Pass `{}` (or
 * call `resetMocks`) to clear. Identity operations are always answered
 * from the seeded admin and are unaffected by the dispatch map.
 */
export async function setMocks(
  request: { post: (_url: string, _opts: { data: unknown }) => Promise<unknown> },
  byOperation: Record<string, unknown>,
) {
  await request.post(`${MOCK_BASE}/__test__/mock`, { data: byOperation })
}

export async function resetMocks(request: { post: (_url: string, _opts: { data: unknown }) => Promise<unknown> }) {
  await request.post(`${MOCK_BASE}/__test__/reset`, { data: {} })
}
