alter table workops.spec add column completion_version bigint not null default 0;

-- This token covers persisted completion inputs without changing the public optimistic version.
create function workops.invalidate_spec_completion(spec_ids uuid[]) returns void language plpgsql as $$
declare spec_id uuid;
begin
    for spec_id in select id from workops.spec where id = any(spec_ids) order by id loop
        update workops.spec set completion_version = completion_version + 1 where id = spec_id;
    end loop;
end;
$$;

create function workops.spec_completion_self() returns trigger language plpgsql as $$
begin
    if (to_jsonb(new) - 'completion_version') is distinct from (to_jsonb(old) - 'completion_version') then
        new.completion_version := old.completion_version + 1;
    end if;
    return new;
end;
$$;
create trigger spec_completion_self before update on workops.spec for each row execute function workops.spec_completion_self();

create function workops.spec_completion_requirement() returns trigger language plpgsql as $$
begin
    perform workops.invalidate_spec_completion(array[
        case when tg_op <> 'INSERT' and old.parent_type = 'spec' then old.parent_id end,
        case when tg_op <> 'DELETE' and new.parent_type = 'spec' then new.parent_id end
    ]);
    return null;
end;
$$;
create trigger spec_completion_requirement after insert or update or delete on workops.requirement
    for each row execute function workops.spec_completion_requirement();

create function workops.spec_completion_task() returns trigger language plpgsql as $$
declare changed_task_id uuid := case when tg_op = 'DELETE' then old.id else new.id end;
begin
    perform workops.invalidate_spec_completion(array(
        select r.parent_id from workops.requirement r where r.parent_type = 'spec' and r.task_id = changed_task_id
        union
        select g.spec_id from workops.spec_task_generation g where changed_task_id = any(g.generated_task_ids)
    ));
    return null;
end;
$$;
create trigger spec_completion_task after insert or update or delete on workops.task
    for each row execute function workops.spec_completion_task();

create function workops.spec_completion_reference() returns trigger language plpgsql as $$
begin
    perform workops.invalidate_spec_completion(array[
        case when tg_op <> 'INSERT' then old.spec_id end,
        case when tg_op <> 'DELETE' then new.spec_id end
    ]);
    return null;
end;
$$;
create trigger spec_completion_context after insert or update or delete on workops.spec_context
    for each row execute function workops.spec_completion_reference();
create trigger spec_completion_generation after insert or update or delete on workops.spec_task_generation
    for each row execute function workops.spec_completion_reference();
create trigger spec_completion_comment_insert_delete after insert or delete on workops.spec_comment
    for each row execute function workops.spec_completion_reference();
create trigger spec_completion_comment_update after update on workops.spec_comment
    for each row when ((old.spec_id, old.content, old.status, old.visibility, old.deleted, old.parent_id)
        is distinct from (new.spec_id, new.content, new.status, new.visibility, new.deleted, new.parent_id))
    execute function workops.spec_completion_reference();

create function workops.spec_completion_document() returns trigger language plpgsql as $$
declare changed_metadata_id uuid := case when tg_op = 'DELETE' then old.metadata_id else new.metadata_id end;
changed_document_version integer := case when tg_op = 'DELETE' then old.version else new.version end;
begin
    if exists (select 1 from public.metadata m where m.id = changed_metadata_id and m.version = changed_document_version) then
        perform workops.invalidate_spec_completion(array(
            select s.id from workops.spec s where s.metadata_id = changed_metadata_id
            union
            select r.parent_id from workops.requirement r where r.parent_type = 'spec' and r.metadata_id = changed_metadata_id
        ));
    end if;
    return null;
end;
$$;
create trigger spec_completion_document after insert or update or delete on public.documents
    for each row execute function workops.spec_completion_document();

create function workops.spec_completion_metadata() returns trigger language plpgsql as $$
declare changed_metadata_id uuid := case when tg_op = 'DELETE' then old.id else new.id end;
begin
    perform workops.invalidate_spec_completion(array(
        select s.id from workops.spec s where s.metadata_id = changed_metadata_id
        union
        select r.parent_id from workops.requirement r where r.parent_type = 'spec' and r.metadata_id = changed_metadata_id
    ));
    return null;
end;
$$;
create trigger spec_completion_metadata after update on public.metadata
    for each row when ((old.version, old.deleted) is distinct from (new.version, new.deleted))
    execute function workops.spec_completion_metadata();

create function workops.spec_completion_status() returns trigger language plpgsql as $$
begin
    perform workops.invalidate_spec_completion(array(
        select s.id from workops.spec s where s.status_id = new.id
        union
        select r.parent_id from workops.requirement r where r.parent_type = 'spec' and r.status_id = new.id
        union
        select r.parent_id from workops.requirement r join workops.task t on t.id = r.task_id
            where r.parent_type = 'spec' and t.status_id = new.id
        union
        select g.spec_id from workops.spec_task_generation g join workops.task t on t.id = any(g.generated_task_ids)
            where t.status_id = new.id
    ));
    return null;
end;
$$;
create trigger spec_completion_status after update on workops.status
    for each row when (old.category is distinct from new.category)
    execute function workops.spec_completion_status();

create trigger spec_completion_metadata_delete after delete on public.metadata
    for each row execute function workops.spec_completion_metadata();
