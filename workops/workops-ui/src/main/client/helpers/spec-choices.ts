import type { ClientContext } from "@bosca/bml";
import type { SelectSearch, SelectOption } from "@bosca/bml-design/select";

/** WorkOps resolves Content titles to authorized specifications; the library owns selection and paging. */
export function workOpsSpecChoices(ctx: ClientContext, search: (query: string, offset: number) => Promise<Record<string, string>>) {
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", event => {
    if (!(event.target instanceof Element) || !event.target.closest("[data-workops-spec-picker]")) return;
    event.stopPropagation();
    const { query, offset, resolve, reject } = event.detail;
    search(query, offset).then(row => {
      const options: SelectOption[] = JSON.parse(row.options || "[]");
      resolve({ options, previousOffset: row.previousOffset === undefined ? undefined : Number(row.previousOffset),
        nextOffset: row.nextOffset === undefined ? undefined : Number(row.nextOffset) });
    }).catch(error => reject(new Error("Could not load specifications. Try again.", { cause: error })));
  });
}
