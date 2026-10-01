import { describe, it, expect } from 'vitest'
import {
  BoscaAuthError,
  InvalidCredentialsError,
  EmailNotVerifiedError,
  PrincipalNotVerifiedError,
  TokenExpiredError,
  NetworkError,
  OAuthError,
  GraphQLError,
} from './errors'

describe('BoscaAuthError', () => {
  it('stores message and code', () => {
    const err = new BoscaAuthError('test message', 'test/code')
    expect(err.message).toBe('test message')
    expect(err.code).toBe('test/code')
    expect(err.name).toBe('BoscaAuthError')
    expect(err).toBeInstanceOf(Error)
  })
})

describe('InvalidCredentialsError', () => {
  it('uses default message and correct code', () => {
    const err = new InvalidCredentialsError()
    expect(err.message).toBe('Invalid email or password')
    expect(err.code).toBe('auth/invalid-credentials')
    expect(err.name).toBe('InvalidCredentialsError')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })

  it('accepts a custom message', () => {
    const err = new InvalidCredentialsError('custom')
    expect(err.message).toBe('custom')
    expect(err.code).toBe('auth/invalid-credentials')
  })
})

describe('EmailNotVerifiedError', () => {
  it('uses default message and correct code', () => {
    const err = new EmailNotVerifiedError()
    expect(err.code).toBe('auth/email-not-verified')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })
})

describe('PrincipalNotVerifiedError', () => {
  it('uses default message and correct code', () => {
    const err = new PrincipalNotVerifiedError()
    expect(err.code).toBe('auth/principal-not-verified')
    expect(err.name).toBe('PrincipalNotVerifiedError')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })

  it('is distinct from EmailNotVerifiedError', () => {
    expect(new PrincipalNotVerifiedError()).not.toBeInstanceOf(EmailNotVerifiedError)
    expect(new EmailNotVerifiedError()).not.toBeInstanceOf(PrincipalNotVerifiedError)
  })
})

describe('TokenExpiredError', () => {
  it('uses default message and correct code', () => {
    const err = new TokenExpiredError()
    expect(err.code).toBe('auth/token-expired')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })
})

describe('NetworkError', () => {
  it('uses default message and correct code', () => {
    const err = new NetworkError()
    expect(err.code).toBe('auth/network-error')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })
})

describe('OAuthError', () => {
  it('uses default message and correct code', () => {
    const err = new OAuthError()
    expect(err.code).toBe('auth/oauth-error')
    expect(err).toBeInstanceOf(BoscaAuthError)
  })
})

describe('GraphQLError', () => {
  it('uses correct code and stores errors array', () => {
    const rawErrors = [{ message: 'field error' }]
    const err = new GraphQLError('operation failed', rawErrors)
    expect(err.code).toBe('auth/graphql-error')
    expect(err.errors).toBe(rawErrors)
    expect(err).toBeInstanceOf(BoscaAuthError)
  })

  it('defaults to empty errors array', () => {
    const err = new GraphQLError('fail')
    expect(err.errors).toEqual([])
  })
})
