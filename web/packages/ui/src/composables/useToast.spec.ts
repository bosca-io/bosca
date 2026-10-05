import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useToast } from './useToast'

beforeEach(() => {
  vi.useFakeTimers()
  const { toasts } = useToast()
  toasts.value = []
})

describe('useToast', () => {
  it('show adds a toast', () => {
    const { show, toasts } = useToast()
    show('Hello')
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].message).toBe('Hello')
    expect(toasts.value[0].tone).toBe('info')
    expect(toasts.value[0].persistent).toBe(false)
  })

  it('show auto-dismisses after duration', () => {
    const { show, toasts } = useToast()
    show('Hello', 'info', 2000)
    expect(toasts.value).toHaveLength(1)
    vi.advanceTimersByTime(2000)
    expect(toasts.value).toHaveLength(0)
  })

  it('show uses default 4000ms duration', () => {
    const { show, toasts } = useToast()
    show('Hello')
    vi.advanceTimersByTime(3999)
    expect(toasts.value).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(toasts.value).toHaveLength(0)
  })

  it('dismiss removes a specific toast', () => {
    const { show, dismiss, toasts } = useToast()
    show('A')
    show('B')
    const id = toasts.value[0].id
    dismiss(id)
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].message).toBe('B')
  })

  it('showPersistent creates persistent toast', () => {
    const { showPersistent, toasts } = useToast()
    const id = showPersistent('Loading…')
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].persistent).toBe(true)
    vi.advanceTimersByTime(10000)
    expect(toasts.value).toHaveLength(1)
    expect(typeof id).toBe('number')
  })

  it('showProgress creates progress toast', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Uploading')
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].progress).toBe(0)
    expect(toasts.value[0].persistent).toBe(true)

    handle.update(50, 'Half done')
    expect(toasts.value[0].progress).toBe(50)
    expect(toasts.value[0].message).toBe('Half done')
  })

  it('showProgress handle.update clamps progress', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Test')
    handle.update(150)
    expect(toasts.value[0].progress).toBe(100)
    handle.update(-10)
    expect(toasts.value[0].progress).toBe(0)
  })

  it('showProgress handle.complete sets 100% and auto-dismisses', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Uploading')
    handle.complete('Done!')
    expect(toasts.value[0].progress).toBe(100)
    expect(toasts.value[0].tone).toBe('ok')
    expect(toasts.value[0].message).toBe('Done!')
    expect(toasts.value[0].persistent).toBe(false)
    vi.advanceTimersByTime(2000)
    expect(toasts.value).toHaveLength(0)
  })

  it('showProgress handle.complete without message keeps original', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Uploading')
    handle.complete()
    expect(toasts.value[0].message).toBe('Uploading')
  })

  it('showProgress handle.dismiss removes immediately', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Test')
    handle.dismiss()
    expect(toasts.value).toHaveLength(0)
  })

  it('showProgress handle.update without message keeps original', () => {
    const { showProgress, toasts } = useToast()
    const handle = showProgress('Original')
    handle.update(50)
    expect(toasts.value[0].message).toBe('Original')
  })

  it('success helper uses ok tone', () => {
    const { success, toasts } = useToast()
    success('Saved')
    expect(toasts.value[0].tone).toBe('ok')
  })

  it('error helper uses err tone', () => {
    const { error, toasts } = useToast()
    error('Failed')
    expect(toasts.value[0].tone).toBe('err')
  })

  it('warn helper uses warn tone', () => {
    const { warn, toasts } = useToast()
    warn('Warning')
    expect(toasts.value[0].tone).toBe('warn')
  })

  it('info helper uses info tone', () => {
    const { info, toasts } = useToast()
    info('Info')
    expect(toasts.value[0].tone).toBe('info')
  })

  it('each toast gets a unique id', () => {
    const { show, toasts } = useToast()
    show('A')
    show('B')
    expect(toasts.value[0].id).not.toBe(toasts.value[1].id)
  })
})
