import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { formControlValue, formErrors, formValidator, formValue, setFormValue, type FormDataValue } from "./schema-form-data";
import "./schema-form.css";

/** Owns generic form values and validation; application islands own lookups and submission. */
export function mountSchemaForm(ctx: ClientContext) {
  const hidden = ctx.ref<HTMLInputElement>("value"), feedback = ctx.ref<HTMLParagraphElement>("feedback");
  if (!hidden || !feedback) return;
  feedback.tabIndex = -1;
  let value = JSON.parse(ctx.root.dataset.value ?? "{}") as FormDataValue;
  let serverValue = ctx.root.dataset.value;
  let form: HTMLFormElement | null = null;
  let disposeSubmit: (() => void) | undefined;
  let schemaError = "";
  let validate: ReturnType<typeof formValidator> | undefined;
  const fields = () => [...ctx.root.querySelectorAll<HTMLElement>("[data-schema-property]")];
  const controls = (field: HTMLElement): Array<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement> => {
    const fileValue = field.querySelector<HTMLInputElement>("[data-file-field-value]");
    if (fileValue) return [fileValue];
    const jsonValue = field.querySelector<HTMLTextAreaElement>('[data-bml-json-editor] [data-bml-ref="value"]');
    if (jsonValue) return [jsonValue];
    const selection = field.querySelector<HTMLSelectElement>("[data-bml-design-select] > select");
    if (selection) return [selection];
    return [...field.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>("input,textarea,select")]
      .filter(control => control.closest("[data-schema-property]") === field);
  };
  try { validate = formValidator(JSON.parse(ctx.root.dataset.schema ?? "{}") as FormDataValue); }
  catch { schemaError = "Form validation is unavailable because the schema is invalid."; }
  const showErrors = (errors: Record<string, string>) => {
    for (const field of fields()) {
      const error = errors[field.dataset.schemaProperty ?? ""] ?? "";
      const message = field.querySelector<HTMLElement>("[data-schema-error]");
      if (message) { message.textContent = error; message.hidden = !error; }
      for (const control of controls(field)) {
        control.setAttribute("aria-invalid", String(Boolean(error)));
        if (message?.id) control.setAttribute("aria-describedby", message.id);
        control.setCustomValidity(error);
      }
    }
    feedback.textContent = schemaError || errors[""] || (Object.keys(errors).length ? "Please correct the marked fields." : "");
    feedback.hidden = !feedback.textContent;
  };
  const check = () => {
    const errors = validate ? formErrors(validate, value) : { "": schemaError };
    for (const unsupported of ctx.root.querySelectorAll<HTMLElement>("[data-schema-unsupported]")) {
      errors[""] = unsupported.textContent?.trim() || "This form uses an unavailable control.";
    }
    if (ctx.root.querySelector("[data-file-field-busy]")) errors[""] = "Wait for the file to finish reading or cancel it before saving.";
    showErrors(errors);
    return Object.keys(errors).length === 0 && !schemaError;
  };
  const activateTab = (group: string, key: string) => {
    for (const panel of ctx.root.querySelectorAll<HTMLElement>("[data-schema-tab-group]")) {
      if (panel.dataset.schemaTabGroup === group) panel.hidden = panel.dataset.schemaTab !== key;
    }
  };
  const focusError = () => {
    const control = ctx.root.querySelector<HTMLElement>('[aria-invalid="true"]');
    const panels: HTMLElement[] = [];
    let parent = control?.parentElement;
    while (parent && parent !== ctx.root) {
      if (parent.hasAttribute("data-schema-tab")) panels.unshift(parent);
      parent = parent.parentElement;
    }
    for (const panel of panels) {
      const group = panel.dataset.schemaTabGroup ?? "", key = panel.dataset.schemaTab ?? "";
      activateTab(group, key);
      for (const tabs of ctx.root.querySelectorAll<HTMLElement>("[data-schema-tabs]")) {
        if (tabs.dataset.schemaTabs !== group) continue;
        for (const tab of tabs.querySelectorAll<HTMLButtonElement>("[role=tab]")) {
          const selected = tab.dataset.tab === key;
          tab.setAttribute("aria-selected", String(selected)); tab.tabIndex = selected ? 0 : -1;
        }
      }
    }
    const field = control?.closest<HTMLElement>("[data-schema-property]");
    const widget = field?.querySelector<HTMLElement>('[data-bml-design-select] [data-bml-ref="trigger"], [data-bml-json-editor] [contenteditable="true"], [data-file-field] input[type="file"]');
    (widget ?? control ?? feedback).focus();
  };
  const publish = (event: Event) => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement)) return;
    const field = target.closest<HTMLElement>("[data-schema-property]");
    if (!field || !ctx.root.contains(field) || ctx.root.hasAttribute("data-readonly")) return;
    if (!controls(field).includes(target)) return;
    const property = field.dataset.schemaProperty ?? "";
    const schema = JSON.parse(field.dataset.propertySchema ?? "{}") as FormDataValue;
    let raw: unknown;
    if (field.dataset.schemaControl === "json-editor" || target.hasAttribute("data-file-field-value")) {
      try { raw = JSON.parse(target.value); }
      catch { return; } // The shared JSON editor reports and blocks malformed JSON itself.
    } else {
      if (target instanceof HTMLInputElement && target.type === "radio" && !target.checked) return;
      raw = formControlValue(target instanceof HTMLSelectElement && target.multiple
        ? [...target.selectedOptions].map(option => option.value)
        : target instanceof HTMLInputElement && target.type === "checkbox" ? target.checked : target.value, schema);
    }
    setFormValue(value, property, raw);
    hidden.value = JSON.stringify(value);
    hidden.dispatchEvent(new Event("input", { bubbles: true }));
    showErrors(validate ? formErrors(validate, value) : { "": schemaError });
    ctx.root.dispatchEvent(new CustomEvent("schemaformchange", { bubbles: true, detail: { property, value: raw, data: value } }));
  };
  ctx.on(ctx.root, "input", publish);
  ctx.on(ctx.root, "change", publish);
  ctx.on(ctx.root, "invalid", () => { check(); focusError(); }, true);
  ctx.on<CustomEvent<string>>(ctx.root, "viewchange", event => {
    const tabs = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>("[data-schema-tabs]") : null;
    if (tabs) activateTab(tabs.dataset.schemaTabs ?? "", event.detail);
  });
  const observer = new MutationObserver(() => { ctx.root.inert = Boolean(form?.hasAttribute("data-bml-action-pending")); });
  const sync = () => {
    if (hidden.form !== form) {
      disposeSubmit?.(); observer.disconnect(); form = hidden.form;
      if (form) {
        const submit = (event: Event) => {
          if (!check()) { event.preventDefault(); event.stopImmediatePropagation(); focusError(); }
        };
        form.addEventListener("submit", submit, true);
        disposeSubmit = () => form?.removeEventListener("submit", submit, true);
        observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
      }
    }
    const next = ctx.root.dataset.value;
    if (next !== serverValue) {
      value = JSON.parse(next ?? "{}") as FormDataValue; serverValue = next;
      for (const field of fields()) {
        const current = formValue(value, field.dataset.schemaProperty ?? "");
        for (const control of controls(field)) {
          if (control instanceof HTMLInputElement && control.type === "checkbox") control.checked = current === true;
          else if (control instanceof HTMLInputElement && control.type === "radio") control.checked = String(current) === control.value;
          else if (control instanceof HTMLSelectElement && control.multiple) for (const option of control.options) option.selected = Array.isArray(current) && current.includes(option.value);
          else control.value = field.dataset.schemaControl === "json-editor" || control.hasAttribute("data-file-field-value") ? JSON.stringify(current ?? null) : String(current ?? "");
        }
        const json = field.querySelector<HTMLElement>("[data-bml-json-editor]");
        if (json) json.dataset.value = JSON.stringify(current ?? null);
        const file = field.querySelector<HTMLElement>("[data-file-field]");
        if (file) file.dataset.value = JSON.stringify(current ?? null);
      }
    }
    hidden.value = JSON.stringify(value);
    ctx.root.inert = Boolean(form?.hasAttribute("data-bml-action-pending"));
    if (schemaError) showErrors({ "": schemaError });
  };
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.on(ctx.root, "filefieldbusy", () => { if (!feedback.hidden) check(); });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => { disposeSubmit?.(); observer.disconnect(); });
  sync();
}
