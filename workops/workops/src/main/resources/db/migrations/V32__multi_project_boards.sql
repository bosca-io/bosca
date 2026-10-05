-- Work Ops — Multi-project boards (BOSCAWEB-SPEC-7)
--
-- Allows boards to span a specific subset of projects via a join
-- table. The existing single-parent constraint is relaxed from
-- "exactly one" to "at most one" so that standalone multi-project
-- boards (no single parent) are valid. A portfolio_id column is
-- added as a third parent option for organizational grouping.

-- ---------------------------------------------------------------
-- 1. Add portfolio_id to board
-- ---------------------------------------------------------------

alter table workops.board
    add column portfolio_id uuid references workops.portfolio(id) on delete cascade;

create index board_portfolio_idx on workops.board(portfolio_id) where portfolio_id is not null;

-- ---------------------------------------------------------------
-- 2. Relax the parent constraint: at most one of project_id,
--    program_id, portfolio_id may be set. All three NULL means
--    the board's scope comes entirely from board_project rows.
-- ---------------------------------------------------------------

alter table workops.board drop constraint board_exactly_one_parent;

alter table workops.board add constraint board_at_most_one_parent check (
    (project_id is not null)::int
  + (program_id is not null)::int
  + (portfolio_id is not null)::int
  <= 1
);

-- ---------------------------------------------------------------
-- 3. Join table: which projects does this board span?
--    For legacy single-project boards, a trigger backfills this
--    automatically so queries only need one code path.
-- ---------------------------------------------------------------

create table workops.board_project (
    board_id    uuid not null references workops.board(id) on delete cascade,
    project_id  uuid not null references workops.project(id) on delete cascade,
    added_at    timestamptz not null default now(),
    primary key (board_id, project_id)
);

create index board_project_project_idx on workops.board_project(project_id);

-- ---------------------------------------------------------------
-- 4. Backfill: every existing project-scoped board gets a
--    corresponding board_project row so downstream queries are
--    uniform.
-- ---------------------------------------------------------------

insert into workops.board_project (board_id, project_id)
select id, project_id
from workops.board
where project_id is not null;
