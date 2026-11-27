import type { DocumentReferenceRequest, ReferenceChoice, MetadataReference, ScripturePassage } from "@bosca/bml-design/document-references";
import { scripturePassage } from "./scripture";

type RequestInput = { [K in DocumentReferenceRequest["kind"]]: Omit<Extract<DocumentReferenceRequest, { kind: K }>, "respondWith" | "signal"> }[DocumentReferenceRequest["kind"]];
type Attributes = Record<string, unknown>;
const references = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === "string" && item.length > 0) : [];

function request<T>(root: HTMLElement, query: RequestInput, signal: AbortSignal): Promise<T> {
  let response: T | Promise<T> | undefined, answered = false;
  const detail = { ...query, signal, respondWith(value: T | Promise<T>) {
    if (answered) throw new Error("Document reference request already has a response.");
    answered = true; response = value;
  } } as DocumentReferenceRequest;
  root.dispatchEvent(new CustomEvent<DocumentReferenceRequest>("documentreferencerequest", { bubbles: true, detail }));
  return answered ? Promise.resolve(response!) : Promise.reject(new Error("Document references are unavailable."));
}

function choices(value: readonly ReferenceChoice[]): readonly ReferenceChoice[] {
  if (!Array.isArray(value) || value.some(item => !item || typeof item.id !== "string" || !item.id || typeof item.label !== "string")) {
    throw new Error("Invalid reference choices.");
  }
  return value;
}

function button(label: string, action: () => void, signal: AbortSignal) {
  const control = document.createElement("button"); control.type = "button"; control.textContent = label;
  control.addEventListener("click", action, { signal }); return control;
}

/** Native controls within a ProseMirror node view; the application supplies all authorized data. */
export function documentReferencePanel(root: HTMLElement, options: {
  kind: "METADATA" | "BIBLE";
  standalone?: boolean;
  filters: readonly string[];
  current(): Attributes;
  change(patch: Attributes): void;
}) {
  const dom = document.createElement("div"), controls = document.createElement("div"), selected = document.createElement("div");
  const status = document.createElement("p"), retry = document.createElement("button");
  dom.className = "document-reference-panel"; dom.contentEditable = "false";
  controls.className = "document-reference-controls"; selected.className = "document-reference-selected";
  status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
  retry.type = "button"; retry.textContent = "Reload reference"; retry.hidden = true;
  dom.append(controls, status, retry, selected);
  const lifetime = new AbortController();
  let loading: AbortController | undefined, searching: AbortController | undefined, listing: AbortController | undefined;
  let rendered = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined, signature = "", editable = true, disposed = false;
  let queryInput: HTMLInputElement | undefined, results: HTMLSelectElement | undefined, bible: HTMLSelectElement | undefined;
  let metadataChoices: readonly ReferenceChoice[] = [], bibleChoices: readonly ReferenceChoice[] = [], bibleOffset = 0;
  let moreBibles: HTMLButtonElement | undefined;
  let started = false, listed = false;
  const message = (text: string, failed = false) => { status.textContent = text; status.hidden = !text; retry.hidden = !failed; };
  const active = (controller: AbortController) => !disposed && !controller.signal.aborted;
  const ids = (attrs: Attributes): string[] => options.standalone && Array.isArray(attrs.metadataIds)
    ? references(attrs.metadataIds) : typeof attrs.metadataId === "string" && attrs.metadataId ? [attrs.metadataId] : [];
  const refs = (attrs: Attributes) => references(options.standalone && Array.isArray(attrs.usfm) ? attrs.usfm : attrs.references);
  const change = (patch: Attributes) => { if (editable && !disposed) options.change(patch); };
  const setRefs = (next: string[]) => change({ [options.standalone && Array.isArray(options.current().usfm) ? "usfm" : "references"]: next });
  const label = (text: string, field: HTMLElement) => { const wrapper = document.createElement("label"); wrapper.append(document.createTextNode(text), field); controls.append(wrapper); };
  const search = () => {
    searching?.abort(); clearTimeout(timer);
    if (!editable || !queryInput || !results) return;
    const controller = searching = new AbortController(), query = queryInput.value;
    metadataChoices = [];
    results.replaceChildren(new Option("Searching…", "")); results.disabled = true;
    timer = setTimeout(() => {
      const input: RequestInput = options.kind === "METADATA" ? { kind: "metadata-search", query, filters: options.filters }
        : { kind: "scripture-search", id: ids(options.current())[0] ?? "", query };
      if (input.kind === "scripture-search" && (!input.id || !query.trim())) {
        results!.replaceChildren(new Option("Search for a reference…", "")); return;
      }
      void request<readonly ReferenceChoice[]>(root, input, controller.signal).then(value => {
        if (!active(controller) || !editable) return;
        metadataChoices = choices(value);
        results!.replaceChildren(new Option(value.length ? "Choose a reference…" : "No references found", ""), ...value.map(item => new Option(item.label, item.id)));
        results!.disabled = !value.length; results!.setAttribute("aria-invalid", "false");
      }).catch(() => {
        if (!active(controller)) return;
        results!.replaceChildren(new Option("Could not load references. Search again to retry.", ""));
        results!.disabled = true; results!.setAttribute("aria-invalid", "true");
      });
    }, 250);
  };
  if (options.kind === "BIBLE") {
    bible = document.createElement("select"); bible.append(new Option("Select a Bible…", ""));
    label("Bible", bible);
    moreBibles = button("More Bibles", () => loadBibles(), lifetime.signal); moreBibles.hidden = true; controls.append(moreBibles);
    bible.addEventListener("change", () => {
      if (!bibleChoices.some(item => item.id === bible!.value)) return;
      const attrs = options.current();
      if (options.standalone && Array.isArray(attrs.metadataIds)) {
        const next = ids(attrs); if (!next.includes(bible!.value)) change({ metadataIds: [...next, bible!.value] });
        bible!.value = "";
      } else change({ metadataId: bible!.value });
    }, { signal: lifetime.signal });
  }
  queryInput = document.createElement("input"); queryInput.type = "search";
  queryInput.placeholder = options.kind === "METADATA" ? "Search metadata…" : "Genesis 1:1";
  label(options.kind === "METADATA" ? "Find metadata" : "Find scripture", queryInput);
  results = document.createElement("select"); results.append(new Option("Search for a reference…", "")); results.disabled = true;
  label("Reference results", results);
  queryInput.addEventListener("input", search, { signal: lifetime.signal });
  queryInput.addEventListener("keydown", event => { if (event.key === "Enter" && !event.isComposing) { event.preventDefault(); search(); } }, { signal: lifetime.signal });
  results.addEventListener("change", () => {
    const choice = metadataChoices.find(item => item.id === results!.value);
    if (!choice || !editable) return;
    if (options.kind === "METADATA") change({ metadataId: choice.id });
    else { const current = refs(options.current()); if (!current.includes(choice.id)) setRefs([...current, choice.id]); }
    queryInput!.value = ""; results!.value = "";
    if (options.kind === "BIBLE") { results!.replaceChildren(new Option("Search for a reference…", "")); results!.disabled = true; }
  }, { signal: lifetime.signal });

  function loadBibles() {
    if (!editable || disposed) return;
    listed = true;
    listing?.abort(); const controller = listing = new AbortController();
    if (moreBibles) moreBibles.disabled = true;
    void request<readonly ReferenceChoice[]>(root, { kind: "bibles", offset: bibleOffset }, controller.signal).then(value => {
      if (!active(controller)) return;
      const rows = choices(value); bibleOffset += rows.length;
      bibleChoices = [...bibleChoices, ...rows.filter(row => !bibleChoices.some(item => item.id === row.id))];
      const current = bible!.value;
      bible!.replaceChildren(new Option("Select a Bible…", ""), ...bibleChoices.map(item => new Option(item.label, item.id)));
      const selectedId = ids(options.current())[0]; bible!.value = options.standalone && Array.isArray(options.current().metadataIds) ? current : selectedId ?? "";
      for (const item of selected.querySelectorAll<HTMLElement>("[data-reference-bible]")) {
        const name = item.querySelector("span"), choice = bibleChoices.find(value => value.id === item.dataset.referenceBible);
        if (name && choice) name.textContent = choice.label;
      }
      if (moreBibles) { moreBibles.hidden = rows.length < 25; moreBibles.disabled = !editable; }
    }).catch(() => {
      if (!active(controller)) return;
      message("Could not load Bibles. Reload to retry.", true);
      if (moreBibles) moreBibles.disabled = !editable;
    });
  }

  function refresh() {
    loading?.abort(); searching?.abort(); clearTimeout(timer);
    rendered.abort(); rendered = new AbortController();
    const controller = loading = new AbortController(), attrs = options.current();
    metadataChoices = []; results!.replaceChildren(new Option("Search for a reference…", "")); results!.disabled = true;
    selected.replaceChildren(); message("");
    if (bible && !Array.isArray(attrs.metadataIds)) bible.value = ids(attrs)[0] ?? "";
    if (options.kind === "METADATA") {
      if (!ids(attrs).length) { message("No metadata selected."); return; }
      const clear = button("Clear metadata reference", () => change({ metadataId: null, renderer: null }), rendered.signal);
      clear.dataset.referenceEdit = ""; clear.hidden = !editable; clear.disabled = !editable; selected.append(clear);
      message("Loading reference…");
      void request<MetadataReference | null>(root, { kind: "metadata", id: ids(attrs)[0], version: typeof attrs.version === "number" ? attrs.version : null }, controller.signal).then(value => {
        if (!active(controller)) return;
        if (!value) { message("This reference is unavailable or you do not have access to it.", true); return; }
        if (typeof value.label !== "string" || typeof value.id !== "string") throw new Error("Invalid metadata reference.");
        const title = button(value.label, () => root.dispatchEvent(new CustomEvent("documentmentionactivate", { bubbles: true, detail: { id: value.id, entityType: "metadata" } })), rendered.signal);
        selected.prepend(title);
        for (const text of [value.contentType, attrs.renderer]) if (typeof text === "string" && text) {
          const badge = document.createElement("span"); badge.className = "document-reference-badge"; badge.textContent = text; selected.append(badge);
        }
        message("");
      }).catch(() => { if (active(controller)) message("Could not load the reference. Reload to retry.", true); });
    } else {
      const identities = ids(attrs), stored = refs(attrs);
      for (const id of identities) {
        const item = document.createElement("div"), name = document.createElement("span");
        item.className = "document-reference-identity";
        item.dataset.referenceBible = id;
        name.textContent = bibleChoices.find(choice => choice.id === id)?.label ?? "Selected Bible";
        const clear = button("Remove Bible", () => {
          const current = options.current();
          change(options.standalone && Array.isArray(current.metadataIds)
            ? { metadataIds: ids(current).filter(value => value !== id) } : { metadataId: null });
        }, rendered.signal);
        clear.dataset.referenceEdit = ""; clear.hidden = !editable; clear.disabled = !editable;
        item.append(name, clear); selected.append(item);
      }
      if (!identities.length || !stored.length) { message(identities.length ? "No scripture references selected." : "Select a Bible to load scripture."); return; }
      message("Loading scripture…");
      void (async () => {
        for (const reference of stored) {
          const group = document.createElement("section"); group.className = "document-scripture-reference";
          const remove = button(`Remove ${reference}`, () => setRefs(refs(options.current()).filter(item => item !== reference)), rendered.signal);
          remove.setAttribute("aria-label", `Remove ${reference} reference`); remove.dataset.referenceEdit = ""; remove.hidden = !editable; remove.disabled = !editable;
          group.append(remove);
          selected.append(group);
          for (const id of identities) {
            const passages = await request<readonly ScripturePassage[]>(root, { kind: "scripture", id, reference }, controller.signal);
            if (!active(controller)) return;
            if (!Array.isArray(passages) || !passages.length) throw new Error("Scripture is unavailable.");
            for (const passage of passages) group.append(scripturePassage(passage));
          }
          if (!active(controller)) return;
        }
        if (active(controller)) message("");
      })().catch(() => { if (active(controller)) message("Could not load scripture. The stored references are retained. Reload to retry.", true); });
    }
  }
  retry.addEventListener("click", () => { refresh(); if (options.kind === "BIBLE" && editable) loadBibles(); }, { signal: lifetime.signal });
  return {
    dom,
    update(nextEditable: boolean) {
      editable = nextEditable; controls.hidden = !editable;
      for (const control of controls.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>("input,select,button")) control.disabled = !editable;
      if (results) results.disabled = !editable || !metadataChoices.length;
      for (const control of selected.querySelectorAll<HTMLButtonElement>("[data-reference-edit]")) { control.hidden = !editable; control.disabled = !editable; }
      const attrs = options.current(), next = JSON.stringify([ids(attrs), refs(attrs), attrs.version, attrs.renderer]);
      if (next !== signature) { signature = next; refresh(); }
      if (!editable) { searching?.abort(); clearTimeout(timer); }
      else if (started && options.kind === "BIBLE" && !listed) loadBibles();
    },
    start() { if (disposed) return; started = true; if (options.kind === "BIBLE" && editable) loadBibles(); else if (editable) search(); },
    reload() { if (disposed) return; refresh(); if (options.kind === "BIBLE" && editable) loadBibles(); },
    destroy() { disposed = true; lifetime.abort(); rendered.abort(); loading?.abort(); searching?.abort(); listing?.abort(); clearTimeout(timer); },
  };
}
