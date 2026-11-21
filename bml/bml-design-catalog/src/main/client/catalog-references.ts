import type { ClientContext } from "@bosca/bml";
import type { DocumentReferenceRequest, ReferenceChoice } from "@bosca/bml-design/document-references";

/** Illustrative catalog records; products supply their own authorized adapters. */
export function catalogReferences(ctx: ClientContext) {
  ctx.on<CustomEvent<DocumentReferenceRequest>>(ctx.root, "documentreferencerequest", event => {
    event.stopPropagation(); const request = event.detail;
    const matches = (values: ReferenceChoice[], query: string) => query === "error" ? Promise.reject(new Error("Illustrative lookup failure"))
      : values.filter(value => value.label.toLowerCase().includes(query.toLowerCase()));
    switch (request.kind) {
      case "metadata-search": request.respondWith(matches([{ id: "example-article", label: "Example article" }, { id: "example-notes", label: "Example notes" }], request.query)); break;
      case "metadata": request.respondWith(request.id === "private" ? null : { id: request.id, label: request.id === "example-notes" ? "Example notes" : "Example article", contentType: "text/plain" }); break;
      case "bibles": request.respondWith(request.offset ? [] : [{ id: "example-bible", label: "Illustrative translation" }]); break;
      case "scripture-search": request.respondWith(matches([{ id: "GEN.1.1", label: "Genesis 1:1" }, { id: "GEN.1.2", label: "Genesis 1:2" }], request.query)); break;
      case "scripture": request.respondWith([{
        label: `Illustrative translation — ${request.reference === "GEN.1.1" ? "Genesis 1:1" : "Genesis 1:2"}`,
        components: { _: "cc", type: "PARAGRAPH", components: [
          { _: "vs", reference: { usfm: request.reference } },
          { _: "cc", type: "DIV", style: { _: "sr", id: "it" }, components: [{ _: "t", text: "Illustrative passage text." }] },
        ] }, styles: [{ id: "it", fontWeight: "normal" }],
        attribution: { copyright: "Illustrative copyright", citation: "Catalog fixture", informationUrl: "https://example.com" },
      }]); break;
    }
  });
  for (const editor of ctx.root.querySelectorAll<HTMLElement>("[data-reference-controls]")) editor.dispatchEvent(new Event("documentreferencesready"));
}
