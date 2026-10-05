-- Track where the most recent login request using each credential came from, distinct from the immutable
-- `originator` (the original, creation-time source). Updated on each interactive login that supplies an
-- originator; initialized to the original at creation. Nullable.
alter table principal_credentials
    add column last_originator varchar;
