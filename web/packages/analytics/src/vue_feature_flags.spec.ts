/**
 * Tests for the vue_feature_flags composable.
 *
 * Since vue_feature_flags.ts uses Vue's lifecycle hooks (onMounted, onUnmounted)
 * and reactivity (ref, computed), we test it using Vue's test utilities. The
 * composable requires a component context for lifecycle hooks to fire.
 *
 * @vitest-environment happy-dom
 */
import { describe, expect, test, vi, beforeEach, afterEach } from 'vitest'
import { createApp, defineComponent, nextTick, ref as vueRef } from 'vue'

// We need to mock useFeatureFlags from nuxt_feature_flags before import
const { mockClient, state } = vi.hoisted(() => {
  const client = {
    getFlag: null as any,
    onChange: null as any,
  }
  return {
    mockClient: client,
    state: { throwOnUseFeatureFlags: false },
  }
})

vi.mock('./nuxt_feature_flags', () => ({
  useFeatureFlags: () => {
    if (state.throwOnUseFeatureFlags) throw new Error('not initialized')
    return { client: mockClient }
  },
}))

import { useFeatureFlag } from './vue_feature_flags'

describe('useFeatureFlag', () => {
  let onChangeListeners: Array<(flags: Map<string, any>) => void>

  beforeEach(() => {
    onChangeListeners = []
    state.throwOnUseFeatureFlags = false

    mockClient.getFlag = vi.fn().mockReturnValue(null)
    mockClient.onChange = vi.fn((listener: (flags: Map<string, any>) => void) => {
      onChangeListeners.push(listener)
      return () => {
        const idx = onChangeListeners.indexOf(listener)
        if (idx >= 0) onChangeListeners.splice(idx, 1)
      }
    })
  })

  function broadcastChange(flags: Record<string, any>) {
    const map = new Map(Object.entries(flags))
    for (const l of [...onChangeListeners]) l(map)
  }

  /**
   * Runs useFeatureFlag inside a real Vue component setup context so that
   * onMounted/onUnmounted lifecycle hooks fire correctly.
   */
  function createTestComponent(key: string, defaultValue?: any) {
    let result: ReturnType<typeof useFeatureFlag> | null = null
    let isMounted = false
    let unmountFn: (() => void) | null = null

    const app = createApp(
      defineComponent({
        setup() {
          result = useFeatureFlag(key, defaultValue)
          return {}
        },
        render() { return null },
      })
    )

    // Mount to a detached DOM element
    const el = document.createElement('div')
    app.mount(el)
    isMounted = true
    unmountFn = () => { app.unmount(); isMounted = false }

    return {
      get value() { return result!.value.value },
      get variationKey() { return result!.variationKey.value },
      unmount: () => unmountFn?.(),
      result: result!,
    }
  }

  test('returns default value when flag not loaded', () => {
    const comp = createTestComponent('test-flag', 'default-val')
    expect(comp.value).toBe('default-val')
    comp.unmount()
  })

  test('returns default false when no defaultValue provided', () => {
    const comp = createTestComponent('test-flag')
    expect(comp.value).toBe(false)
    comp.unmount()
  })

  test('returns undefined variationKey when flag not loaded', () => {
    const comp = createTestComponent('test-flag', true)
    expect(comp.variationKey).toBeUndefined()
    comp.unmount()
  })

  test('reads flag value on mount', () => {
    mockClient.getFlag.mockReturnValue({
      flagKey: 'my-flag',
      value: 'active-value',
      variationKey: 'variant-a',
    })

    const comp = createTestComponent('my-flag', 'default')
    expect(comp.value).toBe('active-value')
    expect(comp.variationKey).toBe('variant-a')
    comp.unmount()
  })

  test('subscribes to onChange on mount', () => {
    createTestComponent('flag-key', false)
    expect(mockClient.onChange).toHaveBeenCalledTimes(1)
    expect(onChangeListeners).toHaveLength(1)
  })

  test('updates value when flag changes via onChange', async () => {
    const comp = createTestComponent('dynamic-flag', 'initial')
    expect(comp.value).toBe('initial')

    broadcastChange({
      'dynamic-flag': { flagKey: 'dynamic-flag', value: 'changed', variationKey: 'v2' },
    })

    await nextTick()

    expect(comp.value).toBe('changed')
    expect(comp.variationKey).toBe('v2')
    comp.unmount()
  })

  test('falls back to default when updated flag is not in broadcast', async () => {
    mockClient.getFlag.mockReturnValue({
      flagKey: 'some-flag', value: 'loaded', variationKey: 'v1',
    })

    const comp = createTestComponent('some-flag', 'fallback')
    expect(comp.value).toBe('loaded')

    broadcastChange({})

    await nextTick()

    expect(comp.value).toBe('fallback')
    comp.unmount()
  })

  test('unsubscribes on unmount', () => {
    const comp = createTestComponent('cleanup-flag', false)
    expect(onChangeListeners).toHaveLength(1)

    comp.unmount()
    expect(onChangeListeners).toHaveLength(0)
  })

  test('handles feature flags not initialized gracefully', () => {
    state.throwOnUseFeatureFlags = true

    const comp = createTestComponent('unavailable-flag', 'safe-default')
    expect(comp.value).toBe('safe-default')
    expect(comp.variationKey).toBeUndefined()
    comp.unmount()
  })

  test('uses null-coalescing for null flag value', () => {
    mockClient.getFlag.mockReturnValue({
      flagKey: 'null-flag', value: null, variationKey: undefined,
    })

    const comp = createTestComponent('null-flag', 'the-default')
    expect(comp.value).toBe('the-default')
    comp.unmount()
  })

  test('reads flag from client synchronously during setup', () => {
    mockClient.getFlag.mockImplementation((key: string) => {
      if (key === 'ssr-flag') {
        return { flagKey: 'ssr-flag', value: 'server-resolved', variationKey: 'treatment' }
      }
      return null
    })

    const comp = createTestComponent('ssr-flag', 'ssr-default')
    expect(comp.value).toBe('server-resolved')
    expect(comp.variationKey).toBe('treatment')
    comp.unmount()
  })

  test('multiple onChange broadcasts update the value each time', async () => {
    const comp = createTestComponent('evolving-flag', 'v0')

    broadcastChange({ 'evolving-flag': { flagKey: 'evolving-flag', value: 'v1' } })
    await nextTick()
    expect(comp.value).toBe('v1')

    broadcastChange({ 'evolving-flag': { flagKey: 'evolving-flag', value: 'v2' } })
    await nextTick()
    expect(comp.value).toBe('v2')

    comp.unmount()
  })
})
