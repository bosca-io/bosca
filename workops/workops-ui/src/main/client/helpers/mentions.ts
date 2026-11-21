import type { ClientContext } from "@bosca/bml";
import type { MentionSearch } from "@bosca/bml-design/mentions";

/** Resolve shared mention requests through generated same-origin WorkOps server contracts. */
export function workOpsMentions(ctx: ClientContext, search: {
  profiles(query: string): Promise<Record<string, string>[]>;
  entities(query: string): Promise<Record<string, string>[]>;
}) {
  ctx.on<CustomEvent<MentionSearch>>(ctx.root, "mentionsearch", event => {
    event.stopPropagation();
    const { kind, query, respondWith } = event.detail;
    respondWith((kind === "profile" ? search.profiles(query) : search.entities(query))
      .then(rows => rows.map(row => ({ id: row.id, label: row.label, handle: row.handle, entityType: row.entityType }))));
  });
}
