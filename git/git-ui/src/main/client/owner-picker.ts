import type { ClientContext } from "@bosca/bml";
import type { SelectSearch } from "./select";

/** Supplies Git profile choices to the reusable select through the generated server contract. */
export function mountOwnerPicker(
  ctx: ClientContext,
  search: (query: string) => Promise<Record<string, string>[]>,
) {
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", (event) => {
    event.stopPropagation();
    search(event.detail.query).then(
      (owners) =>
        event.detail.resolve(
          owners.map((owner) => ({ value: owner.id, label: owner.label })),
        ),
      event.detail.reject,
    );
  });
  ctx.on(ctx.root, "change", (event) => {
    if (!(event.target instanceof HTMLSelectElement)) return;
    ctx.root.dispatchEvent(
      new CustomEvent("ownerselected", {
        bubbles: true,
        detail: event.target.value,
      }),
    );
  });
}
