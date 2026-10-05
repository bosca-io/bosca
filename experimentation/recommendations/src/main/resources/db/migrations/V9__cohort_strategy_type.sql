-- "People like you" cohort co-engagement strategy type (EXP-SPEC-3, Phase 2). Kept in its own migration
-- (like V2's ml_model add) so the new enum label is committed before any later migration or seed uses it.
-- Stored lowercase; the EnumMapper lowercases COHORT_CO_ENGAGEMENT on bind.
alter type recommendations.strategy_type add value 'cohort_co_engagement';
