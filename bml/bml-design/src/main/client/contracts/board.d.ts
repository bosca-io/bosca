declare module "@bosca/bml-design/board" {
  /** Application-owned save request emitted by a shared board card. */
  export interface BoardMoveRequest {
    cardId: string;
    columnId: string;
    /** Register the application-owned save before the event listener returns. */
    waitUntil(promise: Promise<unknown>): void;
  }
}
