declare module "@bosca/bml-design/images" {
  export interface ImageChoice { id: string; label: string; src: string; contentType: string }
  export interface ImagePage { items: readonly ImageChoice[]; previousOffset?: number; nextOffset?: number }
  export interface ImageSelection { id?: string; src: string; alt: string; title: string }
  export interface ImagePickerOpen {
    initial?: ImageSelection;
    select(value: ImageSelection): void;
    cancel(): void;
  }
  /** Applications supply authorized lookup and upload; the library owns picker presentation. */
  export type ImageRequest = (
    | { kind: "search"; query: string; offset: number; respondWith(value: ImagePage | Promise<ImagePage>): void }
    | { kind: "resolve"; id: string; respondWith(value: ImageChoice | null | Promise<ImageChoice | null>): void }
    | { kind: "upload"; file: File; progress(value: number, phase: "uploading" | "processing"): void;
        respondWith(value: ImageChoice | Promise<ImageChoice>): void }
  ) & { signal: AbortSignal };
}
