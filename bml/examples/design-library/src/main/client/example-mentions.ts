import type { ClientContext } from "@bosca/bml";
import type { MentionSearch } from "@bosca/bml-design/mentions";

/** This independent consumer supplies its own choices without importing catalog code. */
export function exampleMentions(ctx: ClientContext) {
  ctx.on<CustomEvent<MentionSearch>>(ctx.root, "mentionsearch", event => {
    event.stopPropagation();
    const { query, kind } = event.detail;
    const choices = kind === "profile"
      ? [{ id: "reviewer", label: "Riley", handle: "riley", entityType: "profile" }]
      : [{ id: "review-notes", label: "Review notes", entityType: "metadata" },
        { id: "review-files", label: "Review files", entityType: "collection" }];
    event.detail.respondWith(choices.filter(choice => choice.label.toLowerCase().includes(query.toLowerCase())));
  });
}
