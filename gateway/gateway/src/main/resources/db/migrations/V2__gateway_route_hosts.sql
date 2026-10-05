-- Host-based routing for the gateway proxy.
--
-- The original schema routed purely by `path_pattern`. Operators who
-- want different upstreams on different hostnames (e.g.
-- `trino.example.com` vs `preview.example.com`) on a single proxy
-- instance could not express that — every route matched on every host.
--
-- `hosts` is an array of host patterns. Each element is either:
--   * a literal host: `api.bosca.io`
--   * a leading-`*.` wildcard: `*.bosca.io` matches one label segment
-- Empty array means "match any host", which preserves the prior
-- behaviour for existing rows.
--
-- Matching precedence (resolved in the Rust proxy):
--   host-literal > host-wildcard > host-any, then longest-prefix wins,
--   then lower `sort_order` wins.

alter table gateway.route
    add column hosts varchar[] not null default '{}';

-- The original uniqueness was `(gateway_id, path_pattern)` — which is
-- now too strict. With host scoping, the same `(gateway_id, path)` can
-- legitimately exist twice for two different hosts. Drop the old
-- index; rely on operator UX (the routes page surfaces the host) plus
-- application-level remap of unique-violations to convey collisions.
drop index if exists gateway.gateway_route_pattern_unique;
