-- Work Ops — Phase 15 (specs/workops/plan.md §15, R30)
--
-- Inbound email — inboxes per project, audit rows per processed
-- message, and the outbound Message-ID registry that lets the
-- inbound side thread `In-Reply-To` headers back to the right
-- task or comment.

create table workops.email_inbox (
    id                      uuid    not null default gen_random_uuid() primary key,
    name                    varchar not null,
    description             varchar,
    kind                    varchar not null,
    address                 varchar not null,
    project_id              uuid    not null references workops.project(id) on delete cascade,
    default_task_type_id    uuid    references workops.task_type(id) on delete set null,
    default_priority_id     uuid    references workops.priority(id) on delete set null,
    enabled                 boolean not null default true,
    hmac_secret             varchar,
    version                 bigint  not null default 0,
    unique (kind, address)
);

create index email_inbox_project_idx on workops.email_inbox(project_id);
create index email_inbox_enabled_idx on workops.email_inbox(enabled) where enabled = true;

create table workops.inbound_email (
    id                  uuid    not null default gen_random_uuid() primary key,
    inbox_id            uuid    not null references workops.email_inbox(id) on delete cascade,
    message_id          varchar,
    in_reply_to         varchar,
    from_address        varchar not null,
    subject             varchar,
    outcome             varchar not null,
    task_id             uuid    references workops.task(id) on delete set null,
    comment_id          bigint,
    error_message       varchar,
    received_at         timestamptz not null default now(),
    -- The raw MIME blob; production deployments wrap this through
    -- core-storage's encryption-at-rest path. Phase 15 stores
    -- in-table for the plumbing test fixture.
    raw_mime            text
);

create index inbound_email_inbox_idx on workops.inbound_email(inbox_id, received_at desc);
create index inbound_email_message_idx on workops.inbound_email(message_id) where message_id is not null;
create index inbound_email_in_reply_to_idx on workops.inbound_email(in_reply_to) where in_reply_to is not null;

-- Outbound Message-ID registry; one row per notification email sent.
create table workops.outbound_message_id (
    message_id          varchar not null primary key,
    task_id             uuid    not null references workops.task(id) on delete cascade,
    comment_id          bigint,
    created_at          timestamptz not null default now()
);

create index outbound_message_id_task_idx on workops.outbound_message_id(task_id);
