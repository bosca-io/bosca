import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { test } from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'

const require = createRequire(import.meta.url)
const ts = require('typescript')
const Y = require('yjs')
const { HocuspocusProvider, HocuspocusProviderWebsocket } = require('@hocuspocus/provider')
const { WebSocket, WebSocketServer } = createRequire(require.resolve('@hocuspocus/server'))('ws')
const { Redis } = require('@hocuspocus/extension-redis')
const source = readFileSync(new URL('../server/utils/collaboration.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText
const factoryModule = { exports: {} }
new Function('require', 'module', 'exports', compiled)(require, factoryModule, factoryModule.exports)
const { createCollaborationServer } = factoryModule.exports

async function until(predicate) {
  for (let attempt = 0; attempt < (process.env.COLLABORATION_TEST_NITRO_ENTRY ? 1400 : 200); attempt++) {
    if (predicate()) return
    await delay(25)
  }
  throw new Error(`Collaboration condition timed out: ${predicate.toString()}`)
}

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return `http://127.0.0.1:${server.address().port}`
}

async function fixture(t, redis = false) {
  const name = `${randomUUID()}.1`
  const initial = new Y.Doc()
  initial.getMap('scratch').set('secret', 'private content')
  let persisted = Y.encodeStateAsUpdate(initial)
  const tokens = new Set(['owner', 'renewed', 'renewed-again', 'peer'])
  const requests = []
  const api = createServer(async (request, response) => {
    if (request.url === '/public-login') {
      response.writeHead(200).end('Public login page')
      return
    }
    if (request.url === '/api/v1/profiles/me') {
      if (!request.headers.cookie) response.writeHead(401).end()
      else response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify([{ id: name.split('.')[0] }]))
      return
    }
    const token = request.headers.authorization?.replace(/^Bearer /, '')
    requests.push({ method: request.method, token, path: request.url })
    if (token === 'redirected' && request.method === 'HEAD') {
      response.writeHead(302, { Location: '/public-login' }).end()
      return
    }
    if (!tokens.has(token) || request.url !== `/api/v1/content/metadata/${name.split('.')[0]}/document/collaboration?version=1`) {
      response.writeHead(403).end()
      return
    }
    if (request.method === 'HEAD') {
      if (token === 'renewed') await delay(150)
      response.writeHead(200).end()
    }
    else if (request.method === 'GET') response.writeHead(200).end(persisted)
    else if (request.method === 'PUT') {
      const chunks = []
      for await (const chunk of request) chunks.push(chunk)
      persisted = Buffer.concat(chunks)
      response.writeHead(200).end()
    } else response.writeHead(405).end()
  })
  const apiUrl = await listen(api)
  const servers = []
  const clients = []
  t.after(async () => {
    for (const client of clients) {
      client.provider.destroy()
      client.socket.destroy()
      client.doc.destroy()
    }
    for (const { hocus, sockets, http, child } of servers) {
      if (child) {
        const exited = new Promise(resolve => child.once('exit', resolve))
        child.kill('SIGTERM')
        await exited
        continue
      }
      hocus.closeConnections()
      for (const socket of sockets.clients) socket.terminate()
      await until(() => hocus.getConnectionsCount() === 0)
      await until(() => hocus.getDocumentsCount() === 0 && hocus.unloadingDocuments.size === 0)
      await hocus.hooks('onDestroy', { instance: hocus })
      await new Promise(resolve => sockets.close(resolve))
      await new Promise(resolve => http.close(resolve))
    }
    api.closeAllConnections()
    await new Promise(resolve => api.close(resolve))
    initial.destroy()
  })
  const prefix = `bosca-security-test-${randomUUID()}`
  async function server() {
    if (process.env.COLLABORATION_TEST_NITRO_ENTRY) {
      assert.ok(process.env.COLLABORATION_TEST_REDIS_PORT, 'A Redis port is required for the built Nitro integration')
      const portHolder = createServer()
      const url = await listen(portHolder)
      const port = portHolder.address().port
      await new Promise(resolve => portHolder.close(resolve))
      const child = spawn(process.execPath, [process.env.COLLABORATION_TEST_NITRO_ENTRY], {
        env: { ...process.env, NITRO_HOST: '127.0.0.1', NITRO_PORT: String(port),
          NUXT_API_URL: apiUrl, COLLABORATION_URL: apiUrl,
          REDIS_HOST: '127.0.0.1', REDIS_PORT: process.env.COLLABORATION_TEST_REDIS_PORT,
          REDIS_PREFIX: prefix, REDIS_PASSWORD: '' },
        stdio: ['ignore', 'pipe', 'pipe']
      })
      servers.push({ child })
      let output = ''
      t.after(() => { if (output.includes('Error')) console.error(output) })
      child.stdout.on('data', chunk => { output += chunk })
      child.stderr.on('data', chunk => { output += chunk })
      for (let attempt = 0; attempt < 200; attempt++) {
        if (child.exitCode !== null) throw new Error(`Nitro exited: ${output}`)
        try {
          if ((await fetch(`${url}/health`)).ok) return url.replace('http:', 'ws:') + '/collaboration'
        } catch { /* Wait for the isolated server to bind its port. */ }
        await delay(25)
      }
      throw new Error(`Nitro did not start: ${output}`)
    }
    const extensions = redis ? [new Redis({
      host: process.env.COLLABORATION_TEST_REDIS_HOST || '127.0.0.1',
      port: Number(process.env.COLLABORATION_TEST_REDIS_PORT),
      prefix
    })] : []
    const hocus = createCollaborationServer({ url: apiUrl, extensions, permissionCheckInterval: 250 })
    hocus.configure({ debounce: 20, maxDebounce: 100, quiet: true })
    const http = createServer()
    const sockets = new WebSocketServer({ server: http })
    sockets.on('connection', (socket, request) => hocus.handleConnection(socket, request))
    const url = (await listen(http)).replace('http:', 'ws:')
    servers.push({ hocus, sockets, http })
    return url
  }
  function connect(url, token, documentName = name) {
    const doc = new Y.Doc()
    const events = { synced: false, denied: false, closed: false }
    let currentToken = token
    const Socket = process.env.COLLABORATION_TEST_NITRO_ENTRY ? class extends WebSocket {
      constructor(url, protocols) {
        super(url, protocols, { headers: token ? { Cookie: `_bat=${token}` } : {} })
      }
    } : WebSocket
    const socket = new HocuspocusProviderWebsocket({ url, WebSocketPolyfill: Socket })
    const provider = new HocuspocusProvider({
      websocketProvider: socket, name: documentName, document: doc,
      token: () => currentToken,
      onSynced: () => { events.synced = true },
      onAuthenticationFailed: () => { events.denied = true },
      onClose: () => { events.closed = true }
    })
    provider.attach()
    const client = { doc, provider, socket, events, refresh: async (token) => { currentToken = token; await provider.sendToken() } }
    clients.push(client)
    return client
  }
  return { name, tokens, requests, server, connect, persisted: () => persisted }
}

test('loaded documents require each participant, support refresh, and stop revoked participants', { timeout: 60000 }, async (t) => {
  const f = await fixture(t)
  const url = await f.server()
  const owner = f.connect(url, 'owner')
  await until(() => owner.events.synced)
  assert.equal(owner.doc.getMap('scratch').get('secret'), 'private content')
  for (const token of ['denied', '', 'redirected']) {
    const denied = f.connect(url, token)
    await until(() => denied.events.denied || denied.events.closed)
    assert.equal(denied.events.synced, false)
    assert.equal(denied.doc.getMap('scratch').get('secret'), undefined)
    denied.doc.getMap('scratch').set('attack', 'denied write')
  }
  const malformed = f.connect(url, 'owner', `${f.name}/../../other`)
  await until(() => malformed.events.denied || malformed.events.closed)
  assert.equal(malformed.events.synced, false)
  assert.equal(f.requests.filter(request => request.method === 'GET').length, 1)
  assert.equal(owner.doc.getMap('scratch').has('attack'), false)

  // A replacement token must work even when the previous token has expired.
  f.tokens.delete('owner')
  await owner.refresh('renewed')
  owner.doc.getMap('scratch').set('during-refresh', 'allowed')
  await until(() => {
    const saved = new Y.Doc()
    Y.applyUpdate(saved, f.persisted())
    const allowed = saved.getMap('scratch').get('during-refresh') === 'allowed'
    saved.destroy()
    return allowed
  })
  await until(() => f.requests.some(request => request.token === 'renewed' && request.method === 'HEAD'))
  f.tokens.delete('renewed')
  await owner.refresh('renewed-again')
  await until(() => f.requests.some(request => request.token === 'renewed-again' && request.method === 'HEAD'))
  owner.doc.getMap('scratch').set('allowed', 'persisted edit')
  await until(() => f.requests.some(request => request.token === 'renewed-again' && request.method === 'PUT'))
  const saved = new Y.Doc()
  Y.applyUpdate(saved, f.persisted())
  assert.equal(saved.getMap('scratch').get('allowed'), 'persisted edit')
  assert.equal(saved.getMap('scratch').has('attack'), false)
  saved.destroy()

  const peer = f.connect(url, 'peer')
  await until(() => peer.events.synced)
  f.tokens.delete('peer')
  await until(() => peer.events.closed)
  owner.doc.getMap('scratch').set('after-revocation', 'private')
  await delay(100)
  assert.equal(peer.doc.getMap('scratch').has('after-revocation'), false)
  await until(() => {
    const saved = new Y.Doc()
    Y.applyUpdate(saved, f.persisted())
    const persisted = saved.getMap('scratch').get('after-revocation') === 'private'
    saved.destroy()
    return persisted
  })
  f.tokens.delete('renewed-again')
  owner.doc.getMap('scratch').set('revoked-write', 'blocked')
  await until(() => owner.events.closed)
  await delay(100)
  const final = new Y.Doc()
  Y.applyUpdate(final, f.persisted())
  assert.equal(final.getMap('scratch').has('revoked-write'), false)
  final.destroy()
  assert.equal(f.requests.some(request => request.method === 'PUT' && request.token === 'denied'), false)
})

test('Redis-connected nodes authorize joins and updates independently', {
  timeout: 60000,
  skip: !process.env.COLLABORATION_TEST_REDIS_PORT && 'Set COLLABORATION_TEST_REDIS_PORT to run the Redis integration'
}, async (t) => {
  const f = await fixture(t, true)
  const first = await f.server()
  const second = await f.server()
  const owner = f.connect(first, 'owner')
  await until(() => owner.events.synced)
  const peer = f.connect(second, 'peer')
  await until(() => peer.events.synced)
  owner.doc.getMap('scratch').set('cross-node', 'allowed')
  await until(() => peer.doc.getMap('scratch').get('cross-node') === 'allowed')
  const denied = f.connect(second, 'denied')
  await until(() => denied.events.denied || denied.events.closed)
  assert.equal(denied.events.synced, false)
  assert.equal(denied.doc.getMap('scratch').get('secret'), undefined)
  denied.doc.getMap('scratch').set('cross-node-attack', 'blocked')
  f.tokens.delete('peer')
  peer.doc.getMap('scratch').set('revoked-cross-node', 'blocked')
  await until(() => peer.events.closed)
  await delay(100)
  assert.equal(owner.doc.getMap('scratch').has('cross-node-attack'), false)
  assert.equal(owner.doc.getMap('scratch').has('revoked-cross-node'), false)
})
