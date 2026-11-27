import type { ClientContext } from "@bosca/bml";
import type { ImageRequest } from "@bosca/bml-design/images";

const image = { id: "example-image", label: "Illustrative image", contentType: "image/svg+xml",
  src: "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#c7d2fe"/><circle cx="80" cy="45" r="24" fill="#4f46e5"/></svg>') };

/** The independent consumer supplies its own illustrative data through the exported public types. */
export function exampleImages(ctx: ClientContext) {
  ctx.on<CustomEvent<ImageRequest>>(ctx.root, "imagerequest", event => {
    event.stopPropagation(); const request = event.detail;
    switch (request.kind) {
      case "search": request.respondWith({ items: image.label.toLowerCase().includes(request.query.toLowerCase()) ? [image] : [] }); break;
      case "resolve": request.respondWith(request.id === image.id ? image : null); break;
      case "upload": request.respondWith(Promise.reject(new Error("This example supplies library choices. Configure your application's upload adapter to store files."))); break;
    }
  });
  for (const editor of ctx.root.querySelectorAll<HTMLElement>("[data-media]")) editor.dispatchEvent(new Event("imagesready"));
}
