-- Rename to the coherent "co-engagement" model. The behavioral item-to-item signal was named
-- RELATED_CONTENT / "related items" — a near-synonym of content *similarity* that inverted most people's
-- intuition. It is now CO_ENGAGEMENT ("people who engaged with this also engaged with…"), served as the
-- `coEngaged` surface. The trained-model type is renamed ML_MODEL → PERSONALIZED to name the signal
-- rather than the technology.
--
-- Enum RENAME VALUE and table/column/index RENAME are all metadata-only — existing rows are preserved
-- untouched. This migration runs in the `recommendations` schema (Flyway `.schemas`), so unqualified
-- names resolve there.

-- Strategy-type enum labels (values are stored lowercase; EnumMapper lowercases on bind).
alter type recommendations.strategy_type rename value 'related_content' to 'co_engagement';
alter type recommendations.strategy_type rename value 'ml_model' to 'personalized';

-- Materialized co-engagement edges (was related_items) + its far-endpoint column.
alter table recommendations.related_items rename to co_engagements;
alter table recommendations.co_engagements rename column related_metadata_id to co_engaged_metadata_id;
alter index recommendations.idx_related_items_source rename to idx_co_engagements_source;
alter index recommendations.idx_related_items_strategy rename to idx_co_engagements_strategy;

-- Re-label the seeded strategy on existing installs (fresh installs get the new name from the installer).
-- Keyed on the old name so it never touches an admin-renamed strategy, and avoids referencing the enum
-- value renamed above in the same transaction.
update recommendations.strategies
   set name = 'People also viewed',
       description = 'Behavioral co-engagement ("people who engaged with this also engaged with…") powering the item-context surfaces, refreshed daily.'
 where name = 'Related Content';
