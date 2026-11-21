import type { ClientContext } from "@bosca/bml";
import type { SelectPage, SelectSearch } from "@bosca/bml-design/select";
import type { SchemaFormChoicesRequest } from "@bosca/bml-design/schema-form";

/** Applications supply transport; this field owns cancellation, initial labels and retry feedback. */
export function schemaFormChoices(ctx: ClientContext) {
  if (ctx.root.dataset.schemaControl !== "remote-select") return;
  const select = ctx.root.querySelector<HTMLElement>("[data-bml-design-select]");
  const status = ctx.root.querySelector<HTMLElement>("[data-schema-choices-status]");
  const retry = ctx.root.querySelector<HTMLButtonElement>("[data-schema-choices-retry]");
  const form = ctx.root.closest<HTMLElement>("[data-bml-schema-form]");
  if (!select || !status || !retry || !form) return;
  let pending: AbortController | undefined;
  let disposed = false, loaded = false;
  let lastQuery: string | null = null, lastOffset = 0;
  const request = async (query: string | null, offset: number): Promise<SelectPage> => {
    lastQuery = query; lastOffset = offset;
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    status.textContent = "Loading choices…";
    status.hidden = false;
    retry.hidden = true;
    try {
      let result: SelectPage | Promise<SelectPage> | undefined;
      ctx.root.dispatchEvent(new CustomEvent<SchemaFormChoicesRequest>("schemaformchoicesrequest", {
        bubbles: true, detail: {
          fieldKey: ctx.root.dataset.schemaFieldKey ?? "", property: ctx.root.dataset.schemaProperty ?? "",
          query, offset, signal: controller.signal,
          respondWith: value => { result = value; },
        },
      }));
      if (!result) throw new Error("Choices are unavailable. Try again.");
      const page = await result;
      controller.signal.throwIfAborted();
      if (disposed) throw new DOMException("Field closed", "AbortError");
      loaded = true;
      status.textContent = "";
      status.hidden = true;
      select.dispatchEvent(new CustomEvent("selectoptions", { detail: page.options }));
      return page;
    } catch (error) {
      if (!disposed && !controller.signal.aborted) {
        status.textContent = error instanceof Error ? error.message : "Could not load choices.";
        status.hidden = false;
        retry.hidden = false;
      }
      throw error;
    }
  };
  const initial = () => {
    if (!loaded && !disposed && select.hasAttribute("data-select-ready")) void request(null, 0).catch(() => {});
  };
  ctx.on<CustomEvent<SelectSearch>>(select, "selectsearch", event => {
    event.stopPropagation();
    void request(event.detail.query, event.detail.offset).then(event.detail.resolve, event.detail.reject);
  });
  ctx.on(select, "selectready", initial);
  ctx.on(form, "schemaformchoicesready", initial);
  ctx.on(retry, "click", () => { void request(lastQuery, lastOffset).catch(() => {}); });
  ctx.onUnmount(() => { disposed = true; pending?.abort(); });
  initial();
}
