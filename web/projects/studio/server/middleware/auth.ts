import type { H3Event } from 'h3'

function allowsUnauthed(e: H3Event): boolean {
  const path = e.path.split(' ')[0]?.split('?')[0]?.toString()
  return (
    path?.startsWith('/auth/') === true
    || path?.startsWith('/_') === true
    || path?.startsWith('/api/') === true
    || path?.startsWith('/graphql') === true
    || path === '/health'
    || path === '/robots.txt'
    // Reached from unsubscribe links in email; must render logged-out.
    || path === '/unsubscribe'
  )
}

export default defineEventHandler(async (e) => {
  if (allowsUnauthed(e)) return

  const config = useRuntimeConfig()
  const cookieHeader = getHeader(e, 'cookie')

  if (!cookieHeader) {
    return sendRedirect(e, '/auth/login')
  }

  try {
    const requestOrigin = getRequestURL(e).origin
    // Authentication gate only: confirm the session cookie resolves to a real
    // authenticated profile. Authorization (editor access, studio persona) is
    // deferred to the app's route middleware, which routes users without a
    // persona to /welcome. Filtering by `?isEditor=true` here would bounce a
    // valid-but-not-yet-provisioned user (the /welcome request-access audience)
    // to login with ?unauthorized=true instead of letting them reach /welcome.
    const response = await fetch(
      config.apiUrl + '/api/v1/profiles/me',
      { headers: { Cookie: cookieHeader, Origin: requestOrigin } },
    )

    // A bad or expired token fails here; that is the only case that warrants
    // ?unauthorized=true. A valid token always resolves to a real profile,
    // whether or not the user is an editor.
    if (!response.ok) {
      return sendRedirect(e, '/auth/login?unauthorized=true')
    }

    const profiles = await response.json()
    if (
      !Array.isArray(profiles)
      || profiles.length === 0
      || profiles[0].id === '00000000-0000-0000-0000-000000000000'
    ) {
      return sendRedirect(e, '/auth/login?unauthorized=true')
    }
  } catch {
    return sendRedirect(e, '/auth/login?unauthorized=true')
  }
})
