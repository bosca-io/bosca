import type { BoardMoveRequest } from "@bosca/bml-design/board";

const request: BoardMoveRequest = { cardId: "proposal", columnId: "done", waitUntil: promise => { void promise; } };
request.waitUntil(Promise.resolve({ saved: true }));
// @ts-expect-error Save registration requires a promise.
request.waitUntil("saved");
// @ts-expect-error Destinations are stable string identifiers.
const invalid: BoardMoveRequest = { cardId: "proposal", columnId: 3, waitUntil: promise => { void promise; } };
void invalid;
