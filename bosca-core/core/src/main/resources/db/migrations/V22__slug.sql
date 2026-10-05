create extension if not exists unaccent;

create or replace function slugify(text)
    returns text AS
$$
declare
    value text := $1;
    slug  text := '';
    i     integer;
    c     text;
begin
    value := lower(value);
    value := unaccent(value);

    for i in 1..length(value)
        loop
            c := substring(value from i for 1);
            if c ~* '[a-z0-9]' then
                slug := slug || c;
            elsif c = ' ' or c = '-' then
                slug := slug || '-';
            else
                slug := slug || '-';
            end if;
        end loop;
    slug := trim(both '-' from slug);
    while slug like '%--%'
        loop
            slug := replace(slug, '--', '-');
        end loop;

    if slug = '' then
        slug := 'n-a';
    end if;

    return slug;
end;
$$ language plpgsql immutable;

create table slugs
(
    slug          varchar not null,
    metadata_id   uuid,
    collection_id uuid,
    profile_id    uuid,
    primary key (slug),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (profile_id) references profiles (id) on delete cascade
);

create sequence duplicate_slug_seq;
