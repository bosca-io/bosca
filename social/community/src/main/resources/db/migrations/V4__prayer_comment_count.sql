alter table prayers add column comment_count int not null default 0;

update prayers p set comment_count = (
    select count(*) from prayer_comments pc
    where pc.prayer_id = p.id and not pc.deleted
);

create index idx_prayer_comments_prayer_id on prayer_comments(prayer_id, created desc) where not deleted;
