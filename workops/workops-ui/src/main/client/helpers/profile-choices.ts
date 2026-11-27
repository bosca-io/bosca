import type { ClientContext } from "@bosca/bml";
import type { SelectSearch } from "@bosca/bml-design/select";

/** WorkOps supplies profile choices; the shared select owns search, paging and keyboard behavior. */
export function workOpsProfileChoices(ctx: ClientContext, search: (query: string, offset: number) => Promise<Record<string, string>[]>) {
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", event => {
    if (!(event.target instanceof Element) || !event.target.closest("[data-workops-profile-picker]")) return;
    event.stopPropagation();
    const { query, offset, resolve, reject } = event.detail;
    search(query, offset).then(rows => resolve({
      options: rows.slice(0, 20).map(row => ({ value: row.id, label: row.label })),
      previousOffset: offset > 0 ? Math.max(0, offset - 20) : undefined,
      nextOffset: rows.length > 20 ? offset + 20 : undefined,
    }), reject);
  });
}
