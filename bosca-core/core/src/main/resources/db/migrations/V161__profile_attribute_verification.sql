-- Per-email-attribute verification (complements principal-level verification).
--
--  * verified            — server-set proof flag (a client must NEVER be able to set this via
--                          ProfileAttributeInput; it is set only by the verification flow).
--  * verification_token  — the one-time token for the pending confirmation. INTERNAL ONLY: it is never
--                          exposed through GraphQL (no SDL field / no resolver) so it can't be read back.
--  * verification_source — how control was proven once verified (e.g. 'email', 'google', 'admin').
alter table profile_attributes
    add column verified            boolean not null default false,
    add column verification_token  varchar,
    add column verification_source varchar;

-- The confirmation flow looks the pending attribute up by its one-time token AND redeems it set-based
-- (verifyByToken: `update ... where verification_token = :token`). That redemption is only safe if a token
-- lives on AT MOST ONE row — otherwise one click verifies every attribute carrying the token, including a
-- co-attached squat. A UNIQUE (partial) index enforces that structural invariant at the schema so any future
-- write that would copy a token onto a second attribute fails LOUDLY instead of silently enabling a squat
-- (mirrors the UNIQUE `principals_verification_token` index the per-attribute token replaced). Partial on
-- `not null` so the many unverified rows with no pending token don't collide and stay out of the index.
create unique index ix_profile_attributes_verification_token
    on profile_attributes (verification_token)
    where verification_token is not null;
