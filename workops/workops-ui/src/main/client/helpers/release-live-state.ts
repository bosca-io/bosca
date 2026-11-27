export type WorkOpsLiveSource = { appId: string; appVersion: string | null; projectId: string; projectLabel: string };
export type WorkOpsLivePoint = { appId: string; appVersion: string | null; sessionId: string; lat: number; lon: number; projectIds: string[]; lastSeen: number };
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);
export const workOpsLiveTtl = 15 * 60 * 1000;

/** Client arrival time is the expiry clock; subscription payloads contain no event timestamp. */
export class WorkOpsLiveSessions {
  private points = new Map<string, WorkOpsLivePoint>();
  constructor(readonly sources: readonly WorkOpsLiveSource[]) {}
  get rows(): readonly WorkOpsLivePoint[] { return [...this.points.values()]; }
  get size() { return this.points.size; }
  clear() { this.points.clear(); }
  accept(source: WorkOpsLiveSource, value: unknown, now: number): boolean {
    if (!this.sources.some(item => item.appId === source.appId && item.appVersion === source.appVersion && item.projectId === source.projectId) || !record(value)) return false;
    if (typeof value.sessionId !== "string" || !value.sessionId.trim() || typeof value.lat !== "number" || typeof value.lon !== "number"
      || !Number.isFinite(value.lat) || !Number.isFinite(value.lon) || value.lat < -90 || value.lat > 90 || value.lon < -180 || value.lon > 180
      || value.appId != null && value.appId !== source.appId || source.appVersion !== null && value.appVersion !== source.appVersion) return false;
    const version = typeof value.appVersion === "string" ? value.appVersion : null;
    const projectIds = [...new Set(this.sources.filter(item => item.appId === source.appId && (item.appVersion === null || item.appVersion === version)).map(item => item.projectId))];
    this.points.set(JSON.stringify([source.appId, value.sessionId]), { appId: source.appId, appVersion: version, sessionId: value.sessionId, lat: value.lat, lon: value.lon, projectIds, lastSeen: now });
    return true;
  }
  expire(now: number): boolean {
    let changed = false;
    for (const [key, point] of this.points) if (now - point.lastSeen > workOpsLiveTtl) { this.points.delete(key); changed = true; }
    return changed;
  }
}
