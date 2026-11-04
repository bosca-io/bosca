import type { ClientContext } from "@bosca/bml";

/** Serializes repeated native model controls into the live action's single JSON transport field. */
export function mountStorageModelFields(ctx: ClientContext) {
  const serialize = (form: HTMLFormElement) => {
    const transport = form.elements.namedItem("modelValues");
    if (!(transport instanceof HTMLInputElement)) return;
    transport.value = JSON.stringify(Array.from(form.querySelectorAll<HTMLElement>("[data-storage-model-row]"), row => ({
      key: row.dataset.storageModelRow,
      modelId: row.querySelector<HTMLSelectElement>("select")?.value ?? "",
      configuration: row.querySelector<HTMLTextAreaElement>("textarea")?.value ?? "{}",
    })));
  };
  ctx.on<Event>(ctx.root, "bml:form-serialize", event => {
    if (event.target instanceof HTMLFormElement && event.target.matches("[data-storage-editor]")) serialize(event.target);
  }, true);
}
