-- Trino-readable projection of the cached Personalization Signals (EXP-SPEC-3). Trino's postgresql
-- connector cannot unnest the `profile_attributes.signals` jsonb, so — mirroring the `metadata_embedding`
-- view (V175) for pgvector — this view does the unnest in Postgres and exposes each signal as plain columns:
-- one row per (profile, signal key). The value is its JSON text form (a scalar's literal like "25-34"/42/true,
-- or a JSON array/object literal) so Trino reads it as varchar and the trainer parses it per the definition's
-- value_type. The `recommender-user-signals` analytics query reads this view and joins the signal definitions
-- for the use_as_feature filter + priority.
create view profile_attribute_signals as
select
    pa.profile                     as user_id,
    sig.elem ->> 'key'             as signal_key,
    (sig.elem -> 'value')::text    as signal_value,
    pa.priority                    as attribute_priority
from profile_attributes pa
cross join lateral jsonb_array_elements(pa.signals) as sig(elem)
where pa.signals is not null;
