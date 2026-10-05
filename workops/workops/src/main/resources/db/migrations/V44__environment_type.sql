-- Environment Types: a global, user-managed taxonomy of lifecycle stages (development / staging /
-- production / preview / ...). Replaces the hard-coded environment_kind enum: pipelines reference a
-- TYPE (portable across programs), and each program's environments instantiate a type. Resolution of
-- "which concrete environment" happens at run time within the run's program.

create table workops.environment_type (
    id            uuid    not null default gen_random_uuid() primary key,
    name          varchar not null unique,
    description   varchar,
    display_order integer not null default 0,
    version       bigint  not null default 0
);

-- Seed the catalog with the stages the kind enum hard-coded, preserving its vocabulary so existing
-- environments (and the bundled release relays, which name development/staging/production) keep working.
insert into workops.environment_type (name, description, display_order) values
    ('preview',     'On-demand preview environments (per branch / pull request)', 0),
    ('development', 'The first integration stage a release deploys to',           1),
    ('staging',     'Pre-production verification',                                2),
    ('production',  'Live, user-facing',                                          3);

alter table workops.environment add column type_id uuid references workops.environment_type(id);

update workops.environment e
set type_id = t.id
from workops.environment_type t
where t.name = e.kind::text;

alter table workops.environment alter column type_id set not null;
alter table workops.environment drop column kind;
drop type workops.environment_kind;
