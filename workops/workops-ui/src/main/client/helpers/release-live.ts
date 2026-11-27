import type { IslandContext } from "@bosca/bml";
import { WorkOpsLiveSessions, type WorkOpsLiveSource } from "./release-live-state";
import { workOpsBrowser as host } from "./browser-session";

type Channel = { source: WorkOpsLiveSource; state: string; message: string; attempt: number; socket?: WebSocket; timer?: ReturnType<typeof setTimeout> };
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);

/** Owns only WorkOps stream identity, authentication and lifetime; the shared chart renders native options. */
export function workOpsReleaseLive(ctx: IslandContext) {
  const findOwner = () => ctx.root.matches("[data-workops-release-live]") ? ctx.root : ctx.root.querySelector<HTMLElement>("[data-workops-release-live]");
  const initialOwner = findOwner();
  if (!initialOwner) return;
  let owner: HTMLElement = initialOwner;
  let disposed = false, generation = 0, authGeneration = 0, signature = "", authReady = false, authRefreshing = true, authRevision = owner.dataset.streamRevision ?? "", sourceError = "";
  let channels: Channel[] = [], sessions = new WorkOpsLiveSessions([]), frame: number | undefined;
  const removers: Array<() => void> = [], projectColors = new Map<string, unknown>();
  const errorText = (error: unknown) => error instanceof Error && error.message ? error.message : "Live sessions are unavailable.";
  const paint = () => {
    if (disposed) return;
    const loaded = owner.hasAttribute("data-live-loaded"), live = channels.filter(channel => channel.state === "open").length;
    const message = sourceError || (!loaded ? "Application sources are unavailable." : !channels.length ? "Waiting for application streams." : live === channels.length ? "Live" : live ? `Live · ${live} of ${channels.length} streams connected` : "Connecting or reconnecting…");
    for (const target of owner.querySelectorAll<HTMLElement>("[data-live-status]")) target.textContent = message;
    for (const target of owner.querySelectorAll<HTMLElement>("[data-live-count]")) target.textContent = sourceError || !loaded ? "Unavailable" : `${sessions.size} sessions received`;
    const failures = channels.map(channel => channel.message).filter(Boolean);
    for (const target of owner.querySelectorAll<HTMLElement>("[data-live-error]")) target.textContent = [...new Set(failures)].join(" · ");
    try {
      const option: unknown = JSON.parse(owner.dataset.liveOptions ?? "{}");
      if (!record(option) || !Array.isArray(option.series)) throw new Error("Live map configuration is unavailable.");
      const rows = sessions.rows;
      option.series = option.series.filter(record).map(series => {
        const project = String(series.id), style = record(series.itemStyle) ? series.itemStyle : {};
        if (!projectColors.has(project)) projectColors.set(project, style.color);
        return { ...series, itemStyle: { ...style, color: projectColors.get(project) }, data: rows.filter(row => row.projectIds.includes(project)).map(row => ({
          name: `${row.appId} · ${row.sessionId} · ${row.appVersion ?? "Version unavailable"}`, value: [row.lon, row.lat],
        })) };
      });
      const encoded = JSON.stringify(option);
      for (const chart of owner.querySelectorAll<HTMLElement>("[data-workops-live-chart]")) if (chart.dataset.config !== encoded) chart.dataset.config = encoded;
    } catch (error) { for (const target of owner.querySelectorAll<HTMLElement>("[data-live-error]")) target.textContent = errorText(error); }
  };
  const schedule = () => { if (!disposed && frame === undefined) frame = requestAnimationFrame(() => { frame = undefined; paint(); }); };
  const disconnect = (channel: Channel) => {
    if (channel.timer !== undefined) clearTimeout(channel.timer);
    channel.timer = undefined;
    const socket = channel.socket; channel.socket = undefined;
    if (!socket) return;
    socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ id: "sessions", type: "complete" }));
    if (socket.readyState <= WebSocket.OPEN) socket.close();
  };
  const stop = () => { generation++; channels.forEach(disconnect); channels = []; };
  const pauseAuth = (message: string) => {
    authGeneration++; authReady = false; authRefreshing = false; authRevision = owner.dataset.streamRevision ?? ""; sourceError = message;
    stop(); sessions.clear(); schedule();
  };
  const connect = async (channel: Channel, epoch: number) => {
    const current = () => !disposed && epoch === generation && channels.includes(channel);
    const retry = (message: string) => {
      if (!current()) return;
      disconnect(channel); channel.state = "reconnecting"; channel.message = message; schedule();
      channel.timer = setTimeout(() => { channel.timer = undefined; void connect(channel, epoch); }, Math.min(30000, 1000 * 2 ** Math.min(channel.attempt++, 5)));
    };
    try {
      channel.state = "connecting"; schedule();
      const token = await host.boscaAuth?.getToken();
      if (!current()) return;
      if (!token) { pauseAuth("Sign in to receive live sessions."); return; }
      const socket = new WebSocket(`${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/_git/subscriptions`, "graphql-transport-ws");
      channel.socket = socket;
      socket.onopen = () => { if (current()) socket.send(JSON.stringify({ type: "connection_init", payload: { authToken: token } })); };
      socket.onmessage = event => {
        if (!current() || channel.socket !== socket) return;
        try {
          const message: unknown = JSON.parse(String(event.data));
          if (!record(message)) throw new Error("Invalid live session response.");
          if (message.type === "connection_ack") {
            channel.attempt = 0; channel.state = "open"; channel.message = "";
            socket.send(JSON.stringify({ id: "sessions", type: "subscribe", payload: { query: owner.dataset.subscription, variables: { appId: channel.source.appId, appVersion: channel.source.appVersion } } })); schedule();
          } else if (message.type === "ping") socket.send(JSON.stringify({ type: "pong", ...(message.payload !== undefined ? { payload: message.payload } : {}) }));
          else if (message.type === "next" && message.id === "sessions") {
            const payload = record(message.payload) ? message.payload : {};
            if (Array.isArray(payload.errors) && payload.errors.length) throw new Error(payload.errors.filter(record).map(error => String(error.message ?? "Subscription failed")).join(" · "));
            const data = record(payload.data) ? payload.data : {};
            if (!sessions.accept(channel.source, data.liveSessions, Date.now())) throw new Error("Invalid or out-of-scope live session update.");
            schedule();
          } else if (message.type === "error") {
            const errors = Array.isArray(message.payload) ? message.payload.filter(record).map(error => String(error.message ?? "Subscription failed")) : [];
            retry(errors.join(" · ") || "Live session subscription failed; reconnecting.");
          } else if (message.type === "complete") retry("Live session stream disconnected; reconnecting.");
        } catch (error) { retry(errorText(error)); }
      };
      socket.onerror = () => retry("Live session connection failed; reconnecting.");
      socket.onclose = () => retry("Live session connection closed; reconnecting.");
    } catch (error) { retry(errorText(error)); }
  };
  const readSources = (): WorkOpsLiveSource[] => {
    const value: unknown = JSON.parse(owner.dataset.liveSources ?? "[]");
    if (!Array.isArray(value) || !value.every(item => record(item) && typeof item.appId === "string" && item.appId.trim()
      && (item.appVersion === null || typeof item.appVersion === "string" && item.appVersion.trim()) && typeof item.projectId === "string" && item.projectId && typeof item.projectLabel === "string")) throw new Error("Application sources are invalid.");
    return value as WorkOpsLiveSource[];
  };
  const sync = (force = false) => {
    if (disposed) return;
    try {
      const loaded = owner.hasAttribute("data-live-loaded"), sources = loaded ? readSources() : [];
      const next = JSON.stringify([loaded, owner.dataset.streamRevision, sources.map(source => [source.appId, source.appVersion, source.projectId]).sort()]);
      if (next !== signature || force) {
        stop(); signature = next; sessions = new WorkOpsLiveSessions(sources);
        if (!authReady && !authRefreshing && owner.dataset.streamRevision !== authRevision) {
          const epoch = authGeneration;
          void host.boscaAuth?.getToken().then(token => { if (!disposed && epoch === authGeneration && token) { authReady = true; sourceError = ""; sync(true); } })
            .catch(error => { if (!disposed && epoch === authGeneration) pauseAuth(errorText(error)); });
        }
        if (authReady && loaded) {
          sourceError = "";
          const unique = new Map<string, WorkOpsLiveSource>();
          for (const source of sources) unique.set(JSON.stringify([source.appId, source.appVersion]), source);
          channels = [...unique.values()].map(source => ({ source, state: "connecting", message: "", attempt: 0 }));
          const epoch = generation; channels.forEach(channel => { void connect(channel, epoch); });
        }
      }
    } catch (error) { stop(); sessions.clear(); sourceError = errorText(error); }
    schedule();
  };
  const reauthorize = async () => {
    if (disposed) return;
    pauseAuth("Refreshing application sources…"); authRefreshing = true; const epoch = authGeneration;
    try {
      await ctx.dispatch("model", "refreshContext", [""], { coalesce: true });
      if (!disposed && epoch === authGeneration) { authReady = true; sourceError = ""; sync(true); }
    } catch (error) { if (!disposed && epoch === authGeneration) { sourceError = errorText(error); schedule(); } }
    finally { if (epoch === authGeneration) authRefreshing = false; }
  };
  ctx.onUpdate(() => {
    const next = findOwner();
    if (next) { owner = next; sync(); }
    else { stop(); sessions.clear(); }
  });
  const sweep = setInterval(() => { if (sessions.expire(Date.now())) schedule(); }, 30000);
  ctx.on(document, "visibilitychange", () => { if (!document.hidden && sessions.expire(Date.now())) schedule(); });
  ctx.onUnmount(() => { disposed = true; stop(); sessions.clear(); clearInterval(sweep); if (frame !== undefined) cancelAnimationFrame(frame); removers.forEach(remove => remove()); });
  void (async () => {
    await host.boscaAuthFlow?.ready;
    if (disposed) return;
    if (!host.boscaAuth) { pauseAuth("Sign in to receive live sessions."); return; }
    for (const event of ["tokenRefreshed", "tokenAdopted", "signedIn"]) removers.push(host.boscaAuth.on(event, () => { void reauthorize(); }));
    removers.push(host.boscaAuth.on("signedOut", () => pauseAuth("Sign in to receive live sessions.")));
    authReady = true; authRefreshing = false; sync(true);
  })().catch(error => pauseAuth(errorText(error)));
  sync();
}
