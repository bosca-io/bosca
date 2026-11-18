import type { ClientContext } from "@bosca/bml"
import { controlsAreDirty } from "./form-dirty"

/** Keeps dirty state within this profile's details form. */
export function profileDetails(ctx: ClientContext) {
  const sync = () => {
    const form = ctx.root.querySelector<HTMLFormElement>("[data-profile-details-form]")
    if (!form) return
    const dirty = controlsAreDirty(form)
    form.toggleAttribute("data-dirty", dirty)
    const save = form.querySelector<HTMLButtonElement>("[data-profile-save]")
    if (save) save.disabled = !dirty
  }
  ctx.on(ctx.root, "input", sync)
  ctx.on(ctx.root, "change", sync)
  ctx.onUpdate(sync)
  sync()
}
