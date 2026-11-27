let renderer: Promise<typeof import("mermaid").default> | undefined;
let sequence = 0;

function diagramRenderer() {
  return renderer ??= Promise.all([import("mermaid"), import("@mermaid-js/layout-elk")]).then(([{ default: mermaid }, { default: elk }]) => {
    mermaid.registerLayoutLoaders(elk);
    mermaid.initialize({ startOnLoad: false, theme: "neutral", securityLevel: "strict", fontFamily: "inherit",
      layout: "elk", suppressErrorRendering: true });
    return mermaid;
  });
}

/** Render the stored code without replacing its source or allowing a stale render to win. */
export function documentDiagram(target: HTMLElement) {
  const image = document.createElement("div"), status = document.createElement("p");
  target.classList.add("document-diagram");
  image.className = "document-diagram-image";
  status.setAttribute("role", "status");
  target.append(status, image);
  let previous = "", active = false, revision = 0, disposed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    update(source: string, enabled: boolean) {
      if (disposed || source === previous && enabled === active) return;
      previous = source; active = enabled;
      const current = ++revision;
      clearTimeout(timer);
      target.hidden = !enabled;
      image.replaceChildren(); status.textContent = ""; status.className = "";
      if (!enabled || !source.trim()) return;
      status.textContent = "Rendering diagram…";
      timer = setTimeout(() => {
        void diagramRenderer().then(mermaid => mermaid.render(`bml-diagram-${++sequence}`, source.trim())).then(result => {
          if (disposed || current !== revision) return;
          // Mermaid's strict mode sanitizes its generated SVG before returning it.
          image.innerHTML = result.svg;
          image.querySelector("svg")?.setAttribute("aria-label", "Document diagram");
          status.textContent = "";
        }).catch(error => {
          if (disposed || current !== revision) return;
          image.replaceChildren();
          status.textContent = error instanceof Error ? error.message : String(error);
          status.className = "document-diagram-error";
        });
      }, 200);
    },
    destroy() { disposed = true; ++revision; clearTimeout(timer); image.remove(); status.remove(); },
  };
}
