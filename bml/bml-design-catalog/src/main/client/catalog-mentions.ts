import type { ClientContext } from "@bosca/bml";
import type { MentionChoice, MentionSearch } from "@bosca/bml-design/mentions";

const profiles: MentionChoice[] = [
  { id: "example-jamie", label: "Jamie Nguyen", handle: "jamie", entityType: "profile" },
  { id: "example-avery", label: "Avery Morgan", handle: "avery", entityType: "profile" },
];
const entities: MentionChoice[] = [
  { id: "example-document", label: "Launch notes", entityType: "metadata" },
  { id: "example-collection", label: "Launch collection", entityType: "collection" },
  ...profiles,
];

/** The catalog supplies illustrative data through the same contract as a product. */
export function catalogMentions(ctx: ClientContext) {
  ctx.on<CustomEvent<MentionSearch>>(ctx.root, "mentionsearch", event => {
    event.stopPropagation();
    const { query, kind, respondWith } = event.detail;
    respondWith(query === "error" ? Promise.reject(new Error("Example lookup failure")) : query === "empty" ? []
      : (kind === "profile" ? profiles : entities).filter(choice => `${choice.label} ${choice.handle ?? ""}`.toLowerCase().includes(query.toLowerCase())));
  });
}
