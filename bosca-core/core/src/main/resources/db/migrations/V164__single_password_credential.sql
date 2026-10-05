-- At most ONE password credential per principal.
--
-- A password is the account's single login secret, and every path that touches it — loginWithCredential,
-- updatePassword, updateIdentifier (and the email-change identifier-follow in reconcilePrincipalEmails),
-- forgotPassword/resetPassword — resolves "the" password credential. A second one makes "the" ambiguous: the
-- non-deterministic firstOrNull over an unordered `principal_credentials` could rename or reset the wrong row.
-- attachCredential rejects a second password in the service layer; this partial unique index is the schema
-- backstop so NO path (now or later) can create one.
--
-- Scope: only the two password hash types share the single login slot. Passkeys, OAuth identities and API
-- tokens are deliberately excluded — a principal may legitimately hold several of those.
--
-- Safe against existing data: the ONLY path that ever attached a SECOND password is the account-link flow
-- shipping in this same release (updatePassword updates the existing row in place; sign-up adds the first), so
-- pre-release principals already hold at most one password credential and the index builds without conflict.
create unique index ix_principal_single_password
    on principal_credentials (principal)
    where type in ('password', 'password_scrypt');
