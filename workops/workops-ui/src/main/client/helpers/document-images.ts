import type { ClientContext } from "@bosca/bml";
import type { ImageChoice, ImagePage, ImageRequest } from "@bosca/bml-design/images";

type ImageContract = {
  search(query: string, offset: number): Promise<Record<string, string>>;
  resolve(id: string): Promise<Record<string, string> | null>;
  prepare(name: string, type: string, length: number, existingId: string): Promise<Record<string, string>>;
  complete(id: string): Promise<Record<string, string>>;
};
type Upload = { id: string; uploaded: boolean; preparation?: Promise<Record<string, string>> };
const choice = (row: Record<string, string>): ImageChoice => ({ id: row.id, label: row.label, src: row.src, contentType: row.contentType });

/** Content owns upload authorization and storage; the adapter keeps bytes on the UI origin. */
export function workOpsDocumentImages(ctx: ClientContext, lookup: ImageContract) {
  const uploads = new WeakMap<File, Upload>();
  const controllers = new Set<AbortController>();
  let disposed = false;
  const upload = async (request: Extract<ImageRequest, { kind: "upload" }>): Promise<ImageChoice> => {
    const controller = new AbortController(); controllers.add(controller);
    const cancel = () => controller.abort();
    request.signal.addEventListener("abort", cancel, { once: true });
    if (request.signal.aborted || disposed) controller.abort();
    const { signal } = controller, file = request.file;
    try {
      signal.throwIfAborted();
      const record = uploads.get(file) ?? { id: "", uploaded: false };
      uploads.set(file, record);
      if (!record.uploaded) {
        const preparation = record.preparation ??= lookup.prepare(file.name, file.type, file.size, record.id).then(target => {
          record.id = target.id; return target;
        });
        let target: Record<string, string>;
        try { target = await preparation; }
        finally { if (record.preparation === preparation) record.preparation = undefined; }
        signal.throwIfAborted();
        await postImage(file, target, signal, value => request.progress(value, "uploading"));
        record.uploaded = true;
      }
      signal.throwIfAborted(); request.progress(100, "processing");
      const image = await lookup.complete(record.id);
      signal.throwIfAborted(); return choice(image);
    } finally { controllers.delete(controller); request.signal.removeEventListener("abort", cancel); }
  };
  ctx.on<CustomEvent<ImageRequest>>(ctx.root, "imagerequest", event => {
    event.stopPropagation(); const request = event.detail;
    switch (request.kind) {
      case "search": request.respondWith(lookup.search(request.query, request.offset).then(row => ({
        items: (JSON.parse(row.items) as Record<string, string>[]).map(choice),
        previousOffset: row.previousOffset === undefined ? undefined : Number(row.previousOffset),
        nextOffset: row.nextOffset === undefined ? undefined : Number(row.nextOffset),
      } satisfies ImagePage))); break;
      case "resolve": request.respondWith(lookup.resolve(request.id).then(row => row ? choice(row) : null)); break;
      case "upload": request.respondWith(upload(request)); break;
    }
  });
  ctx.onUnmount(() => { disposed = true; controllers.forEach(controller => controller.abort()); controllers.clear(); });
  for (const root of ctx.root.querySelectorAll<HTMLElement>("[data-media],[data-bml-image-picker]")) root.dispatchEvent(new Event("imagesready"));
}

/** Multipart is streamed by the browser and both hosts; progress/cancellation cover the byte transfer. */
function postImage(file: File, target: Record<string, string>, signal: AbortSignal, progress: (value: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const cancel = () => xhr.abort();
    const finish = (error?: Error) => { signal.removeEventListener("abort", cancel); if (error) reject(error); else resolve(); };
    xhr.open("POST", target.url);
    for (const header of JSON.parse(target.headers) as { name: string; value: string }[]) xhr.setRequestHeader(header.name, header.value);
    xhr.upload.onprogress = event => { if (event.lengthComputable && !signal.aborted) progress(event.loaded / event.total * 100); };
    xhr.onload = () => finish(xhr.status >= 200 && xhr.status < 300 ? undefined : new Error(`Image upload failed (${xhr.status}). Retry this file.`));
    xhr.onerror = () => finish(new Error("Image upload failed. Check your connection and retry this file."));
    xhr.onabort = () => finish(new DOMException("Image upload cancelled", "AbortError"));
    signal.addEventListener("abort", cancel, { once: true });
    if (signal.aborted) { finish(new DOMException("Image upload cancelled", "AbortError")); return; }
    const body = new FormData(); body.append("file", file, file.name); xhr.send(body);
  });
}
