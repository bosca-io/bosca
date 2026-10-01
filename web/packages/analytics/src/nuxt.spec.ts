// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setupNuxtAnalytics, useAnalytics, AnalyticsKey } from './nuxt'
import { removeSink } from './sink'
import * as sink from './sink'
import type { NuxtAnalyticsInstance, NuxtAppLike } from './nuxt'
import type { InjectionKey } from 'vue'

vi.mock('./sink', { spy: true })

function createMockNuxtApp(): NuxtAppLike & {
  hooks: Map<string, Function[]>
  provided: Map<symbol, any>
} {
  const hooks = new Map<string, Function[]>()
  const provided = new Map<symbol, any>()
  return {
    hooks,
    provided,
    hook(name: string, fn: (...args: any[]) => void) {
      if (!hooks.has(name)) hooks.set(name, [])
      hooks.get(name)!.push(fn)
    },
    vueApp: {
      provide<T>(key: InjectionKey<T>, value: T) {
        provided.set(key as symbol, value)
      },
      runWithContext<T>(fn: () => T): T {
        return fn()
      },
    },
  }
}

let logErrorSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  vi.clearAllMocks()
  logErrorSpy = vi.spyOn(sink, 'logError').mockImplementation(() => Promise.resolve())
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: string | URL | Request) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (url.endsWith('/installation')) {
      return new Response(JSON.stringify({ id: 'test-iid' }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response('', { status: 202 })
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('setupNuxtAnalytics', () => {
  let instance: NuxtAnalyticsInstance

  afterEach(() => {
    instance?.instrumentation.stop()
    removeSink(instance?.sink)
  })

  it('should provide analytics instance via Vue injection', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
      userId: 'user-42',
    })

    expect(nuxtApp.provided.has(AnalyticsKey as symbol)).toBe(true)
    expect(nuxtApp.provided.get(AnalyticsKey as symbol)).toBe(instance)
    expect(instance.sink).toBeDefined()
    expect(instance.instrumentation).toBeDefined()
    expect((instance.sink as unknown as { context: { user_id?: string } }).context.user_id).toBe('user-42')
  })

  it('should throw if called twice on the same app instance', () => {
    const nuxtApp = createMockNuxtApp()

    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    // Simulate inject returning the existing instance on second call
    nuxtApp.vueApp.runWithContext = () => instance as any

    expect(() =>
      setupNuxtAnalytics(nuxtApp, {
        url: 'http://localhost:8081',
        appId: 'test',
        appVersion: '1.0',
        clientId: 'web',
      }),
    ).toThrow('setupNuxtAnalytics has already been called')
  })

  it('should register vue:error and app:error hooks', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    expect(nuxtApp.hooks.has('vue:error')).toBe(true)
    expect(nuxtApp.hooks.has('app:error')).toBe(true)
  })

  it('should capture vue:error as a non-fatal error event', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    const error = new TypeError('Cannot read property of null')
    nuxtApp.hooks.get('vue:error')![0](error)

    expect(logErrorSpy).toHaveBeenCalledTimes(1)
    const [errorInfo, element] = logErrorSpy.mock.calls[0]
    expect(errorInfo.message).toBe('Cannot read property of null')
    expect(errorInfo.type).toBe('TypeError')
    expect(errorInfo.fatal).toBe(false)
    expect(errorInfo.stack_trace).toBeDefined()
    expect(element.type).toBe('vue_error')
  })

  it('should capture app:error as a fatal error event', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    const error = new Error('500 Internal Server Error')
    nuxtApp.hooks.get('app:error')![0](error)

    expect(logErrorSpy).toHaveBeenCalledTimes(1)
    const [errorInfo, element] = logErrorSpy.mock.calls[0]
    expect(errorInfo.message).toBe('500 Internal Server Error')
    expect(errorInfo.fatal).toBe(true)
    expect(element.type).toBe('nuxt_error')
  })

  it('should handle string errors from vue:error', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    nuxtApp.hooks.get('vue:error')![0]('something broke')

    const [errorInfo] = logErrorSpy.mock.calls[0]
    expect(errorInfo.message).toBe('something broke')
    expect(errorInfo.type).toBe('Error')
    expect(errorInfo.stack_trace).toBeUndefined()
  })

  it('should handle non-Error, non-string values from app:error', () => {
    const nuxtApp = createMockNuxtApp()
    instance = setupNuxtAnalytics(nuxtApp, {
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
    })

    nuxtApp.hooks.get('app:error')![0]({ code: 404 })

    const [errorInfo] = logErrorSpy.mock.calls[0]
    expect(errorInfo.message).toBe('Unknown framework error')
  })
})

describe('useAnalytics', () => {
  it('should throw when analytics is not initialized', () => {
    expect(() => useAnalytics()).toThrow('Analytics not initialized')
  })
})
