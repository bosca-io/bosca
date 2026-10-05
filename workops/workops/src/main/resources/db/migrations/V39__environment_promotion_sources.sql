-- V39: Environment promotion sources become many-to-many (WORKOPS-SPEC-22).
--
-- An environment can be promoted from MULTIPLE sources (a small DAG) rather than a single linear
-- predecessor. Move the existing single `promotion_source_id` edges into a join table and drop the column.

create table workops.environment_promotion_source (
    environment_id        uuid not null references workops.environment(id) on delete cascade,
    source_environment_id uuid not null references workops.environment(id) on delete cascade,
    primary key (environment_id, source_environment_id)
);

insert into workops.environment_promotion_source (environment_id, source_environment_id)
    select id, promotion_source_id from workops.environment where promotion_source_id is not null;

alter table workops.environment drop column promotion_source_id;

create index environment_promotion_source_src_idx
    on workops.environment_promotion_source(source_environment_id);
