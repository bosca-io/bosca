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

export const controlsAreDirty = (root: ParentNode) =>
  Array.from(root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(
    "input:not([type='hidden']), textarea, select",
  )).some(controlIsDirty)
