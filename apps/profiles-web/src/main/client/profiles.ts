/// <reference lib="es2020" />
/// <reference lib="dom" />

import { BoscaAuth } from "@bosca/auth-client-browser/core"

interface ProfilesWebConfig {
  cookieDomain?: string
}

interface BmlRuntimeConfig {
  authCookiePrefix?: string
}

const auth = new BoscaAuth({
  apiUrl: "",
  storage: "cookie",
  tokenName: globalThis.bmlConfig?.authCookiePrefix,
  cookieDomain: globalThis.profilesWebConfig?.cookieDomain,
})

declare global {
  var auth: BoscaAuth
  var bmlConfig: BmlRuntimeConfig | undefined
  var profilesWebConfig: ProfilesWebConfig | undefined
  var profilesReturnPath: (requested: string | null) => string
}

window.auth = auth

function profilesReturnPath(requested: string | null) {
  if (!requested?.startsWith("/")) return "/"
  try {
    const target = new URL(requested, location.origin)
    if (target.origin === location.origin) {
      return `${target.pathname}${target.search}${target.hash}`
    }
  } catch {
    // Keep the account overview as the safe destination.
  }
  return "/"
}

window.profilesReturnPath = profilesReturnPath

async function initializeProfilesAuth(auth: BoscaAuth) {
  try {
    await auth.initialize()
  } catch (error) {
    console.warn("profiles: session restore failed", error)
  }

  const redirectResult = await auth.handleRedirectResult().catch((error) => {
    console.warn("profiles: OAuth exchange failed", error)
    return null
  })
  if (redirectResult) {
    const requested = new URLSearchParams(location.search).get("redirect")
    location.replace(profilesReturnPath(requested))
    return
  }
}

void initializeProfilesAuth(auth).catch((error) => console.warn("profiles: authentication initialization failed", error))

const recentUserActions = new Map<string, number>()
const localDateTimeFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
})

function bmlStateKey(element: Element) {
  return element.closest<HTMLElement>("[data-bml-state-key]")?.dataset.bmlStateKey ?? window.location.pathname
}

function noteUserAction(event: Event) {
  const target = event.target instanceof Element ? event.target : null
  const action = target?.closest<HTMLElement>("[data-bml-method]")
  if (action) recentUserActions.set(bmlStateKey(action), Date.now())
}

function toastContainer() {
  const existing = document.getElementById("profiles-toast-container")
  if (existing) return existing

  const container = document.createElement("div")
  container.id = "profiles-toast-container"
  container.className = "toast-container"
  container.setAttribute("aria-live", "polite")
  container.setAttribute("aria-atomic", "false")
  document.body.append(container)
  return container
}

const consumedToasts = new Set<string>()

function consumeToastSource(source: HTMLElement) {
  if (source.dataset.toastConsumed === "true") return
  source.dataset.toastConsumed = "true"

  const message = source.textContent?.trim()
  const tone = source.dataset.toastTone === "error" ? "error" : "success"
  const actionHref = source.dataset.toastActionHref?.trim()
  const actionLabel = source.dataset.toastActionLabel?.trim()
  const actionSignOut = source.hasAttribute("data-toast-action-sign-out")
  const stateKey = bmlStateKey(source)
  const toastKey = `${stateKey}:${source.dataset.toastId}:${tone}:${message}`
  source.remove()
  if (!message) return
  const actionAt = recentUserActions.get(stateKey)
  if (actionAt == null || Date.now() - actionAt > 30_000) return
  if (consumedToasts.has(toastKey)) return
  consumedToasts.add(toastKey)

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

  if (actionHref && actionLabel) {
    const action = document.createElement("a")
    action.className = "toast-action"
    action.href = actionHref
    action.textContent = actionLabel
    if (actionSignOut) action.setAttribute("data-sign-out-before-navigation", "")
    toast.append(action)
  }

  const close = document.createElement("button")
  close.className = "toast-close"
  close.type = "button"
  close.setAttribute("aria-label", "Dismiss notification")
  close.textContent = "×"
  toast.append(close)
  toastContainer().append(toast)

  let dismissTimer = 0
  const dismiss = () => {
    window.clearTimeout(dismissTimer)
    toast.classList.remove("toast-visible")
    toast.classList.add("toast-leaving")
    window.setTimeout(() => toast.remove(), 200)
  }
  const scheduleDismiss = () => {
    window.clearTimeout(dismissTimer)
    dismissTimer = window.setTimeout(dismiss, actionHref ? 7000 : 4000)
  }

  close.addEventListener("click", dismiss)
  toast.addEventListener("mouseenter", () => window.clearTimeout(dismissTimer))
  toast.addEventListener("mouseleave", scheduleDismiss)
  window.requestAnimationFrame(() => toast.classList.add("toast-visible"))
  scheduleDismiss()
}

function mountToastSources(root: ParentNode) {
  if (root instanceof HTMLElement && root.matches("[data-toast]")) consumeToastSource(root)
  root.querySelectorAll<HTMLElement>("[data-toast]").forEach(consumeToastSource)
}

function formatLocalDateTimes(root: ParentNode) {
  const times: HTMLTimeElement[] = []
  if (root instanceof HTMLTimeElement && root.matches("[data-local-date-time]")) times.push(root)
  root.querySelectorAll<HTMLTimeElement>("time[data-local-date-time]").forEach((time) => times.push(time))
  times.forEach((time) => {
    if (time.dataset.localDateTimeFormatted === "true") return
    const raw = time.dateTime.trim()
    const date = new Date(raw)
    if (!raw || Number.isNaN(date.getTime())) return
    time.textContent = localDateTimeFormatter.format(date)
    time.title = raw
    time.dataset.localDateTimeFormatted = "true"
  })
}

function startToastObserver() {
  document.addEventListener("click", noteUserAction, true)
  document.addEventListener("change", noteUserAction, true)
  document.addEventListener("submit", noteUserAction, true)
  toastContainer()
  mountToastSources(document)
  formatLocalDateTimes(document)
  new MutationObserver((records) => {
    records.forEach((record) => {
      record.addedNodes.forEach((node) => {
        if (node instanceof HTMLElement) {
          mountToastSources(node)
          formatLocalDateTimes(node)
        }
      })
    })
  }).observe(document.body, { childList: true, subtree: true })
}

function startProfileControls() {
  const menuSelector = "details.visibility-menu, details.attribute-menu"
  let activeAttributeModalKey: string | null = null
  const pendingAttributeAdds = new Set<string>()

  const openAttributeModal = (dialog: HTMLDialogElement) => {
    activeAttributeModalKey = bmlStateKey(dialog)
    if (!dialog.open) dialog.showModal()
  }

  const closeRemoveAttributeDialog = (dialog: HTMLDialogElement) => {
    dialog.close()
    dialog.closest(".attribute-row")?.querySelector<HTMLDetailsElement>("details.attribute-menu")?.removeAttribute("open")
  }

  const restoreAttributeModal = (root: ParentNode) => {
    const dialog = root instanceof HTMLDialogElement && root.matches("[data-attribute-modal]")
      ? root
      : root.querySelector<HTMLDialogElement>("[data-attribute-modal]")
    if (!dialog) return
    const key = bmlStateKey(dialog)
    if (pendingAttributeAdds.delete(key) && dialog.dataset.hasSelectedAttributeType !== "true") {
      if (activeAttributeModalKey === key) activeAttributeModalKey = null
      return
    }
    if (activeAttributeModalKey === key && !dialog.open) dialog.showModal()
  }

  const controlIsDirty = (control: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement) => {
    if (control instanceof HTMLInputElement && control.type === "checkbox") {
      return control.checked !== control.defaultChecked
    }
    if (control instanceof HTMLSelectElement) {
      const initial = Array.from(control.options).find((option) => option.defaultSelected)?.value
        ?? control.options.item(0)?.value
        ?? ""
      return control.value !== initial
    }
    return control.value !== control.defaultValue
  }

  const controlsAreDirty = (root: ParentNode) =>
    Array.from(root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(
      "input:not([type='hidden']), textarea, select",
    )).some(controlIsDirty)

  const markAttributesDirty = (target: EventTarget | null) => {
    const control = target instanceof Element ? target : null
    const editor = control?.closest<HTMLElement>("[data-attribute-editor]")
    const form = editor?.closest<HTMLFormElement>("[data-profile-attributes-form]")
    if (!form || !editor) return
    const editorDirty = controlsAreDirty(editor)
    editor.toggleAttribute("data-dirty", editorDirty)
    editor.closest<HTMLElement>(".attribute-row")?.toggleAttribute("data-dirty", editorDirty)
    const formDirty = form.querySelector("[data-attribute-editor][data-dirty]") != null
    form.toggleAttribute("data-dirty", formDirty)
    const save = form.querySelector<HTMLButtonElement>("[data-attributes-save]")
    if (save) save.disabled = !formDirty
    form.querySelectorAll<HTMLButtonElement>("[data-remove-attribute]").forEach((remove) => {
      remove.disabled = formDirty
      if (formDirty) {
        remove.title = "Save or discard your changes before removing an attribute."
      } else {
        remove.removeAttribute("title")
      }
    })
    const add = form.closest(".section-card")?.querySelector<HTMLButtonElement>("[data-open-attribute-modal]")
    if (add) {
      add.disabled = formDirty
      add.title = formDirty
        ? "Save or discard your changes before adding an attribute."
        : "Add an attribute"
    }
  }

  const markProfileDetailsDirty = (target: EventTarget | null) => {
    const control = target instanceof Element ? target : null
    const form = control?.closest<HTMLFormElement>("[data-profile-details-form]")
    if (!form) return
    const dirty = controlsAreDirty(form)
    form.toggleAttribute("data-dirty", dirty)
    const save = form.querySelector<HTMLButtonElement>("[data-profile-save]")
    if (save) save.disabled = !dirty
  }

  const collectAttributes = (form: HTMLFormElement) => {
    return Array.from(form.querySelectorAll<HTMLElement>("[data-attribute-editor]")).map((editor) => {
      const valueControl = editor.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>("[name='value']")
      const visibilityControl = editor.querySelector<HTMLInputElement | HTMLSelectElement>("[name='visibility']")
      if (!valueControl || !visibilityControl) throw new Error("Attribute editor is incomplete")
      const value = valueControl instanceof HTMLInputElement && valueControl.type === "checkbox"
        ? String(valueControl.checked)
        : valueControl.value
      return {
        id: editor.dataset.attributeId ?? "",
        typeId: editor.dataset.attributeTypeId ?? "",
        value,
        visibility: visibilityControl.value,
      }
    })
  }

  document.addEventListener("input", (event) => {
    markAttributesDirty(event.target)
    markProfileDetailsDirty(event.target)
  })

  document.addEventListener("change", (event) => {
    markAttributesDirty(event.target)
    markProfileDetailsDirty(event.target)
    const select = event.target instanceof HTMLSelectElement ? event.target : null
    const visibilityMenu = select?.closest<HTMLDetailsElement>("details.visibility-menu")
    if (select && visibilityMenu) {
      const selectedLabel = select.selectedOptions.item(0)?.textContent?.trim()
      const label = visibilityMenu.querySelector<HTMLElement>("[data-visibility-label]")
      if (label && selectedLabel) label.textContent = selectedLabel
      const trigger = visibilityMenu.querySelector<HTMLElement>("summary")
      if (trigger && selectedLabel) trigger.title = `Visibility: ${selectedLabel}`
      visibilityMenu.removeAttribute("open")
    }
  })

  document.addEventListener("click", (event) => {
    const element = event.target instanceof Element ? event.target : null
    const signOutLink = element?.closest<HTMLAnchorElement>("[data-sign-out-before-navigation]")
    if (signOutLink) {
      event.preventDefault()
      if (signOutLink.dataset.signOutPending === "true") return
      signOutLink.dataset.signOutPending = "true"
      signOutLink.setAttribute("aria-disabled", "true")
      void (async () => {
        try {
          await auth.signOut()
        } catch (error) {
          console.warn("profiles: remote sign-out failed", error)
        } finally {
          auth.destroy()
          location.assign(signOutLink.href)
        }
      })()
      return
    }
    const open = element?.closest<HTMLElement>("[data-open-attribute-modal]")
    if (open) {
      const dialog = open.closest("[data-bml-island]")?.querySelector<HTMLDialogElement>("[data-attribute-modal]")
      if (dialog) openAttributeModal(dialog)
    }
    const close = element?.closest<HTMLElement>("[data-close-attribute-modal]")
    if (close) {
      const dialog = close.closest<HTMLDialogElement>("[data-attribute-modal]")
      activeAttributeModalKey = null
      dialog?.close()
    }
    if (element instanceof HTMLDialogElement && element.matches("[data-attribute-modal]")) {
      activeAttributeModalKey = null
      element.close()
    }

    const openRemove = element?.closest<HTMLButtonElement>("[data-open-remove-attribute-dialog]")
    if (openRemove) {
      const dialog = openRemove.closest(".attribute-row")?.querySelector<HTMLDialogElement>("[data-remove-attribute-dialog]")
      if (dialog && !dialog.open) dialog.showModal()
    }
    const closeRemove = element?.closest<HTMLElement>("[data-close-remove-attribute-dialog]")
    if (closeRemove) {
      const dialog = closeRemove.closest<HTMLDialogElement>("[data-remove-attribute-dialog]")
      if (dialog) closeRemoveAttributeDialog(dialog)
    }
    const confirmRemove = element?.closest<HTMLElement>("[data-confirm-remove-attribute]")
    if (confirmRemove) {
      const dialog = confirmRemove.closest<HTMLDialogElement>("[data-remove-attribute-dialog]")
      if (dialog) closeRemoveAttributeDialog(dialog)
    }
    if (element instanceof HTMLDialogElement && element.matches("[data-remove-attribute-dialog]")) {
      closeRemoveAttributeDialog(element)
    }

    const target = event.target instanceof Node ? event.target : null
    document.querySelectorAll<HTMLDetailsElement>(`${menuSelector}[open]`).forEach((menu) => {
      if (!target || !menu.contains(target)) menu.removeAttribute("open")
    })
  })

  document.addEventListener("submit", (event) => {
    const form = event.target instanceof HTMLFormElement ? event.target : null
    if (!form) return
    if (form.matches("[data-profile-attributes-form]")) {
      const payload = form.elements.namedItem("attributes")
      if (payload instanceof HTMLInputElement) payload.value = JSON.stringify(collectAttributes(form))
    }
    if (form.matches("[data-add-attribute-form]")) {
      const dialog = form.closest<HTMLDialogElement>("[data-attribute-modal]")
      if (dialog) pendingAttributeAdds.add(bmlStateKey(dialog))
    }
  }, true)

  document.addEventListener("cancel", (event) => {
    const dialog = event.target instanceof HTMLDialogElement ? event.target : null
    if (dialog?.matches("[data-attribute-modal]")) activeAttributeModalKey = null
    if (dialog?.matches("[data-remove-attribute-dialog]")) {
      event.preventDefault()
      closeRemoveAttributeDialog(dialog)
    }
  }, true)

  document.addEventListener("bml:update", (event) => {
    const root = event.target instanceof Element ? event.target : document
    restoreAttributeModal(root)
  })

  document.addEventListener("toggle", (event) => {
    const opened = event.target instanceof HTMLDetailsElement ? event.target : null
    if (!opened?.open || !opened.matches(menuSelector)) return
    document.querySelectorAll<HTMLDetailsElement>(`${menuSelector}[open]`).forEach((menu) => {
      if (menu !== opened) menu.removeAttribute("open")
    })
  }, true)
}

function startClient() {
  startToastObserver()
  startProfileControls()
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", startClient, { once: true })
} else {
  startClient()
}
