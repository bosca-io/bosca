import type { ClientContext } from "@bosca/bml";
import type { DocumentReferenceRequest } from "@bosca/bml-design/document-references";

type ReferenceContract = {
  searchMetadata(query: string, filters: string[]): Promise<Record<string, string>[]>;
  metadata(id: string, version: number | null): Promise<Record<string, string> | null>;
  bibles(offset: number): Promise<Record<string, string>[]>;
  findScripture(id: string, query: string): Promise<Record<string, string>[]>;
  scripture(id: string, reference: string): Promise<Record<string, string>[]>;
};

/** Caller-bound generated contracts adapt Content data to the shared editor's public events. */
export function workOpsDocumentReferences(ctx: ClientContext, lookup: ReferenceContract) {
  const choice = (row: Record<string, string>) => ({ id: row.id, label: row.label, description: row.contentType });
  ctx.on<CustomEvent<DocumentReferenceRequest>>(ctx.root, "documentreferencerequest", event => {
    event.stopPropagation(); const request = event.detail;
    switch (request.kind) {
      case "metadata-search": request.respondWith(lookup.searchMetadata(request.query, [...request.filters]).then(rows => rows.map(choice))); break;
      case "metadata": request.respondWith(lookup.metadata(request.id, request.version).then(row => row ? { ...choice(row), contentType: row.contentType } : null)); break;
      case "bibles": request.respondWith(lookup.bibles(request.offset).then(rows => rows.map(choice))); break;
      case "scripture-search": request.respondWith(lookup.findScripture(request.id, request.query).then(rows => rows.map(choice))); break;
      case "scripture": request.respondWith(lookup.scripture(request.id, request.reference).then(rows => rows.map(row => ({
        label: row.label, components: JSON.parse(row.components), styles: JSON.parse(row.styles),
        attribution: row.copyright || row.citation || row.informationUrl ? {
          copyright: row.copyright, citation: row.citation, informationUrl: row.informationUrl,
        } : undefined,
      })))); break;
    }
  });
  for (const editor of ctx.root.querySelectorAll<HTMLElement>("[data-reference-controls]")) editor.dispatchEvent(new Event("documentreferencesready"));
}
