export class DelayedAction {

  private _promise: Promise<boolean>
  private action: () => Promise<void>
  private onComplete: (success: boolean) => void
  private timeout: any | null = null
  private resolve: ((value: boolean) => void) | null = null
  private reject: ((reason?: any) => void) | null = null

  constructor(action: () => Promise<void>, onComplete: (success: boolean) => void) {
    this.action = action
    this.onComplete = onComplete

    const self = this
    this._promise = new Promise((resolve, reject) => {
      self.resolve = resolve
      self.reject = reject
      self.delay()
    })
  }

  async execute() {
    let success = true
    try {
      await this.action()
      if (this.resolve) {
        this.resolve!(true)
      }
    } catch (e) {
      success = false
      if (this.reject) {
        this.reject!(e)
      } else {
        throw e
      }
    } finally {
      this.resolve = null
      this.reject = null
      this.onComplete(success)
    }
  }

  promise(): Promise<boolean> {
    return this._promise
  }

  delay() {
    if (this.timeout) {
      clearTimeout(this.timeout)
    }
    const self = this
    this.timeout = setTimeout(() => {
      self.timeout = null
      self.execute().catch(e => console.error('failed to execute delayed action: ', e))
    }, 500)
  }
}