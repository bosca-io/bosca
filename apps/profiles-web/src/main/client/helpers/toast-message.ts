import type { ClientContext, IslandUpdate } from "@bosca/bml"
import { signOutAndNavigate } from "./sign-out-link"

/** Each tag owns its notices; only the visible stacking surface is shared. */
export function toastMessage(ctx: ClientContext) {
  let sequence = Number(ctx.root.dataset.toastId) || 0
  const notices = new Set<() => void>()
  const show = () => {
    const next = Number(ctx.root.dataset.toastId)
    if (!Number.isFinite(next) || next <= sequence) return
    sequence = next
    const message = ctx.root.dataset.toastMessage?.trim()
    if (!message) return
    let container = document.getElementById("profiles-toast-container")
    if (!container) {
      container = document.createElement("div")
      container.id = "profiles-toast-container"
      container.className = "toast-container"
      container.setAttribute("aria-live", "polite")
      container.setAttribute("aria-atomic", "false")
      document.body.append(container)
    }
    const tone = ctx.root.dataset.toastTone === "error" ? "error" : "success"
    const toast = document.createElement("div")
    toast.className = `toast toast-${tone}`
    toast.setAttribute("role", tone === "error" ? "alert" : "status")
    const icon = document.createElement("span")
    icon.className = "toast-icon"
    icon.setAttribute("aria-hidden", "true")
    icon.textContent = tone === "error" ? "!" : "✓"
    const text = document.createElement("span")
    text.className = "toast-message"
    text.textContent = message
    toast.append(icon, text)
    const events = new AbortController()
    const actionHref = ctx.root.dataset.toastActionHref?.trim()
    const actionLabel = ctx.root.dataset.toastActionLabel?.trim()
    let disposed = false, closing = false
    if (actionHref && actionLabel) {
      const action = document.createElement("a")
      action.className = "toast-action"
      action.href = actionHref
      action.textContent = actionLabel
      if (ctx.root.hasAttribute("data-toast-action-sign-out")) {
        let pending = false
        action.addEventListener("click", event => {
          event.preventDefault()
          if (pending) return
          pending = true
          action.setAttribute("aria-disabled", "true")
          void signOutAndNavigate(action.href, () => !disposed)
        }, { signal: events.signal })
      }
      toast.append(action)
    }
    const close = document.createElement("button")
    close.className = "toast-close"
    close.type = "button"
    close.setAttribute("aria-label", "Dismiss notification")
    close.textContent = "×"
    toast.append(close)
    container.append(toast)
    let timer = 0, leaving = 0
    const destroy = () => {
      if (disposed) return
      disposed = true
      window.clearTimeout(timer)
      window.clearTimeout(leaving)
      window.cancelAnimationFrame(frame)
      events.abort()
      toast.remove()
      if (!container.childElementCount) container.remove()
      notices.delete(destroy)
    }
    const dismiss = () => {
      if (closing || disposed) return
      closing = true
      window.clearTimeout(timer)
      toast.classList.remove("toast-visible")
      toast.classList.add("toast-leaving")
      leaving = window.setTimeout(destroy, 200)
    }
    const schedule = () => {
      if (closing || disposed) return
      window.clearTimeout(timer)
      timer = window.setTimeout(dismiss, actionHref ? 7000 : 4000)
    }
    close.addEventListener("click", dismiss, { signal: events.signal })
    toast.addEventListener("mouseenter", () => window.clearTimeout(timer), { signal: events.signal })
    toast.addEventListener("mouseleave", schedule, { signal: events.signal })
    const frame = window.requestAnimationFrame(() => toast.classList.add("toast-visible"))
    notices.add(destroy)
    schedule()
  }
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) show()
  })
  ctx.onUnmount(() => { for (const destroy of notices) destroy() })
}
