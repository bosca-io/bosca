-- Adds a per-principal token generation counter used to mass-invalidate
-- outstanding JWTs without relying on a server-side revocation list.
--
-- Every JWT minted by `createJwtToken` embeds the current
-- `token_version` as a `tver` claim. On request verification the claim
-- is compared against the principal's current value; a mismatch rejects
-- the token. Flows that must invalidate outstanding sessions
-- (password change, reset, identifier change, admin lock, etc.) call
-- `incrementTokenVersion`, which bumps this counter and — paired with
-- an unconditional delete of `principal_refresh_tokens` for the same
-- principal — renders every previously-issued JWT and refresh token
-- unusable.
--
-- Backwards compatibility: existing rows start at 0, and legacy JWTs
-- minted before this migration carry no `tver` claim. The verifier
-- treats a missing claim as version 0, so pre-existing tokens remain
-- valid until their owner performs an action that bumps the counter.
alter table principals
    add column token_version integer not null default 0;
