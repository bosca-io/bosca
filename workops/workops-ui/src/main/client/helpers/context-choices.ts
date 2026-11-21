import type { ClientContext } from "@bosca/bml";
import type { SelectSearch } from "@bosca/bml-design/select";

/** Domain reference choices use generated server contracts and the library's async select lifecycle. */
export function workOpsContextChoices(ctx: ClientContext, search: (kind: string, query: string, offset: number) => Promise<Record<string, string>>) {
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", event => {
    if (!(event.target instanceof Element)) return;
    const picker = event.target.closest<HTMLElement>("[data-workops-context-picker]");
    if (!picker) return;
    event.stopPropagation();
    const { query, offset, resolve, reject } = event.detail;
    search(picker.dataset.contextKind || "", query, offset).then(row => resolve({
      options: JSON.parse(row.options || "[]"),
      previousOffset: row.previousOffset === undefined ? undefined : Number(row.previousOffset),
      nextOffset: row.nextOffset === undefined ? undefined : Number(row.nextOffset),
    })).catch(error => reject(new Error("Could not load references. Try again.", { cause: error })));
  });
}
