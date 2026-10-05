import { ref } from 'vue'

export type ToastTone = 'info' | 'ok' | 'warn' | 'err'

export interface Toast {
  id: number
  message: string
  tone: ToastTone
  persistent: boolean
  progress?: number
}

export interface ProgressHandle {
  update: (progress: number, message?: string) => void
  complete: (message?: string) => void
  dismiss: () => void
}

let nextId = 0
const toasts = ref<Toast[]>([])

export function useToast() {
  function push(toast: Toast, duration?: number) {
    toasts.value = [...toasts.value, toast]
    if (typeof window !== 'undefined' && !toast.persistent && toast.progress === undefined) {
      setTimeout(() => dismiss(toast.id), duration ?? 4000)
    }
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  function patchToast(id: number, patch: Partial<Omit<Toast, 'id'>>) {
    toasts.value = toasts.value.map((t) => (t.id === id ? { ...t, ...patch } : t))
  }

  function show(message: string, tone: ToastTone = 'info', duration = 4000) {
    push({ id: nextId++, message, tone, persistent: false }, duration)
  }

  function showPersistent(message: string, tone: ToastTone = 'info'): number {
    const id = nextId++
    push({ id, message, tone, persistent: true })
    return id
  }

  function showProgress(message: string, tone: ToastTone = 'info'): ProgressHandle {
    const id = nextId++
    push({ id, message, tone, persistent: true, progress: 0 })

    return {
      update(progress: number, msg?: string) {
        const patch: Partial<Toast> = { progress: Math.min(100, Math.max(0, progress)) }
        if (msg !== undefined) patch.message = msg
        patchToast(id, patch)
      },
      complete(msg?: string) {
        patchToast(id, {
          tone: 'ok',
          progress: 100,
          persistent: false,
          ...(msg !== undefined ? { message: msg } : {}),
        })
        if (typeof window !== 'undefined') {
          setTimeout(() => dismiss(id), 2000)
        }
      },
      dismiss: () => dismiss(id),
    }
  }

  function success(message: string, duration?: number) { show(message, 'ok', duration) }
  function error(message: string, duration?: number) { show(message, 'err', duration) }
  function warn(message: string, duration?: number) { show(message, 'warn', duration) }
  function info(message: string, duration?: number) { show(message, 'info', duration) }

  const colorToTone: Record<string, ToastTone> = { error: 'err', success: 'ok', warning: 'warn', info: 'info' }

  function add(opts: { title: string; description?: string; color?: string; duration?: number }) {
    const tone = colorToTone[opts.color ?? 'info'] ?? 'info'
    const message = opts.description ? `${opts.title}: ${opts.description}` : opts.title
    show(message, tone, opts.duration)
  }

  return { toasts, show, showPersistent, showProgress, dismiss, success, error, warn, info, add }
}
