-- WORKOPS-SPEC-22/23 — a dependency is one of two claims. The default (strict) claim: "my provider
-- must ship in the same release" — launching a release containing the consumer without its provider
-- fails. The relaxed claim (build_order_only = true): "when we DO ship together, build the provider
-- first" — the provider's absence from a release is fine.
alter table workops.dependency_declaration
    add column build_order_only boolean not null default false;
