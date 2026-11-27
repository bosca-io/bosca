declare module "@bosca/bml-design/mentions" {
  /** Stable identities and visible labels supplied by the consuming application. */
  export interface MentionChoice {
    id: string;
    label: string;
    handle?: string;
    entityType?: string;
  }

  /** Call respondWith synchronously; results may resolve asynchronously. */
  export interface MentionSearch {
    query: string;
    kind: "profile" | "entity";
    signal: AbortSignal;
    respondWith(results: readonly MentionChoice[] | Promise<readonly MentionChoice[]>): void;
  }
}
