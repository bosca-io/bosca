-- Work Ops — V28: Spec hierarchy (parent-child composition)
--
-- Adds parent_spec_id for composing larger specs from smaller ones,
-- sort_order for child ordering, and denormalized rollup counts
-- (child_count, child_done_count) for fast UI rendering.

alter table workops.spec
    add column if not exists parent_spec_id   uuid references workops.spec(id) on delete set null,
    add column if not exists sort_order       int  not null default 0,
    add column if not exists child_count      int  not null default 0,
    add column if not exists child_done_count int  not null default 0;

create index spec_parent_idx on workops.spec(parent_spec_id) where parent_spec_id is not null and deleted_at is null;
