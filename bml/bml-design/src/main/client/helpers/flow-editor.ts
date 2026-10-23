import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { createApp, defineComponent, h, markRaw, nextTick, shallowRef, type Component } from "vue";
import { VueFlow, Handle, Position, useVueFlow, type Connection, type Edge, type Node, type VueFlowStore, type ViewportTransform } from "@vue-flow/core";
import "@vue-flow/core/dist/style.css";
import "./flow-editor.css";

type Port = { id: string; label?: string };
const ports = (value: unknown): Port[] | undefined => Array.isArray(value) ? value as Port[] : undefined;
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);
type Graph = Record<string, unknown> & { nodes: Node[]; edges: Edge[] };
const Card = defineComponent({
  inheritAttrs: false,
  props: ["data", "connectable", "sourcePosition", "targetPosition"],
  setup(props) {
    return () => {
      const data = props.data as Record<string, unknown>;
      const handles = (kind: "source" | "target", list: Port[] | undefined) => (list ?? [{ id: "" }]).map((port, i, all) => {
        const side = kind === "source" ? props.sourcePosition ?? Position.Right : props.targetPosition ?? Position.Left;
        return h(Handle, { id: port.id || undefined, type: kind, position: side,
          connectable: props.connectable, title: port.label ?? port.id ?? kind,
          style: { [side === Position.Top || side === Position.Bottom ? "left" : "top"]: `${100 * (i + 1) / (all.length + 1)}%` } });
      });
      return h("div", { class: "flow-card", "data-tone": typeof data.tone === "string" ? data.tone : "" }, [
        ...handles("target", ports(data.inputs)),
        h("strong", String(data.label ?? "Node")),
        data.description ? h("span", String(data.description)) : null,
        data.status ? h("small", String(data.status)) : null,
        ...handles("source", ports(data.outputs)),
      ]);
    };
  },
});

/** Mount an isolated VueFlow canvas while preserving native BML form ownership. */
export function mountFlowEditor(ctx: ClientContext) {
  const target = ctx.ref<HTMLDivElement>("canvas"), field = ctx.ref<HTMLTextAreaElement>("value");
  const feedback = ctx.ref<HTMLElement>("feedback"), controls = ctx.ref<HTMLElement>("controls"), outline = ctx.ref<HTMLElement>("outline");
  const sources = ctx.ref<HTMLSelectElement>("source"), targets = ctx.ref<HTMLSelectElement>("target");
  if (!target || !field || !feedback || !controls || !outline || !sources || !targets) return;
  const instanceId = `bml-flow-${crypto.randomUUID()}`;
  const locked = shallowRef(false), disabled = shallowRef(false);
  let store: VueFlowStore, previous = ctx.root.dataset.value ?? "{\"nodes\":[],\"edges\":[]}";
  let base: Graph = { nodes: [], edges: [] }, disposed = false, replacing = false, invalid = "", pending = false;
  let form: HTMLFormElement | null = null, queued = false;
  let initialFit = true, requestedViewport: ViewportTransform | undefined;
  const emit = (name: string, detail: unknown, cancelable = false) => ctx.root.dispatchEvent(new CustomEvent(name, { bubbles: true, cancelable, detail }));
  const report = () => {
    feedback.textContent = invalid || ctx.root.dataset.error || ""; feedback.hidden = !feedback.textContent;
    field.setCustomValidity(invalid); ctx.root.setAttribute("aria-invalid", String(Boolean(feedback.textContent)));
  };
  const select = (kind: "node" | "edge", id: string) => {
    store.getSelectedNodes.value.forEach(node => { node.selected = false; });
    store.getSelectedEdges.value.forEach(edge => { edge.selected = false; });
    const element = kind === "node" ? store.findNode(id) : store.findEdge(id);
    if (element && !disabled.value) { element.selected = true; emit("flowselect", { kind, id, data: element.data }); }
    updateOutline();
  };
  const updateOutline = () => {
    for (const kind of ["nodes", "edges"] as const) {
      const list = ctx.ref<HTMLUListElement>(kind);
      if (!list) continue;
      const entries = kind === "nodes" ? store.getNodes.value : store.getEdges.value;
      // Reuse list controls so selection and graph updates retain keyboard focus.
      const existing = new Map([...list.children].map(li => [(li as HTMLElement).dataset.id, li]));
      for (const [index, entry] of entries.entries()) {
        const li = (existing.get(entry.id) ?? document.createElement("li")) as HTMLElement; li.dataset.id = entry.id; existing.delete(entry.id);
        let button = li.querySelector("button");
        if (!button) { button = document.createElement("button"); button.type = "button"; button.dataset.flowKind = kind === "nodes" ? "node" : "edge"; li.append(button); }
        button.dataset.flowId = entry.id; button.disabled = disabled.value;
        button.setAttribute("aria-pressed", String(entry.selected));
        button.textContent = kind === "nodes" ? String(entry.data?.label ?? entry.label ?? entry.id)
          : `${(entry as Edge).source}${(entry as Edge).sourceHandle ? ` · ${(entry as Edge).sourceHandle}` : ""} → ${(entry as Edge).target}${(entry as Edge).targetHandle ? ` · ${(entry as Edge).targetHandle}` : ""}`;
        if (list.children[index] !== li) list.insertBefore(li, list.children[index] ?? null);
      }
      existing.forEach(li => li.remove());
    }
    for (const [select, kind] of [[sources, "source"], [targets, "target"]] as const) {
      const old = select.value; const options: HTMLOptionElement[] = [];
      for (const node of store.getNodes.value) {
        if (node.type === (kind === "source" ? "output" : "input") || node.type === "group") continue;
        const list = ports(node.data?.[kind === "source" ? "outputs" : "inputs"]) ?? [{ id: "" }];
        for (const port of list) options.push(new Option(`${String(node.data?.label ?? node.label ?? node.id)}${port.id ? ` · ${port.label ?? port.id}` : ""}`, JSON.stringify({ node: node.id, handle: port.id || null })));
      }
      select.replaceChildren(...options); if (options.some(option => option.value === old)) select.value = old;
      select.disabled = locked.value;
    }
    const remove = ctx.ref<HTMLButtonElement>("remove"), connect = ctx.ref<HTMLButtonElement>("connect");
    if (remove) remove.disabled = locked.value || (!store.getSelectedNodes.value.length && !store.getSelectedEdges.value.length);
    if (connect) connect.disabled = locked.value || !sources.value || !targets.value;
  };
  const publish = () => {
    if (disposed || replacing || locked.value || invalid) return;
    const { nodes, edges } = store.toObject();
    const originals = new Map(base.nodes.map(node => [node.id, node]));
    for (const node of nodes) for (const key of ["draggable", "connectable", "selectable", "focusable", "domAttributes"] as const) {
      const original = originals.get(node.id)?.[key];
      if (original === undefined) delete node[key]; else Object.assign(node, { [key]: original });
    }
    for (const edge of edges) {
      const original = base.edges.find(item => item.id === edge.id)?.domAttributes;
      if (original === undefined) delete edge.domAttributes; else edge.domAttributes = original;
    }
    const graph = { ...base, nodes, edges, viewport: { ...store.viewport.value } };
    field.value = JSON.stringify(graph);
    field.dispatchEvent(new Event("input", { bubbles: true })); field.dispatchEvent(new Event("change", { bubbles: true }));
    emit("flowchange", { graph }); updateOutline();
  };
  const schedule = () => { if (!queued) { queued = true; void nextTick(() => { queued = false; publish(); }); } };
  const connect = (connection: Connection) => {
    if (locked.value || invalid || !connection.source || !connection.target) return;
    if (!emit("flowconnect", { connection }, true)) return;
    const duplicate = store.getEdges.value.some(edge => edge.source === connection.source && edge.target === connection.target
      && (edge.sourceHandle || null) === (connection.sourceHandle || null) && (edge.targetHandle || null) === (connection.targetHandle || null));
    if (!duplicate) { store.addEdges([{ ...connection, id: crypto.randomUUID() }]); schedule(); }
  };
  const app = createApp(defineComponent({ setup() {
    store = useVueFlow(instanceId);
    store.onNodesInitialized(() => {
      if (requestedViewport) { void store.setViewport(requestedViewport); requestedViewport = undefined; }
      else if (initialFit) void store.fitView({ padding: 0.2 });
      initialFit = false;
    });
    store.onMoveEnd(schedule);
    store.onConnect(connect);
    store.onNodeClick(({ node }) => { emit("flowselect", { kind: "node", id: node.id, data: node.data }); void nextTick(updateOutline); });
    store.onEdgeClick(({ edge }) => { emit("flowselect", { kind: "edge", id: edge.id, data: edge.data }); void nextTick(updateOutline); });
    store.onNodesChange(changes => { if (changes.some(change => change.type === "position" || change.type === "remove" || change.type === "add")) schedule(); void nextTick(updateOutline); });
    store.onEdgesChange(changes => { if (changes.some(change => change.type !== "select")) schedule(); void nextTick(updateOutline); });
    return () => h(VueFlow as Component, { id: instanceId, nodeTypes: { card: markRaw(Card) }, minZoom: 0.2, maxZoom: 2,
      nodesDraggable: !locked.value, nodesConnectable: !locked.value, elementsSelectable: !disabled.value,
      nodesFocusable: !disabled.value, edgesFocusable: !disabled.value, deleteKeyCode: null,
      panOnDrag: !disabled.value, zoomOnScroll: false, zoomOnPinch: !disabled.value, zoomOnDoubleClick: false,
      role: "group", "aria-label": ctx.root.dataset.label ?? "Flow editor" });
  } }));
  app.mount(target);
  const load = (raw: string, fit: boolean) => {
    replacing = true;
    try {
      const graph: unknown = JSON.parse(raw);
      if (!record(graph) || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) throw new Error("Supply graph nodes and edges arrays.");
      const ids = new Set<string>();
      for (const node of graph.nodes) {
        if (!record(node) || typeof node.id !== "string" || !node.id || ids.has(node.id) || !record(node.position)
          || !Number.isFinite(node.position.x) || !Number.isFinite(node.position.y)) throw new Error("Nodes need unique IDs and finite x/y positions.");
        ids.add(node.id);
        if (node.data !== undefined && !record(node.data)) throw new Error("Node data must be an object.");
        for (const key of ["inputs", "outputs"]) if (record(node.data) && node.data[key] !== undefined) {
          const values = node.data[key];
          if (!Array.isArray(values) || values.some(port => !record(port) || typeof port.id !== "string" || !port.id)
            || new Set(values.map(port => port.id)).size !== values.length) throw new Error("Named ports need unique nonempty IDs.");
        }
      }
      const edgeIds = new Set<string>();
      for (const edge of graph.edges) {
        if (!record(edge) || typeof edge.id !== "string" || !edge.id || edgeIds.has(edge.id) || !ids.has(String(edge.source)) || !ids.has(String(edge.target)))
          throw new Error("Edges need unique IDs and existing source/target nodes.");
        edgeIds.add(edge.id);
      }
      base = graph as Graph; invalid = "";
      if (record(graph.viewport)) {
        const { x, y, zoom } = graph.viewport;
        if (typeof x !== "number" || typeof y !== "number" || typeof zoom !== "number" || !Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(zoom) || zoom <= 0)
          throw new Error("Viewport needs finite x/y coordinates and a positive zoom.");
        requestedViewport = { x, y, zoom };
      }
      store.setNodes(base.nodes); store.setEdges(base.edges);
      void nextTick(() => { if (disposed) return; if (!fit && requestedViewport) { void store.setViewport(requestedViewport); requestedViewport = undefined; } replacing = false; updateOutline(); });
    } catch (error) {
      invalid = error instanceof Error ? error.message : "Invalid graph.";
      store.setNodes([]); store.setEdges([]); replacing = false;
    }
    field.value = raw; report();
  };
  const observer = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    if (field.form !== form) { observer.disconnect(); form = field.form; if (form) observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] }); }
    pending = Boolean(form?.hasAttribute("data-bml-action-pending"));
    disabled.value = ctx.root.hasAttribute("data-disabled");
    locked.value = disabled.value || pending || ctx.root.hasAttribute("data-readonly") || Boolean(invalid);
    field.name = ctx.root.dataset.name ?? "graph"; field.disabled = disabled.value; field.readOnly = locked.value;
    const value = ctx.root.dataset.value ?? "{\"nodes\":[],\"edges\":[]}";
    if (value !== previous) { previous = value; load(value, false); locked.value = disabled.value || pending || ctx.root.hasAttribute("data-readonly") || Boolean(invalid); }
    for (const node of store.getNodes.value) {
      const original = base.nodes.find(item => item.id === node.id);
      node.draggable = !locked.value && original?.draggable !== false;
      node.connectable = !locked.value && original?.connectable !== false;
      node.selectable = !disabled.value && original?.selectable !== false;
      node.focusable = !disabled.value && original?.focusable !== false;
      node.domAttributes = { ...original?.domAttributes,
        "aria-describedby": [original?.domAttributes?.["aria-describedby"], `${ctx.root.dataset.editorId}-instructions`].filter(Boolean).join(" ") };
    }
    for (const edge of store.getEdges.value) {
      const original = base.edges.find(item => item.id === edge.id);
      edge.domAttributes = { ...original?.domAttributes,
        "aria-describedby": [original?.domAttributes?.["aria-describedby"], `${ctx.root.dataset.editorId}-instructions`].filter(Boolean).join(" ") };
    }
    controls.hidden = Boolean(invalid); outline.hidden = Boolean(invalid);
    const connectControls = ctx.ref<HTMLElement>("connectControls"), remove = ctx.ref<HTMLElement>("remove");
    if (connectControls) connectControls.hidden = ctx.root.hasAttribute("data-readonly");
    if (remove) remove.hidden = ctx.root.hasAttribute("data-readonly");
    for (const ref of ["fit", "zoomIn", "zoomOut"]) { const button = ctx.ref<HTMLButtonElement>(ref); if (button) button.disabled = disabled.value; }
    field.hidden = !invalid; report(); updateOutline(); ctx.root.setAttribute("data-flow-ready", "");
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("button") : null;
    if (!button || button.disabled) return;
    if (button === ctx.ref("fit")) void store.fitView({ padding: 0.2 }).then(schedule);
    else if (button === ctx.ref("zoomIn")) void store.zoomIn().then(schedule);
    else if (button === ctx.ref("zoomOut")) void store.zoomOut().then(schedule);
    else if (button === ctx.ref("remove") && !locked.value) {
      store.removeNodes(store.getSelectedNodes.value); store.removeEdges(store.getSelectedEdges.value); schedule();
    } else if (button === ctx.ref("connect") && !locked.value && sources.value && targets.value) {
      const source = JSON.parse(sources.value) as { node: string; handle: string | null }, target = JSON.parse(targets.value) as { node: string; handle: string | null };
      connect({ source: source.node, target: target.node, sourceHandle: source.handle, targetHandle: target.handle });
    } else if (button.dataset.flowId) select(button.dataset.flowKind === "node" ? "node" : "edge", button.dataset.flowId);
  });
  ctx.on<Event>(field, "invalid", event => { event.preventDefault(); field.hidden = false; field.focus(); });
  ctx.on<Event>(document, "reset", event => { if (event.target === field.form) queueMicrotask(() => { if (!disposed && !event.defaultPrevented) { load(previous, false); sync(); } }); }, true);
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUnmount(() => { disposed = true; observer.disconnect(); app.unmount(); store.$destroy(); ctx.root.removeAttribute("data-flow-ready"); });
  load(previous, true); sync();
}
