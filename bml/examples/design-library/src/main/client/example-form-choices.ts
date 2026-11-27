import type { ClientContext } from "@bosca/bml";
import type { SchemaFormChoicesRequest } from "@bosca/bml-design/schema-form";

/** Consumer-owned sample transport; the shared form imports no application data. */
export function exampleFormChoices(ctx: ClientContext) {
  ctx.on<CustomEvent<SchemaFormChoicesRequest>>(ctx.root, "schemaformchoicesrequest", event => {
    event.stopPropagation();
    const { query, offset, signal } = event.detail;
    event.detail.respondWith(Promise.resolve().then(() => {
      signal.throwIfAborted();
      if (query === "fail") throw new Error("Example lookup failed. Change the search or retry.");
      const options = Array.from({ length: 31 }, (_, index) => ({ value: `choice-${index + 1}`, label: `Example choice ${index + 1}` }))
        .filter(option => !query || option.label.toLowerCase().includes(query.toLowerCase()));
      return { options: options.slice(offset, offset + 25), previousOffset: offset > 0 ? Math.max(0, offset - 25) : undefined, nextOffset: options.length > offset + 25 ? offset + 25 : undefined };
    }));
  });
  const ready = () => { for (const form of ctx.root.querySelectorAll("[data-bml-schema-form]")) form.dispatchEvent(new Event("schemaformchoicesready")); };
  ctx.onUpdate(ready); ready();
}
