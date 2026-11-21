import type { ScripturePassage } from "@bosca/bml-design/document-references";

type ObjectValue = Record<string, unknown>;
const object = (value: unknown): ObjectValue | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as ObjectValue : null;
const inlineStyles = new Set("w rb va vp ca qac qs add addpn bk dc efm fm k nd ndx ord pn png pro qt rq sig sls tl wg wh wa wj xt jmp no it bd bdit em sc sup xo xop xta xk xq xot xnt xdc ior iqt fr cat ft fk fq fqa fl fw fp fv fdc litl lik liv liv1 liv2 liv3 liv4 liv5".split(" "));
const footnotes = new Set(["f", "fe", "ef", "x", "ex"]);

function size(value: unknown): string {
  const raw = object(value);
  if (!raw || typeof raw.size !== "number" || !Number.isFinite(raw.size)) return "";
  const unit = { INCH: "in", POINT: "pt", PERCENT: "%", EM: "em" }[String(raw.unit)];
  return unit ? `${raw.size}${unit}` : "";
}

/** Render Content's component tree as text and elements, retaining Scripture's inline semantics. */
export function scriptureContent(components: unknown, definitions: unknown): DocumentFragment {
  const styles = new Map<string, ObjectValue>();
  if (Array.isArray(definitions)) {
    for (const value of definitions) { const style = object(value); if (style && typeof style.id === "string") styles.set(style.id, style); }
  } else {
    for (const [id, value] of Object.entries(object(definitions) ?? {})) { const style = object(value); if (style) styles.set(id, style); }
  }
  const fragment = document.createDocumentFragment();
  const append = (parent: Node, value: unknown) => {
    const node = object(value);
    if (!node || typeof node._ !== "string") throw new Error("Unsupported scripture component.");
    const ref = object(node.style), id = typeof ref?.id === "string" ? ref.id : "";
    const styleIds = id.split(" "), inline = styleIds.some(value => inlineStyles.has(value));
    if (node._ === "ve" || node._ === "cc" && styleIds.some(value => footnotes.has(value))) return;
    let element: HTMLElement;
    if (node._ === "cc") {
      const type = String(node.type);
      const tag = inline || type === "SPAN" ? "span" : type === "PARAGRAPH" ? "p" : "div";
      element = document.createElement(tag);
      element.classList.add(inline ? "scripture-inline" : `scripture-${type.toLowerCase()}`);
      if (!Array.isArray(node.components)) throw new Error("Scripture container has no components.");
      for (const child of node.components) append(element, child);
    } else if (node._ === "t") {
      if (typeof node.text !== "string") throw new Error("Scripture text is missing.");
      element = document.createElement("span"); element.textContent = node.text;
    } else if (node._ === "vs") {
      const reference = object(node.reference);
      if (typeof reference?.usfm !== "string") throw new Error("Scripture verse reference is missing.");
      element = document.createElement("sup"); element.className = "scripture-verse";
      element.textContent = reference.usfm.split(".").at(-1) ?? "";
      if (typeof reference.human === "string") element.title = reference.human;
    } else if (node._ === "b") element = document.createElement("br");
    else throw new Error(`Unsupported scripture component: ${node._}.`);
    const style: ObjectValue = { ...Object.assign({}, ...styleIds.map(key => styles.get(key))),
      ...Object.fromEntries(Object.entries(ref ?? {}).filter(([, value]) => value !== null && value !== undefined)) };
    if (style.reference === true) element.classList.add("scripture-reference");
    if (styleIds.some(value => ["it", "bdit", "em"].includes(value))) element.style.fontStyle = "italic";
    if (styleIds.some(value => ["bd", "bdit"].includes(value))) element.style.fontWeight = "700";
    if (styleIds.includes("sc")) element.style.fontVariant = "small-caps";
    if (styleIds.includes("sup")) { element.style.verticalAlign = "super"; element.style.fontSize = "0.7em"; }
    if (typeof style.fontWeight === "string") {
      if (style.fontWeight === "ITALIC") element.style.fontStyle = "italic";
      else element.style.fontWeight = style.fontWeight.toLowerCase();
    }
    for (const key of ["fontFamily", "color", "whiteSpace", "verticalAlign", "textDecoration", "align"] as const) {
      if (typeof style[key] === "string") element.style[key === "align" ? "textAlign" : key] = key === "fontFamily" ? style[key] : String(style[key]).toLowerCase().replaceAll("_", "-");
    }
    element.style.fontSize = size(style.fontSize) || element.style.fontSize;
    element.style.textIndent = size(style.textIndent);
    const margin = object(style.margin);
    if (margin) for (const side of ["Top", "Right", "Bottom", "Left"] as const) element.style[`margin${side}`] = size(margin[side.toLowerCase()]);
    parent.appendChild(element);
  };
  for (const component of Array.isArray(components) ? components : [components]) append(fragment, component);
  return fragment;
}

/** Supplied attribution stays with its passage and never enters the stored document body. */
export function scripturePassage(passage: ScripturePassage): HTMLElement {
  const section = document.createElement("section"), title = document.createElement("p"), body = document.createElement("div");
  section.className = "document-scripture-passage";
  section.setAttribute("role", "group"); section.setAttribute("aria-label", passage.label);
  title.className = "document-scripture-label"; title.textContent = passage.label;
  body.className = "document-scripture-content";
  body.append(scriptureContent(passage.components, passage.styles)); section.append(title, body);
  if (passage.attribution) {
    const attribution = document.createElement("footer"); attribution.className = "document-scripture-attribution";
    for (const value of [passage.attribution.copyright, passage.attribution.citation]) {
      if (value) { const line = document.createElement("p"); line.textContent = value; attribution.append(line); }
    }
    if (passage.attribution.informationUrl) {
      const url = new URL(passage.attribution.informationUrl, location.href);
      if (["https:", "http:"].includes(url.protocol)) {
        const link = document.createElement("a"); link.href = url.href; link.textContent = "Source information"; attribution.append(link);
      }
    }
    section.append(attribution);
  }
  return section;
}
