import { afterEach, describe, expect, test, vi } from 'vitest'
import { createApp, defineComponent, inject } from 'vue'
import { setupNuxtFeatureFlags, useFeatureFlags, FeatureFlagKey } from './nuxt_feature_flags'
import { BoscaSink } from './bosca'
import { FeatureFlagClient } from './feature_flags'

function createMockNuxtApp() {
  const app = createApp(defineComponent({ render() { return null } }))
  return {
    vueApp: {
      provide: app.provide.bind(app),
      runWithContext: app.runWithContext.bind(app),
    },
    _app: app,
  }
}

describe('setupNuxtFeatureFlags', () => {
  afterEach(() => {
    document.cookie = 'bml_iid=; Path=/; Max-Age=0'
    vi.restoreAllMocks()
  })

  test('uses the BML installation ID for its first feature evaluation', async () => {
    document.cookie = 'bml_iid=bml-installation; Path=/'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      data: { featureFlags: { evaluateAll: [] } },
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const { client } = setupNuxtFeatureFlags(nuxtApp, sink, { graphqlUrl: '/graphql' })

    await client.initialize()

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.variables.installationId).toBe('bml-installation')
  })

  test('creates a FeatureFlagClient and provides it via injection', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const result = setupNuxtFeatureFlags(nuxtApp, sink, {
      graphqlUrl: '/graphql',
      wsUrl: '/graphqlws',
    })

    expect(result).toBeDefined()
    expect(result.client).toBeInstanceOf(FeatureFlagClient)
  })

  test('creates client without wsUrl', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const result = setupNuxtFeatureFlags(nuxtApp, sink, {
      graphqlUrl: '/graphql',
    })

    expect(result.client).toBeDefined()
  })

  test('creates client with auth token', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const result = setupNuxtFeatureFlags(nuxtApp, sink, {
      graphqlUrl: '/graphql',
      authToken: 'my-token',
    })

    expect(result.client).toBeDefined()
  })

  test('creates client with auth token function', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const result = setupNuxtFeatureFlags(nuxtApp, sink, {
      graphqlUrl: '/graphql',
      authToken: () => 'dynamic-token',
    })

    expect(result.client).toBeDefined()
  })

  test('throws when called twice on the same app', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })

    setupNuxtFeatureFlags(nuxtApp, sink, { graphqlUrl: '/graphql' })
    expect(() =>
      setupNuxtFeatureFlags(nuxtApp, sink, { graphqlUrl: '/graphql' }),
    ).toThrow(/already been called/)
  })

  test('instance is retrievable via inject inside the app context', () => {
    const nuxtApp = createMockNuxtApp()
    const sink = new BoscaSink({ url: 'http://localhost:8080', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    const provided = setupNuxtFeatureFlags(nuxtApp, sink, { graphqlUrl: '/graphql' })

    const injected = nuxtApp.vueApp.runWithContext(() => inject(FeatureFlagKey))
    expect(injected).toBe(provided)
  })
})

describe('useFeatureFlags', () => {
  test('throws when no instance is provided', () => {
    const app = createApp(defineComponent({ render() { return null } }))
    expect(() => {
      app.runWithContext(() => useFeatureFlags())
    }).toThrow(/not initialized/)
  })
})
