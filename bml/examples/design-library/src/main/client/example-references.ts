import type { ClientContext } from "@bosca/bml";
import type { DocumentReferenceRequest } from "@bosca/bml-design/document-references";

/** This independent consumer answers the public reference contract with illustrative records. */
export function exampleReferences(ctx: ClientContext) {
  ctx.on<CustomEvent<DocumentReferenceRequest>>(ctx.root, "documentreferencerequest", event => {
    event.stopPropagation(); const request = event.detail;
    switch (request.kind) {
      case "metadata-search": request.respondWith([{ id: "00000000-0000-4000-8000-000000000004", label: "Consumer resource" }]); break;
      case "metadata": request.respondWith({ id: request.id, label: "Consumer resource", contentType: "text/plain" }); break;
      case "bibles": request.respondWith(request.offset ? [] : [{ id: "00000000-0000-4000-8000-000000000005", label: "Consumer example Bible" }]); break;
      case "scripture-search": request.respondWith([{ id: "GEN.1.2", label: "Genesis 1:2" }]); break;
      case "scripture": request.respondWith([{
        label: request.reference === "GEN.1.1" ? "Genesis 1:1" : "Genesis 1:2",
        components: { _: "cc", type: "PARAGRAPH", components: [
          { _: "vs", reference: { usfm: request.reference } }, { _: "t", text: "Consumer example passage.", style: { _: "sr", id: "it" } },
        ] }, styles: [{ id: "it", fontWeight: "normal" }],
        attribution: { copyright: "Consumer example copyright", citation: "Independent consumer fixture" },
      }]); break;
    }
  });
  for (const editor of ctx.root.querySelectorAll<HTMLElement>("[data-reference-controls]")) editor.dispatchEvent(new Event("documentreferencesready"));
}
