import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'

const isAdmin = ref(false)
const load = vi.fn()
const navigate = vi.fn()
vi.stubGlobal('usePersonas', () => ({ isAdmin, load }))
vi.stubGlobal('navigateTo', navigate)
vi.stubGlobal('defineNuxtRouteMiddleware', (middleware: unknown) => middleware)
const { default: middleware } = await import('./artifacts-admin')
const route = {} as RouteLocationNormalized

describe('Artifact administrator routes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    isAdmin.value = false
  })
  it('waits for administrator access to resolve before admitting the page', async () => {
    load.mockImplementationOnce(async () => { isAdmin.value = true })
    await middleware(route, route)
    expect(load).toHaveBeenCalledOnce()
    expect(navigate).not.toHaveBeenCalled()
  })
  it('redirects non-administrators to repositories', async () => {
    await middleware(route, route)
    expect(navigate).toHaveBeenCalledWith('/artifacts/repositories')
  })
})
