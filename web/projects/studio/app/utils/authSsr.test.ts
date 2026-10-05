import { describe, expect, it } from 'vitest'
import { buildSsrTokenStorage } from './authSsr'

describe('buildSsrTokenStorage', () => {
  it('reads the configured custom auth cookies', () => {
    const token = 'custom-token'
    const storage = buildSsrTokenStorage(
      `_bat_preview=${token}; _bat_preview_rt=refresh`,
      '_bat_preview',
    )

    expect(storage.getToken()).toBe(token)
    expect(storage.getRefreshToken()).toBe('refresh')
  })

  it('ignores the default cookie when a custom prefix is configured', () => {
    const token = 'default-token'
    const storage = buildSsrTokenStorage(`_bat=${token}; _bat_rt=default-refresh`, '_bat_preview')

    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
  })
})
