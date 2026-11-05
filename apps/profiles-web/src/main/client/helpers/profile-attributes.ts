import type { ClientContext } from "@bosca/bml"
import { controlsAreDirty } from "./form-dirty"

/** Owns the attribute form and its add/remove dialogs for one profile instance. */
export function profileAttributes(ctx: ClientContext) {
  let attributeModalOpen = false
  let pendingAttributeAdd = false

  const openAttributeModal = (dialog: HTMLDialogElement) => {
    attributeModalOpen = true
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
    const added = pendingAttributeAdd
    pendingAttributeAdd = false
    if (added && !dialog.hasAttribute("data-has-selected-attribute-type")) {
      attributeModalOpen = false
      return
    }
    if (attributeModalOpen && !dialog.open) dialog.showModal()
  }

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

  ctx.on(ctx.root, "input", event => markAttributesDirty(event.target))
  ctx.on(ctx.root, "change", event => markAttributesDirty(event.target))
  ctx.on(ctx.root, "click", event => {
    const element = event.target instanceof Element ? event.target : null
    const open = element?.closest<HTMLElement>("[data-open-attribute-modal]")
    if (open) {
      const dialog = ctx.root.querySelector<HTMLDialogElement>("[data-attribute-modal]")
      if (dialog) openAttributeModal(dialog)
    }
    const close = element?.closest<HTMLElement>("[data-close-attribute-modal]")
    if (close) {
      const dialog = close.closest<HTMLDialogElement>("[data-attribute-modal]")
      attributeModalOpen = false
      dialog?.close()
    }
    if (element instanceof HTMLDialogElement && element.matches("[data-attribute-modal]")) {
      attributeModalOpen = false
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

  })
  ctx.on(ctx.root, "submit", event => {
    const form = event.target instanceof HTMLFormElement ? event.target : null
    if (!form) return
    if (form.matches("[data-profile-attributes-form]")) {
      const payload = form.elements.namedItem("attributes")
      if (payload instanceof HTMLInputElement) payload.value = JSON.stringify(collectAttributes(form))
    }
    if (form.matches("[data-add-attribute-form]")) {
      const dialog = form.closest<HTMLDialogElement>("[data-attribute-modal]")
      if (dialog) pendingAttributeAdd = true
    }
  }, true)
  ctx.on(ctx.root, "cancel", event => {
    const dialog = event.target instanceof HTMLDialogElement ? event.target : null
    if (dialog?.matches("[data-attribute-modal]")) attributeModalOpen = false
    if (dialog?.matches("[data-remove-attribute-dialog]")) {
      event.preventDefault()
      closeRemoveAttributeDialog(dialog)
    }
  }, true)
  ctx.onUpdate(() => restoreAttributeModal(ctx.root))
  ctx.onUnmount(() => {
    ctx.root.querySelectorAll<HTMLDialogElement>("dialog[open]").forEach(dialog => dialog.close())
  })
}
