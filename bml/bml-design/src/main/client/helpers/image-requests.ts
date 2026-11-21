import type { ImageChoice, ImageRequest } from "@bosca/bml-design/images";

type ImageQuery = { [K in ImageRequest["kind"]]: Omit<Extract<ImageRequest, { kind: K }>, "respondWith" | "signal"> }[ImageRequest["kind"]];

/** Shared image consumers ask their owning application for caller-bound data. */
export function imageRequest<T>(root: HTMLElement, query: ImageQuery, signal: AbortSignal): Promise<T> {
  let response: T | Promise<T> | undefined, answered = false;
  const detail = { ...query, signal, respondWith(value: T | Promise<T>) {
    if (answered) throw new Error("Image request already has a response.");
    answered = true; response = value;
  } } as ImageRequest;
  root.dispatchEvent(new CustomEvent<ImageRequest>("imagerequest", { bubbles: true, detail }));
  return answered ? Promise.resolve(response!) : Promise.reject(new Error("Image lookup is unavailable."));
}

export function imageChoice(value: ImageChoice): ImageChoice {
  if (!value || typeof value.id !== "string" || !value.id || typeof value.label !== "string" ||
      typeof value.src !== "string" || !value.src || typeof value.contentType !== "string" || !value.contentType.startsWith("image/")) {
    throw new Error("Invalid image result.");
  }
  return value;
}

export function imageURL(value: string): boolean {
  try { return Boolean(value.trim()) && ["http:", "https:"].includes(new URL(value, location.href).protocol); }
  catch { return false; }
}
