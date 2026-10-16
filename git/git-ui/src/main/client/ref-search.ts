import type { ClientContext } from "@bosca/bml";
import type { SelectSearch } from "./select";

/** Supplies bounded tag pages through the generated BML server contract. */
export function mountRefSearch(
  ctx: ClientContext,
  search: (id: string, query: string, offset: number, branchesOnly: boolean) => Promise<Record<string, string>[]>,
) {
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", (event) => {
    event.stopPropagation();
    const { query, offset, resolve, reject } = event.detail;
    search(ctx.root.dataset.repository || "", query, offset, ctx.root.hasAttribute("data-branches-only")).then((refs) => {
      const branches = refs.filter((ref) => ref.kind === "branch");
      const tags = refs.filter((ref) => ref.kind === "tag");
      resolve({
        options: [...branches, ...tags.slice(0, 25)].map((ref) => ({
          value: ctx.root.dataset.mode === "qualified"
            ? `refs/${ref.kind === "tag" ? "tags" : "heads"}/${ref.name}`
            : ctx.root.dataset.mode === "search" && ref.kind === "tag" ? `refs/tags/${ref.name}` : ref.name,
          label: ref.name + (ref.kind === "tag" ? " (tag)" : ""),
        })),
        previousOffset: offset > 0 ? Math.max(0, offset - 25) : undefined,
        nextOffset: tags.length > 25 ? offset + 25 : undefined,
      });
    }, reject);
  });
}
