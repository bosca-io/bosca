import { describe, it, expect } from 'vitest'
import {
  BoscaAuth,
  BoscaAuthError,
  InvalidCredentialsError,
  EmailNotVerifiedError,
  PrincipalNotVerifiedError,
  TokenExpiredError,
  NetworkError,
  OAuthError,
  GraphQLError,
  CookieStorage,
  LocalStorageStorage,
  MemoryStorage,
  createStorage,
  setupNuxtAuth,
  useAuth,
} from './index'
import * as index from './index'
import * as core from './core'

describe('index', () => {
  it('re-exports all public API classes and functions', () => {
    expect(BoscaAuth).toBeDefined()
    expect(BoscaAuthError).toBeDefined()
    expect(InvalidCredentialsError).toBeDefined()
    expect(EmailNotVerifiedError).toBeDefined()
    expect(PrincipalNotVerifiedError).toBeDefined()
    expect(TokenExpiredError).toBeDefined()
    expect(NetworkError).toBeDefined()
    expect(OAuthError).toBeDefined()
    expect(GraphQLError).toBeDefined()
    expect(CookieStorage).toBeDefined()
    expect(LocalStorageStorage).toBeDefined()
    expect(MemoryStorage).toBeDefined()
    expect(createStorage).toBeDefined()
    expect(setupNuxtAuth).toBeDefined()
    expect(useAuth).toBeDefined()
  })

  it('keeps the oauth URL helpers internal — BoscaAuth wraps them', () => {
    const surface = { ...core, ...index } as Record<string, unknown>
    expect(surface.startOAuthRedirect).toBeUndefined()
    expect(surface.getExchangeTokenFromUrl).toBeUndefined()
    expect(surface.getLinkTokenFromUrl).toBeUndefined()
    expect(surface.getLinkMethodsFromUrl).toBeUndefined()
  })

  it('exposes the vue-free surface via ./core (everything except the Nuxt integration)', () => {
    expect(core.BoscaAuth).toBeDefined()
    expect(core.BoscaAuthError).toBeDefined()
    expect(core.createStorage).toBeDefined()
    expect((core as Record<string, unknown>).setupNuxtAuth).toBeUndefined()
    expect((core as Record<string, unknown>).useAuth).toBeUndefined()
  })
})
