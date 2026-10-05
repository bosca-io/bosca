import { Hocuspocus, IncomingMessage, MessageType } from '@hocuspocus/server'
import type { Extension } from '@hocuspocus/server'
import { Database } from '@hocuspocus/extension-database'
import { AuthMessageType } from '@hocuspocus/common'

interface CollaborationContext {
  authorization: {
    token: string
    pending?: Promise<void>
    timer?: ReturnType<typeof setInterval>
  }
}

interface CollaborationOptions {
  url: string
  extensions?: Extension[]
  permissionCheckInterval?: number
}

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function collaborationUrl(baseUrl: string, documentName: string): string {
  const [id, versionText, extra] = documentName.split('.')
  const version = Number(versionText)
  if (!id || !UUID_REGEX.test(id) || !versionText || !/^\d+$/.test(versionText)
    || extra !== undefined || !Number.isInteger(version) || version < 1 || version > 2147483647) {
    throw denied()
  }
  return `${baseUrl}/api/v1/content/metadata/${id}/document/collaboration?version=${version}`
}

function denied(): Error & { code: number, reason: string } {
  return Object.assign(new Error('Collaboration access denied'), { code: 4403, reason: 'permission-denied' })
}

/** Authorizes each participant, document message, load, and store through the content API; awareness relies on the periodic re-check. */
export function createCollaborationServer({ url, extensions = [], permissionCheckInterval = 30000 }: CollaborationOptions) {
  async function request(documentName: string, token: string, method: string, body?: Uint8Array) {
    if (!token) throw denied()
    return fetch(collaborationUrl(url, documentName), {
      method,
      redirect: 'error',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/octet-stream' },
      body: body as BodyInit | undefined,
    })
  }
  async function authorize(documentName: string, token: string) {
    const response = await request(documentName, token, 'HEAD')
    if (!response.ok) throw denied()
  }

  return new Hocuspocus({
    unloadImmediately: true,
    onAuthenticate: async ({ documentName, token }) => {
      await authorize(documentName, token)
      return { authorization: { token } } satisfies CollaborationContext
    },
    beforeHandleMessage: async ({ documentName, context, update }) => {
      const message = new IncomingMessage(update)
      message.readVarString()
      const type = message.readVarUint()
      const authorization = (context as CollaborationContext).authorization
      // Awareness carries presence, not document content. It only waits for an
      // in-flight token refresh; the periodic check in `connected` covers revocation.
      if (type === MessageType.Awareness || type === MessageType.QueryAwareness) {
        await authorization.pending
        return
      }
      // Hocuspocus does not await onTokenSync. Validate refreshes here so following
      // edits wait for the new credential instead of racing its authorization.
      const pending = (authorization.pending ?? Promise.resolve()).then(async () => {
        if (type === MessageType.Auth) {
          if (message.readVarUint() !== AuthMessageType.Token) throw denied()
          const token = message.readVarString()
          await authorize(documentName, token)
          authorization.token = token
        } else {
          await authorize(documentName, authorization.token)
        }
      })
      authorization.pending = pending
      try {
        await pending
      } finally {
        if (authorization.pending === pending) authorization.pending = undefined
      }
    },
    connected: async ({ documentName, context, connection }) => {
      let checking = false
      const authorization = (context as CollaborationContext).authorization
      const timer = setInterval(() => {
        if (checking || authorization.pending) return
        checking = true
        const token = authorization.token
        void authorize(documentName, token)
          .catch(() => { if (authorization.token === token) connection.close(denied()) })
          .finally(() => { checking = false })
      }, permissionCheckInterval)
      timer.unref()
      authorization.timer = timer
    },
    onDisconnect: async ({ context }) => {
      const authorization = (context as CollaborationContext).authorization
      const timer = authorization.timer
      if (timer) clearInterval(timer)
      authorization.timer = undefined
    },
    extensions: [
      ...extensions,
      new Database({
        fetch: async ({ documentName, context }) => {
          const response = await request(documentName, (context as CollaborationContext).authorization.token, 'GET')
          if (response.status === 404) return null
          if (!response.ok) throw denied()
          return new Uint8Array(await response.arrayBuffer())
        },
        store: async ({ documentName, context, state }) => {
          const authorization = (context as CollaborationContext).authorization
          await authorization.pending
          const response = await request(documentName, authorization.token, 'PUT', state)
          if (!response.ok) throw denied()
        },
      }),
    ],
  })
}
