import type { ClientContext } from "@bosca/bml";
import type { ImageChoice, ImageRequest } from "@bosca/bml-design/images";

const diagram: ImageChoice = { id: "catalog-diagram", label: "Illustrative diagram", contentType: "image/svg+xml",
  src: "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180"><rect width="320" height="180" rx="16" fill="#e0e7ff"/><circle cx="80" cy="90" r="34" fill="#4f46e5"/><path d="M134 90h126" stroke="#4f46e5" stroke-width="8"/><path d="m240 70 20 20-20 20" fill="none" stroke="#4f46e5" stroke-width="8"/></svg>') };
const images = new Map<string, ImageChoice>([[diagram.id, diagram]]);

/** Illustrative local data demonstrates the public picker contract without a Content backend. */
export function catalogImages(ctx: ClientContext) {
  ctx.on<CustomEvent<ImageRequest>>(ctx.root, "imagerequest", event => {
    event.stopPropagation(); const request = event.detail;
    switch (request.kind) {
      case "resolve": request.respondWith(images.get(request.id) ?? null); break;
      case "search": request.respondWith(request.query === "error" ? Promise.reject(new Error("Illustrative lookup failure"))
        : { items: [...images.values()].filter(image => image.label.toLowerCase().includes(request.query.toLowerCase())) }); break;
      case "upload": request.respondWith(new Promise<ImageChoice>((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () => { request.signal.removeEventListener("abort", cancel); reject(new Error("Could not read this example file.")); };
        reader.onabort = () => { request.signal.removeEventListener("abort", cancel); reject(new DOMException("Upload cancelled", "AbortError")); };
        reader.onprogress = event => { if (event.lengthComputable) request.progress(event.loaded / event.total * 100, "uploading"); };
        reader.onload = () => {
          request.signal.removeEventListener("abort", cancel);
          if (request.signal.aborted) { reject(new DOMException("Upload cancelled", "AbortError")); return; }
          const image = { id: crypto.randomUUID(), label: request.file.name, contentType: request.file.type, src: String(reader.result) };
          images.set(image.id, image); resolve(image);
        };
        const cancel = () => reader.abort();
        if (request.signal.aborted) { reject(new DOMException("Upload cancelled", "AbortError")); return; }
        request.signal.addEventListener("abort", cancel, { once: true }); reader.readAsDataURL(request.file);
      })); break;
    }
  });
  for (const editor of ctx.root.querySelectorAll<HTMLElement>("[data-media]")) editor.dispatchEvent(new Event("imagesready"));
}
