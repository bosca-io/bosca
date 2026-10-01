-- Record where each credential came from: the caller-supplied originator of the login/signup request that
-- created the credential (email, oauth2, or any other type). Stamped once at credential-creation time and
-- never updated. Nullable: credentials created before this column, or without a supplied originator, have none.
alter table principal_credentials
    add column originator varchar;
