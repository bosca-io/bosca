import { Redis } from '@hocuspocus/extension-redis'
import { once } from 'node:events'
import { createCollaborationServer } from '../utils/collaboration'

const redis = new Redis({
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT || '6380'),
  prefix: process.env.REDIS_PREFIX || 'hocuspocus',
  options: { password: process.env.REDIS_PASSWORD },
})

const hocuspocus = createCollaborationServer({
  url: process.env.COLLABORATION_URL || 'http://localhost:8080',
  extensions: [
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
  ],
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
