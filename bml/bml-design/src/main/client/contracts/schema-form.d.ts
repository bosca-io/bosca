declare module "@bosca/bml-design/schema-form" {
  import type { SelectPage } from "@bosca/bml-design/select";

  /** Application-owned choices requested by a schema-form remote-select field. */
  export interface SchemaFormChoicesRequest {
    fieldKey: string;
    property: string;
    /** Null loads initial labels; a string requests a search, including the empty query. */
    query: string | null;
    offset: number;
    signal: AbortSignal;
    respondWith: (result: SelectPage | Promise<SelectPage>) => void;
  }
}
