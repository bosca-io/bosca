-- Preserve email-verification links that were already IN FLIGHT when this release deployed.
--
-- Before this release the email-verify token lived in `principals.verification_token`, the link carried it,
-- and redemption looked the principal up by that token. This release moved the token to
-- `profile_attributes.verification_token` and redeems THERE (confirmVerification -> verifyByToken), and it
-- no longer clears the principal column on verify (that column is now exclusively the password-reset token).
-- Without this migration two things would go wrong for a link a user already holds:
--   1. it would resolve to nothing post-deploy (the new query reads the attribute token, NULL on legacy rows);
--   2. the orphaned token left in `principals.verification_token` would, once the principal verifies, be
--      accepted by resetPassword (which only checks `verified`) — a stale email-verify token doubling as a
--      password-reset token.
--
-- So MOVE the token, don't copy it: stamp it onto the email attribute (statement 1) and clear it from the
-- principal (statement 2). Scoping to `p.verified = false` is exact and safe: forgotPassword/resetPassword
-- only ever stamp `principals.verification_token` for VERIFIED principals, so an unverified principal's token
-- is necessarily an email-verify token, never an in-flight reset token. Both statements run in one Flyway
-- transaction; both are idempotent.

-- 1. Stamp the in-flight token onto the email attribute the link was delivered to — and ONLY when that
--    attribute is UNAMBIGUOUS. The token redeems EVERY attribute that carries it (verifyByToken is set-based)
--    and is a single secret mailed to a single address, so stamping it onto more than the proven address would
--    let one click verify an unproven, possibly squatted email (an unverified OAuth principal CAN log in and
--    attach a victim address). The tokens being migrated belong exclusively to pre-release PASSWORD sign-ups —
--    the only flow that ever stamped principals.verification_token and mailed a link. Such accounts are
--    unverified and therefore cannot log in to accumulate extra profiles, so they hold exactly ONE email
--    attribute: the sign-up address the link went to. We stamp that single-email case and nothing else. A
--    principal holding two or more email attributes is ambiguous (which address did the link reach?) and may be
--    a squat setup, so we stamp NONE of its attributes and leave the link to a fresh resend, which mails a new
--    link to the address actually being proven. The `count(*) = 1` guard makes this UPDATE touch at most one
--    row per principal by construction — the structural invariant the set-based redemption depends on.
update profile_attributes pa
set verification_token = p.verification_token
from profiles pr
         join principals p on p.id = pr.principal
where pa.profile = pr.id
  and pa.type_id = 'bosca.profiles.email'
  and pa.verified = false
  and p.verified = false
  and p.verification_token is not null
  and (
    select count(*)
    from profile_attributes pa2
             join profiles pr2 on pr2.id = pa2.profile
    where pr2.principal = p.id
      and pa2.type_id = 'bosca.profiles.email'
  ) = 1;

-- 2. Clear the migrated token from the principal so it can never later serve as a password-reset token. Only
--    touches unverified principals, so no in-flight reset token (which belongs to a verified principal) is lost.
update principals p
set verification_token = null
where p.verified = false
  and p.verification_token is not null;
