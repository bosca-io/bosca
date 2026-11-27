import type { ClientContext } from "@bosca/bml";
import type { BoardMoveRequest } from "@bosca/bml-design/board";

/** Terminal WorkOps transitions await the domain resolution dialog; shared controls retain the move. */
export function workOpsBoardMoves(ctx: ClientContext, actions: {
  move(cardId: string, columnId: string, version: number): Promise<unknown>;
  prepare(cardId: string, columnId: string, version: number): Promise<unknown>;
}) {
  let waiting: { sequence: string; resolve(): void; reject(error: Error): void } | undefined;
  const root = () => ctx.root.matches("[data-workops-board-view]") ? ctx.root
    : ctx.root.querySelector<HTMLElement>("[data-workops-board-view]");
  ctx.on<CustomEvent<BoardMoveRequest>>(ctx.root, "boardmove", event => {
    const card = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-workops-board-card]") : null;
    if (!card || card.closest("[data-workops-board-view]") !== root()) return;
    const request = event.detail, version = Number(card.dataset.workopsCardVersion);
    event.stopPropagation();
    if (!Number.isSafeInteger(version) || version < 0) {
      request.waitUntil(Promise.reject(new Error("Reload this task before moving it."))); return;
    }
    if (!(card.dataset.workopsClosingColumns ?? "").split(" ").includes(request.columnId)) {
      request.waitUntil(actions.move(request.cardId, request.columnId, version)); return;
    }
    if (waiting) { request.waitUntil(Promise.reject(new Error("Finish the current closing move first."))); return; }
    request.waitUntil(new Promise<void>((resolve, reject) => {
      const pending = { sequence: root()?.dataset.workopsMoveSequence ?? "", resolve, reject };
      waiting = pending;
      void actions.prepare(request.cardId, request.columnId, version).catch(error => {
        if (waiting !== pending) return;
        waiting = undefined;
        pending.reject(error instanceof Error ? error : new Error("The closing move could not be prepared."));
      });
    }));
  });
  ctx.onUpdate(() => {
    const board = root();
    if (!waiting || !board || board.dataset.workopsMoveSequence === waiting.sequence) return;
    const pending = waiting; waiting = undefined;
    if (board.dataset.workopsMoveResult === "saved") pending.resolve();
    else pending.reject(new Error("Closing move canceled. The task has not moved."));
  });
  ctx.onUnmount(() => {
    const pending = waiting; waiting = undefined;
    pending?.reject(new Error("The board was closed before the move completed."));
  });
}
