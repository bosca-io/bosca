import { describe, it, expect, vi } from 'vitest'
import { DelayedAction } from './delayed_action'

describe('DelayedAction', () => {
  it('should call onComplete with true on success and false on failure', async () => {
    const onComplete = vi.fn()
    const success = new DelayedAction(async () => {}, onComplete)
    await success.promise()
    expect(onComplete).toHaveBeenCalledWith(true)

    const onCompleteFail = vi.fn()
    const failure = new DelayedAction(async () => { throw new Error('fail') }, onCompleteFail)
    await expect(failure.promise()).rejects.toThrow('fail')
    expect(onCompleteFail).toHaveBeenCalledWith(false)
  })

  it('should debounce: multiple delay() calls result in single execution', async () => {
    let count = 0
    const action = new DelayedAction(async () => { count++ }, () => {})
    action.delay()
    action.delay()
    action.delay()
    await action.promise()
    expect(count).toBe(1)
  })

  it('should re-throw when reject has already been consumed', async () => {
    let callCount = 0
    const action = new DelayedAction(
      async () => {
        callCount++
        if (callCount > 1) throw new Error('second call')
      },
      () => {},
    )
    await action.promise()

    // After first execution, resolve/reject are null.
    // Direct execute() should propagate the error via throw, not swallow it.
    await expect(action.execute()).rejects.toThrow('second call')
  })
})
