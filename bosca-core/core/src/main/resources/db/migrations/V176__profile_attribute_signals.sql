-- Personalization Signals (EXP-SPEC-3): cached, keyed personalization values computed at attribute
-- write-time by the recommendations write-time pipeline — each matching PersonalizationSignalDefinition's
-- JSONata is run against the attribute and the results cached here — then read back, keyed, by the
-- recommender's trainer + cohort builder via Trino. Opaque jsonb at this layer; the recommendations domain
-- owns the List<PersonalizationSignal { key, value }> shape.
alter table profile_attributes add column signals jsonb;
