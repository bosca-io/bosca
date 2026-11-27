import type { IslandContext } from "@bosca/bml";
import type { SchemaFormChoicesRequest } from "@bosca/bml-design/schema-form";
import type { SelectPage } from "@bosca/bml-design/select";

/** Switches the project-selected stored form while retaining the WorkOps draft. */
export function workOpsTaskCreation(ctx: IslandContext, lookup: { choices(key: string, version: number, fieldKey: string, query: string | null, offset: number): Promise<Record<string, string>> },
  changeProject: (projectId: string, attributes: string) => Promise<unknown>) {
  let pendingProject: string | undefined;
  let disposed = false;
  const staticSources = new WeakMap<HTMLElement, { context: string; fields: Map<string, Promise<SelectPage["options"]>> }>();
  const decode = (row: Record<string, string>): SelectPage => {
    if (row.error) throw new Error(row.error);
    return { options: JSON.parse(row.options) as SelectPage["options"],
      previousOffset: row.previousOffset === undefined ? undefined : Number(row.previousOffset),
      nextOffset: row.nextOffset === undefined ? undefined : Number(row.nextOffset) };
  };
  ctx.on<CustomEvent<SchemaFormChoicesRequest>>(ctx.root, "schemaformchoicesrequest", event => {
    const form = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-workops-form-key]") : null;
    if (!form) return;
    event.stopPropagation();
    const request = event.detail;
    const load = (query: string | null, offset: number) => lookup.choices(form.dataset.workopsFormKey ?? "", Number(form.dataset.workopsFormVersion), request.fieldKey, query, offset).then(decode);
    let response: Promise<SelectPage>;
    if ((form.dataset.workopsStaticChoices ?? "").split(/\s+/).includes(request.fieldKey)) {
      const identity = JSON.stringify([form.dataset.workopsFormKey, form.dataset.workopsFormVersion, form.dataset.bmlPreserve]);
      let sources = staticSources.get(form);
      if (!sources || sources.context !== identity) {
        sources = { context: identity, fields: new Map() }; staticSources.set(form, sources);
      }
      const fields = sources.fields;
      let source = fields.get(request.fieldKey);
      if (!source) {
        const pending = load(null, 0).then(page => page.options).catch(error => {
          if (fields.get(request.fieldKey) === pending) fields.delete(request.fieldKey);
          throw error;
        });
        fields.set(request.fieldKey, pending); source = pending;
      }
      response = source.then(options => {
        if (request.query === null) return { options };
        const query = request.query?.trim().toLowerCase() ?? "";
        const matches = options.filter(option => option.label.toLowerCase().includes(query) || option.value.toLowerCase().includes(query));
        const offset = request.offset;
        return { options: matches.slice(offset, offset + 25), previousOffset: offset > 0 ? Math.max(0, offset - 25) : undefined,
          nextOffset: matches.length > offset + 25 ? offset + 25 : undefined };
      });
    } else response = load(request.query, request.offset);
    request.respondWith(response.then(page => {
      request.signal.throwIfAborted();
      if (disposed) throw new DOMException("Form closed", "AbortError");
      return page;
    }).catch(error => {
      request.signal.throwIfAborted();
      if (!disposed && error instanceof Error && error.message === "The task form changed. Reload the form.") {
        ctx.ref<HTMLElement>("formRetry")?.removeAttribute("hidden");
      }
      if (error instanceof Error && error.message.startsWith("BML contract HTTP ")) {
        throw new Error("Could not load choices. Try again.", { cause: error });
      }
      throw error;
    }));
  });
  const ready = () => { for (const form of ctx.root.querySelectorAll<HTMLElement>("[data-workops-form-key]")) form.dispatchEvent(new Event("schemaformchoicesready")); };
  ctx.onUpdate(ready);
  ctx.onUnmount(() => { disposed = true; });
  ready();
  ctx.on<CustomEvent<{ property: string; value: unknown; data: Record<string, unknown> }>>(ctx.root, "schemaformchange", event => {
    if (event.detail.property !== "projectId" || typeof event.detail.value !== "string") return;
    const project = event.detail.value;
    const owner = ctx.root.hasAttribute("data-workops-create-project") ? ctx.root
      : ctx.root.querySelector<HTMLElement>("[data-workops-create-project]");
    if (project === owner?.dataset.workopsCreateProject || project === pendingProject) return;
    pendingProject = project;
    const form = ctx.root.querySelector<HTMLElement>("[data-bml-schema-form]");
    const feedback = ctx.ref<HTMLElement>("transportError");
    if (form) form.inert = true;
    if (feedback) feedback.hidden = true;
    void changeProject(project, JSON.stringify(event.detail.data))
      .catch(error => {
        if (feedback) {
          feedback.textContent = error instanceof Error ? error.message : "Could not load the project task form.";
          feedback.hidden = false;
        }
      }).finally(() => { pendingProject = undefined; if (form) form.inert = false; });
  });
}
