declare module "@bosca/bml-design/document-references" {
  export interface ReferenceChoice { id: string; label: string; description?: string }
  export interface MetadataReference extends ReferenceChoice { contentType?: string }
  export interface ScripturePassage {
    label: string;
    components: unknown;
    styles: unknown;
    attribution?: { copyright: string; citation: string; informationUrl?: string };
  }
  /** Consumers answer synchronously with a value or promise; abort means the result is obsolete. */
  export type DocumentReferenceRequest = (
    | { kind: "metadata-search"; query: string; filters: readonly string[]; respondWith(value: readonly ReferenceChoice[] | Promise<readonly ReferenceChoice[]>): void }
    | { kind: "metadata"; id: string; version: number | null; respondWith(value: MetadataReference | null | Promise<MetadataReference | null>): void }
    | { kind: "bibles"; offset: number; respondWith(value: readonly ReferenceChoice[] | Promise<readonly ReferenceChoice[]>): void }
    | { kind: "scripture-search"; id: string; query: string; respondWith(value: readonly ReferenceChoice[] | Promise<readonly ReferenceChoice[]>): void }
    | { kind: "scripture"; id: string; reference: string; respondWith(value: readonly ScripturePassage[] | Promise<readonly ScripturePassage[]>): void }
  ) & { signal: AbortSignal };
}
