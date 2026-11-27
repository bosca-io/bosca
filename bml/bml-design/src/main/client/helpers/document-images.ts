import type { Node as DocumentNode } from "@tiptap/pm/model";
import type { NodeView, NodeViewConstructor } from "@tiptap/pm/view";
import type { ImageChoice } from "@bosca/bml-design/images";
import { imageChoice, imageRequest } from "./image-requests";

/** Resolve stored Content identities for the current viewer without rewriting document attributes. */
export function documentImageViews(root: HTMLElement) {
  const mounted = new Set<ImageView>();
  class ImageView implements NodeView {
    readonly dom = document.createElement("figure");
    private readonly image = document.createElement("img");
    private readonly status = document.createElement("figcaption");
    private controller?: AbortController;
    private identity = "";
    private disposed = false;
    constructor(private node: DocumentNode) {
      this.dom.dataset.documentImage = ""; this.dom.contentEditable = "false";
      this.status.setAttribute("role", "status"); this.status.setAttribute("aria-live", "polite");
      this.image.addEventListener("load", this.loaded); this.image.addEventListener("error", this.failed);
      this.dom.append(this.image, this.status); mounted.add(this); this.sync();
    }
    private loaded = () => { if (!this.disposed) { this.status.hidden = true; this.image.hidden = false; } };
    private failed = () => { if (!this.disposed) { this.image.hidden = true; this.status.hidden = false; this.status.textContent = "Image unavailable."; } };
    sync(force = false) {
      this.image.alt = typeof this.node.attrs.alt === "string" ? this.node.attrs.alt : "";
      this.image.title = typeof this.node.attrs.title === "string" ? this.node.attrs.title : "";
      this.image.className = typeof this.node.attrs.class === "string" ? this.node.attrs.class : "";
      const next = JSON.stringify([this.node.attrs.metadataId, this.node.attrs.src]);
      if (!force && next === this.identity) return;
      this.identity = next; this.controller?.abort();
      this.image.removeAttribute("src"); this.image.hidden = true;
      this.status.hidden = false; this.status.textContent = "Loading image…";
      const id = typeof this.node.attrs.metadataId === "string" ? this.node.attrs.metadataId : "";
      if (!id) {
        if (typeof this.node.attrs.src === "string" && this.node.attrs.src) { this.image.src = this.node.attrs.src; this.image.hidden = false; }
        else this.failed();
        return;
      }
      const controller = this.controller = new AbortController();
      void imageRequest<ImageChoice | null>(root, { kind: "resolve", id }, controller.signal).then(value => {
        if (this.disposed || controller.signal.aborted) return;
        if (!value) { this.failed(); return; }
        this.image.src = imageChoice(value).src; this.image.hidden = false;
      }).catch(() => {
        if (this.disposed || controller.signal.aborted) return;
        this.status.textContent = "Could not load the image. Reload the document to retry.";
      });
    }
    update(node: DocumentNode) { if (node.type !== this.node.type) return false; this.node = node; this.sync(); return true; }
    ignoreMutation() { return true; }
    destroy() {
      this.disposed = true; mounted.delete(this); this.controller?.abort();
      this.image.removeEventListener("load", this.loaded); this.image.removeEventListener("error", this.failed);
    }
  }
  const image: NodeViewConstructor = node => new ImageView(node);
  return { image, reload() { mounted.forEach(view => view.sync(true)); } };
}
