-- Persist the originating web host alongside each verification / password-reset token so the
-- transactional auth emails link back to the host the user actually came from, in Bosca's
-- multi-host deployments (one backend serving several Studio hosts). The stored value is the raw
-- request origin; it is validated against the redirect allow-list when the email link is built.
alter table profile_attributes
    add column verification_origin varchar;

alter table principals
    add column verification_origin varchar;
