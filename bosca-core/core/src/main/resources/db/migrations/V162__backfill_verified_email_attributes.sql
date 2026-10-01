-- Backfill attribute-level email verification so the now-trust-gated email→account lookup
-- (getPrincipalByEmail matches only `verified = true` email attributes from this release) keeps resolving
-- accounts that PROVED their email before the `verified` column existed.
--
-- "Proven" requires BOTH:
--   * the principal is verified (`p.verified = true`) — i.e. it actually completed email confirmation. An
--     account that signed up but never confirmed has an identifier equal to its email yet has proven nothing,
--     so it must NOT be promoted (doing so would strand it: its email attribute would read verified while the
--     principal stays unverified, so it could neither log in nor be re-sent a link). This also keeps V162 in
--     step with V160, which registers the uniqueness backstop only for `p.verified = true` principals.
--   * a password/scrypt credential whose identifier equals the email. The login identifier IS the address the
--     (confirmed) verification link was delivered to, and it is only mutable through the verified
--     updateIdentifier path. Only password/scrypt credentials carry an email-shaped identifier; an OAuth
--     provider-id, API token, or passkey id can never satisfy `identifier = email`. The explicit
--     `pc.type in ('password','password_scrypt')` filter makes that a guarantee rather than an incidental
--     property of current identifier shapes, and a squat attribute (a victim's address on an attacker's
--     profile) is never promoted because the attacker's own login identifier is its own address, not the victim's.
--
-- OAuth-only emails (an address that was never a login identifier) are deliberately left unverified rather
-- than promoted on a weaker signal; they become verified on the next provider sign-in that asserts the
-- email, via the going-forward marking path. Idempotent: the `pa.verified = false` guard makes re-runs no-ops.
update profile_attributes pa
set verified            = true,
    verification_source = 'backfill'
from profiles pr
         join principals p on p.id = pr.principal
         join principal_credentials pc on pc.principal = pr.principal
where pa.profile = pr.id
  and pa.type_id = 'bosca.profiles.email'
  and p.verified = true
  and pc.type in ('password', 'password_scrypt')
  and lower(trim(pa.attributes ->> 'email')) = lower(trim(pc.attributes ->> 'identifier'))
  and pa.verified = false;
