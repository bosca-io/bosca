import { Redis } from '@hocuspocus/extension-redis'
import { once } from 'node:events'
import type { Extension } from '@hocuspocus/server'
import { createCollaborationServer } from '../utils/collaboration'

// Redis fans collaboration messages out across server instances, so a single
// local dev server works without it. Skip it in dev unless REDIS_HOST is set
// explicitly — with no Redis listening, ioredis retries localhost forever and
// onLoadDocument blocks on a connection that never becomes ready, which left
// every local document/guide editor stuck on its loading spinner.
const extensions: Extension[] = []
if (process.env.REDIS_HOST || process.env.NODE_ENV === 'production') {
  const redis = new Redis({
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6380'),
    prefix: process.env.REDIS_PREFIX || 'hocuspocus',
    options: { password: process.env.REDIS_PASSWORD },
  })
  extensions.push(
    {
      priority: redis.priority + 1,
      onLoadDocument: async () => {
        // Finish the Redis handshake before entering subscriber mode.
        await Promise.all([redis.pub, redis.sub].map(async (client) => {
          if (client.status === 'end') throw new Error('Collaboration Redis connection is closed')
          if (client.status !== 'ready') await once(client, 'ready')
        }))
      },
    },
    redis,
  )
}

const hocuspocus = createCollaborationServer({
  url: process.env.COLLABORATION_URL || 'http://localhost:8080',
  extensions,
})

export default defineWebSocketHandler({
  open: (peer) => {
    // Nitro supplies a WebSocket and Fetch request rather than the Node request types.
    hocuspocus.handleConnection(
      peer.websocket as unknown as Parameters<typeof hocuspocus.handleConnection>[0],
      peer.request as unknown as Parameters<typeof hocuspocus.handleConnection>[1],
    )
  },
})
