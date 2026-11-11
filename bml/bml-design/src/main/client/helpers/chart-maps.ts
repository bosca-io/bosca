import { getMap, registerMap } from "echarts";

const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);
const objects = (value: unknown): Record<string, unknown>[] => Array.isArray(value) ? value.filter(record) : record(value) ? [value] : [];

/** Content-addressed names let different charts use the same alias for different boundaries. */
function mapKey(source: string): string {
  let first = 2166136261, second = 5381;
  for (let index = 0; index < source.length; index++) {
    const code = source.charCodeAt(index);
    first = Math.imul(first ^ code, 16777619); second = Math.imul(second, 33) ^ code;
  }
  return `bml-map-${(first >>> 0).toString(16)}-${(second >>> 0).toString(16)}`;
}

/** Register supplied GeoJSON once per content and bind native map/geo references to private names. */
export function bindChartMaps(option: Record<string, unknown>, maps: unknown): Record<string, unknown> {
  if (!record(maps)) throw new Error("Chart maps must be an object of named GeoJSON boundaries.");
  const aliases = new Map<string, string>();
  const bind = (value: unknown): unknown => {
    if (!record(value) || typeof value.map !== "string") return value;
    const alias = value.map;
    let name = aliases.get(alias);
    if (!name) {
      const geometry = maps[alias];
      if (!record(geometry) || geometry.type !== "FeatureCollection" || !Array.isArray(geometry.features))
        throw new Error(`Supply a GeoJSON FeatureCollection for map "${alias}" in maps.`);
      const source = JSON.stringify(geometry), key = mapKey(source);
      name = key;
      // Compare content as well as the hash so a collision cannot replace another chart's geometry.
      for (let suffix = 1; getMap(name) && JSON.stringify(getMap(name).geoJSON) !== source; suffix++) name = `${key}-${suffix}`;
      if (!getMap(name)) registerMap(name, geometry as unknown as Parameters<typeof registerMap>[1]);
      aliases.set(alias, name);
    }
    return { ...value, map: name };
  };
  const components = (value: unknown): unknown => Array.isArray(value) ? value.map(bind) : bind(value);
  return { ...option, ...(option.series !== undefined ? { series: components(option.series) } : {}),
    ...(option.geo !== undefined ? { geo: components(option.geo) } : {}) };
}

/** Native map and geo components that own a pannable or zoomable viewport. */
export function mapNavigation(option: Record<string, unknown>) {
  const targets: { kind: "series" | "geo"; index: number; pan: boolean; zoom: boolean }[] = [];
  for (const kind of ["series", "geo"] as const) objects(option[kind]).forEach((item, index) => {
    if (typeof item.map !== "string" || kind === "series" && (item.type !== "map" || item.geoIndex !== undefined || item.geoId !== undefined)) return;
    targets.push({ kind, index, pan: item.roam === true || item.roam === "move", zoom: item.roam === true || item.roam === "scale" });
  });
  return targets;
}
