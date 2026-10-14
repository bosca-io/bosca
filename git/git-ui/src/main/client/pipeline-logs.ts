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
  const status = (text: string) => {
    statusText = text;
    const target = ctx.ref<HTMLElement>("logError");
    if (target) target.textContent = text;
  };
  const active = () =>
    ["QUEUED", "RUNNING"].includes(
      ctx.root.closest<HTMLElement>("[data-step-status]")?.dataset.stepStatus ??
        "",
    );
  const capture = () => {
    const surface = output();
    const top = surface?.getBoundingClientRect().top ?? 0;
    const row = Array.from(
      surface?.querySelectorAll<HTMLElement>("[data-line]") ?? [],
    ).find((item) => item.getBoundingClientRect().bottom > top);
    return {
      line: row?.dataset.line,
      delta: row ? row.getBoundingClientRect().top - top : 0,
      scroll: surface?.scrollTop ?? 0,
    };
  };
  let anchor = capture();
  let earlier = false;
  const seed = () => {
    for (const row of ctx.root.querySelectorAll<HTMLElement>(
      ".log-line[data-line]",
    )) {
      const number = Number(row.dataset.line);
      lines.set(number, {
        lineNumber: number,
        timestamp: row.querySelector("time")?.dateTime ?? "",
        content: row.querySelector("code")?.textContent ?? "",
        stream: row.dataset.stream ?? "STDOUT",
      });
    }
  };
  const render = (position = capture()) => {
    const surface = output();
    if (!surface) return;
    const ordered = Array.from(lines.values()).sort(
      (a, b) => a.lineNumber - b.lineNumber,
    );
    if (ordered.length > 20000) {
      for (const line of ordered.splice(0, ordered.length - 20000))
        lines.delete(line.lineNumber);
    }
    surface.replaceChildren(
      ...ordered.map((line) => {
        const row = document.createElement("div");
        row.className = "log-line";
        row.dataset.line = String(line.lineNumber);
        row.dataset.stream = line.stream;
        const number = document.createElement("span");
        number.className = "muted";
        number.textContent = String(line.lineNumber);
        const time = document.createElement("time");
        time.dateTime = line.timestamp;
        time.textContent = line.timestamp;
        const content = document.createElement("code");
        content.textContent = line.content;
        row.append(number, time, content);
        return row;
      }),
    );
    if (!ordered.length) {
      const waiting = document.createElement("p");
      waiting.textContent = "Waiting for output.";
      surface.append(waiting);
    }
    if (follow) surface.scrollTop = surface.scrollHeight;
    else {
      const row = Array.from(
        surface.querySelectorAll<HTMLElement>("[data-line]"),
      ).find((item) => item.dataset.line === position.line);
      surface.scrollTop = row
        ? surface.scrollTop +
          row.getBoundingClientRect().top -
          surface.getBoundingClientRect().top -
          position.delta
        : position.scroll;
    }
    const pause = ctx.ref<HTMLButtonElement>("pause"),
      following = ctx.ref<HTMLButtonElement>("follow");
    if (pause) {
      pause.setAttribute("aria-pressed", String(paused));
      pause.textContent = paused ? "Resume live logs" : "Pause live logs";
    }
    following?.setAttribute("aria-pressed", String(follow));
  };
  const backfill = async () => {
    if (busy || disposed) return;
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
    status("Reconnecting live logs…");
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
      await gitAuth.initialize();
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
      status("Connecting live logs…");
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
          status("Live logs connected.");
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
            render();
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
        disconnect();
        status("Live logs paused while browsing history.");
      }
      if (button?.matches("[data-bml-ref=pause]")) {
        paused = !paused;
        if (paused) {
          disconnect();
          status("Live logs paused.");
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
        ctx
          .ref<HTMLButtonElement>("follow")
          ?.setAttribute("aria-pressed", String(follow));
      }
    },
    true,
  );
  ctx.onUpdate(() => {
    if (earlier) {
      lines.clear();
      earlier = false;
    }
    seed();
    render(anchor);
    status(statusText);
  });
  const timer = setInterval(() => {
    if (!finished && !active()) {
      finished = true;
      disconnect();
      status("Step finished.");
      void backfill();
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
    disconnect();
    unsubscribe();
  });
}
