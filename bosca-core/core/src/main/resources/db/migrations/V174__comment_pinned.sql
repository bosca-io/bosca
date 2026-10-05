-- Pinned comments: moderators can pin a comment so it floats to the top of the
-- thread, and clients can request only pinned comments via `comments(pinned: true)`.
alter table metadata_comments
    add column pinned boolean not null default false;

-- Supports the `comments(pinned: true)` filter without scanning the whole thread.
create index metadata_comment_pinned_ix
    on metadata_comments (metadata_id, version, created desc)
    where pinned;
