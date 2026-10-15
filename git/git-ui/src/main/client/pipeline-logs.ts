import type { IslandContext } from "@bosca/bml";

interface LogLine {
  lineNumber: number;
  timestamp: string;
  content: string;
  stream: string;
}
interface SubscriptionMessage {
  type: string;
  payload?: { data?: { pipelineStepLogs?: LogLine }; errors?: unknown[] };
  id?: string;
}
const stepLogsQuery =
  "subscription PipelineStepLogs($repositoryId: UUID!, $stepId: UUID!) { pipelineStepLogs(repositoryId: $repositoryId, stepId: $stepId) { lineNumber content stream timestamp } }";

/** Owns the same-origin subscription, historical merge and viewport for one mounted step. */
export function mountPipelineLogs(ctx: IslandContext) {
  const identity = ctx.root.querySelector<HTMLElement>("[data-log-step]");
  if (!identity) return;
  const lines = new Map<number, LogLine>();
  const rows = new Map<number, HTMLElement>();
  const pendingLines = new Set<number>();
  let frame: number | undefined;
  let pendingBackfill = false;
  let paused = false,
    follow = true,
    busy = false,
    disposed = false,
    finished = false;
  let socket: WebSocket | undefined,
    reconnect: ReturnType<typeof setTimeout> | undefined,
    attempts = 0,
    generation = 0;
  const output = () => ctx.ref<HTMLElement>("output");
  let statusText = "";
  let statusState = "idle";
  const status = (text: string, state = "error") => {
    statusText = text;
    statusState = state;
    const target = ctx.ref<HTMLElement>("logStatus");
    if (target) {
      target.textContent = text;
      target.dataset.state = state;
      target.hidden = !text;
    }
  };
  const active = () =>
    ["QUEUED", "RUNNING"].includes(
      ctx.root.closest<HTMLElement>("[data-step-status]")?.dataset.stepStatus ??
        "",
    );
  const capture = () => {
    const surface = output();
    const top = surface?.getBoundingClientRect().top ?? 0;
    const children = ctx.ref<HTMLElement>("lines")?.children;
    let first = 0;
    let end = follow ? 0 : (children?.length ?? 0);
    while (first < end) {
      const middle = Math.floor((first + end) / 2);
      if ((children?.[middle]?.getBoundingClientRect().bottom ?? 0) <= top) first = middle + 1;
      else end = middle;
    }
    const row = follow ? undefined : children?.[first] as HTMLElement | undefined;
    return {
      line: row?.dataset.line,
      delta: row ? row.getBoundingClientRect().top - top : 0,
      scroll: surface?.scrollTop ?? 0,
    };
  };
  let anchor = capture();
  let earlier = false;
  const seed = () => {
    rows.clear();
    for (const row of ctx.root.querySelectorAll<HTMLElement>(".log-line[data-line]")) {
      const number = Number(row.dataset.line);
      rows.set(number, row);
      lines.set(number, {
        lineNumber: number,
        timestamp: row.dataset.timestamp ?? "",
        content: row.querySelector("code")?.textContent ?? "",
        stream: row.dataset.stream ?? "STDOUT",
      });
    }
    for (const number of lines.keys()) pendingLines.add(number);
  };
  const updateControls = () => {
    const pause = ctx.ref<HTMLButtonElement>("pause");
    if (pause) {
      pause.hidden = !active();
      pause.setAttribute("aria-pressed", String(paused));
      pause.textContent = paused ? "Resume" : "Pause";
    }
    const following = ctx.ref<HTMLButtonElement>("follow");
    if (following) {
      following.hidden = follow;
      following.setAttribute("aria-pressed", String(follow));
    }
  };
  const render = (position = capture()) => {
    const surface = output();
    const contentRoot = ctx.ref<HTMLElement>("lines");
    if (!surface || !contentRoot) return;
    if (lines.size > 20000) {
      const ordered = Array.from(lines.keys()).sort((a, b) => a - b);
      for (const number of ordered.slice(0, ordered.length - 20000)) {
        lines.delete(number);
        pendingLines.delete(number);
        rows.get(number)?.remove();
        rows.delete(number);
      }
    }
    if (lines.size) contentRoot.querySelector(".log-empty")?.remove();
    const fragment = document.createDocumentFragment();
    let last = Number(contentRoot.lastElementChild?.getAttribute("data-line") ?? 0);
    let reorder = false;
    for (const number of Array.from(pendingLines).sort((a, b) => a - b)) {
      const line = lines.get(number);
      if (!line) continue;
      let row = rows.get(number);
      if (!row) {
        row = document.createElement("div");
        row.className = "log-line";
        row.dataset.line = String(number);
        const gutter = document.createElement("span");
        gutter.className = "line-number";
        gutter.textContent = String(number);
        const content = document.createElement("code");
        content.className = "line-content";
        row.append(gutter, content);
        rows.set(number, row);
        fragment.append(row);
        if (number < last) reorder = true;
        last = Math.max(last, number);
      }
      row.dataset.stream = line.stream;
      row.dataset.timestamp = line.timestamp;
      const gutter = row.querySelector<HTMLElement>(".line-number");
      if (gutter) gutter.title = line.timestamp;
      const content = row.querySelector("code");
      if (content && content.textContent !== line.content) content.textContent = line.content;
    }
    pendingLines.clear();
    contentRoot.append(fragment);
    if (reorder) {
      let next = contentRoot.firstElementChild;
      for (const number of Array.from(rows.keys()).sort((a, b) => a - b)) {
        const row = rows.get(number);
        if (!row) continue;
        if (row !== next) contentRoot.insertBefore(row, next);
        next = row.nextElementSibling;
      }
    }
    if (!lines.size) {
      let empty = contentRoot.querySelector<HTMLElement>(".log-empty");
      if (!empty) {
        empty = document.createElement("p");
        empty.className = "log-empty";
        contentRoot.append(empty);
      }
      empty.textContent = active() ? "Waiting for output…" : "No log output.";
    }
    if (follow) surface.scrollTop = surface.scrollHeight;
    else {
      const row = position.line ? rows.get(Number(position.line)) : undefined;
      surface.scrollTop = row
        ? surface.scrollTop + row.getBoundingClientRect().top
          - surface.getBoundingClientRect().top - position.delta
        : position.scroll;
    }
    updateControls();
  };
  // A burst of log messages updates the DOM once per animation frame. Existing
  // rows stay mounted so streaming does not rebuild the console or its selection.
  const scheduleRender = () => {
    if (frame !== undefined) return;
    frame = requestAnimationFrame(() => {
      frame = undefined;
      if (!disposed) render();
    });
  };
  const backfill = async () => {
    if (disposed) return;
    if (busy) {
      pendingBackfill = true;
      return;
    }
    busy = true;
    anchor = capture();
    try {
      await ctx.dispatch("model", "refresh");
    } catch (error) {
      status(
        error instanceof Error
          ? error.message
          : "Historical log refresh failed.",
      );
    } finally {
      busy = false;
      if (pendingBackfill) {
        pendingBackfill = false;
        void backfill();
      }
    }
  };
  const disconnect = () => {
    generation += 1;
    if (reconnect) clearTimeout(reconnect);
    reconnect = undefined;
    const previous = socket;
    socket = undefined;
    if (previous?.readyState === WebSocket.OPEN)
      previous.send(JSON.stringify({ id: "logs", type: "complete" }));
    previous?.close();
  };
  const retry = () => {
    if (disposed || paused || finished || reconnect) return;
    status("reconnecting…", "reconnecting");
    reconnect = setTimeout(
      () => {
        reconnect = undefined;
        void connect();
      },
      Math.min(30000, 1000 * 2 ** Math.min(attempts++, 5)),
    );
  };
  const connect = async () => {
    disconnect();
    const current = generation;
    if (disposed || paused || finished || !active()) return;
    try {
      await gitAuth.initialize(false);
      const headers = await gitAuth.getAuthHeaders();
      const token = headers.Authorization?.replace(/^Bearer /, "");
      if (!token) {
        status("Sign in to receive live logs.");
        return;
      }
      if (disposed || current !== generation) return;
      const ws = new WebSocket(
        `${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/_git/subscriptions`,
        "graphql-transport-ws",
      );
      socket = ws;
      status("connecting…", "connecting");
      ws.onopen = () =>
        ws.send(
          JSON.stringify({
            type: "connection_init",
            payload: { authToken: token },
          }),
        );
      ws.onmessage = (event) => {
        if (disposed || current !== generation || paused) return;
        let message: SubscriptionMessage;
        try {
          message = JSON.parse(event.data) as SubscriptionMessage;
        } catch {
          status("Invalid live log response.");
          return;
        }
        if (message.type === "connection_ack") {
          attempts = 0;
          status("live", "streaming");
          ws.send(
            JSON.stringify({
              id: "logs",
              type: "subscribe",
              payload: {
                query: stepLogsQuery,
                variables: {
                  repositoryId: identity.dataset.repository,
                  stepId: identity.dataset.logStep,
                },
              },
            }),
          );
          void backfill();
        } else if (message.type === "ping")
          ws.send(JSON.stringify({ type: "pong" }));
        else if (message.type === "next") {
          const line = message.payload?.data?.pipelineStepLogs;
          if (
            line &&
            Number.isInteger(line.lineNumber) &&
            typeof line.content === "string" &&
            typeof line.timestamp === "string" &&
            typeof line.stream === "string"
          ) {
            lines.set(line.lineNumber, line);
            pendingLines.add(line.lineNumber);
            scheduleRender();
          } else if (message.payload?.errors?.length) {
            status(
              "Live log access failed. Historical output remains available.",
            );
            disconnect();
          }
        } else if (message.type === "error") {
          status(
            "Live log subscription failed. Historical output remains available.",
          );
          disconnect();
        } else if (message.type === "complete") {
          disconnect();
          retry();
        }
      };
      ws.onclose = (event) => {
        if (current === generation) {
          if (event.code === 4401)
            status("Live log authentication failed. Sign in again.");
          else retry();
        }
      };
      ws.onerror = () => status("Live log connection failed; retrying.");
    } catch (error) {
      status(
        error instanceof Error ? error.message : "Could not connect live logs.",
      );
      retry();
    }
  };
  seed();
  render();
  ctx.on<MouseEvent>(
    ctx.root,
    "click",
    (event) => {
      anchor = capture();
      const button = (event.target as Element).closest<HTMLButtonElement>(
        "button",
      );
      if (button?.dataset.bmlMethod === "earlier") {
        earlier = true;
        paused = true;
        follow = false;
        disconnect();
        status(active() ? "paused" : "", "paused");
        updateControls();
      }
      if (button?.matches("[data-bml-ref=pause]")) {
        paused = !paused;
        if (paused) {
          disconnect();
          status("paused", "paused");
        } else {
          void backfill();
          void connect();
        }
        render(anchor);
      }
      if (button?.matches("[data-bml-ref=follow]")) {
        follow = true;
        render(anchor);
      }
    },
    true,
  );
  ctx.on<Event>(
    ctx.root,
    "scroll",
    (event) => {
      const surface = output();
      if (event.target === surface && surface) {
        follow =
          surface.scrollHeight - surface.scrollTop - surface.clientHeight < 24;
        updateControls();
      }
    },
    true,
  );
  ctx.onUpdate(() => {
    if (frame !== undefined) cancelAnimationFrame(frame);
    frame = undefined;
    if (earlier) {
      lines.clear();
      pendingLines.clear();
      earlier = false;
    }
    seed();
    render(anchor);
    status(statusText, statusState);
  });
  finished = !active();
  const timer = setInterval(() => {
    if (!finished && !active()) {
      finished = true;
      disconnect();
      status("", "closed");
      updateControls();
      void backfill();
    } else if (finished && active()) {
      finished = false;
      updateControls();
      void connect();
    }
  }, 1000);
  void connect();
  const unsubscribe = gitAuth.onAuthStateChanged((user) => {
    if (!user) {
      disconnect();
      status("Signed out. Live logs stopped.");
    }
  });
  ctx.onUnmount(() => {
    disposed = true;
    clearInterval(timer);
    if (frame !== undefined) cancelAnimationFrame(frame);
    disconnect();
    unsubscribe();
  });
}
