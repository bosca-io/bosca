-- Restore the unique constraint dropped in V2 with a host-aware shape.
--
-- V2 had to drop `(gateway_id, path_pattern)` uniqueness because the
-- same path can legitimately exist on two routes scoped to different
-- hosts (e.g. `host=trino.example.com /**` and `host=preview.example.com /**`).
-- But dropping it entirely also defeated `GatewayRouteServiceImpl.remapConflict`,
-- which relies on Postgres surfacing a unique-violation to convert
-- an operator typo into a typed `GatewayConflictException`. Without
-- the index, duplicate routes can be persisted and the proxy
-- arbitrarily breaks ties on `sort_order` — invisible at config-load
-- time, visible only as wrong-upstream traffic in production.
--
-- The new index includes `hosts` so two routes with the same path
-- but different host scopes coexist, while two routes with the same
-- `(gateway, path, hosts)` triple still collide.

create unique index gateway_route_host_pattern_unique
    on gateway.route(gateway_id, path_pattern, hosts)
    where deleted_at is null;
