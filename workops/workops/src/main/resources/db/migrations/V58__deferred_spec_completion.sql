-- Contributing rows finish their writes before acquiring specification completion locks.
-- Deferred triggers still advance the revision in the same atomic commit.

drop trigger spec_completion_requirement on workops.requirement;
create constraint trigger spec_completion_requirement after insert or update or delete on workops.requirement
    deferrable initially deferred
    for each row execute function workops.spec_completion_requirement();

drop trigger spec_completion_task on workops.task;
create constraint trigger spec_completion_task after insert or update or delete on workops.task
    deferrable initially deferred
    for each row execute function workops.spec_completion_task();

drop trigger spec_completion_context on workops.spec_context;
create constraint trigger spec_completion_context after insert or update or delete on workops.spec_context
    deferrable initially deferred
    for each row execute function workops.spec_completion_reference();

drop trigger spec_completion_generation on workops.spec_task_generation;
create constraint trigger spec_completion_generation after insert or update or delete on workops.spec_task_generation
    deferrable initially deferred
    for each row execute function workops.spec_completion_reference();

drop trigger spec_completion_comment_insert_delete on workops.spec_comment;
create constraint trigger spec_completion_comment_insert_delete after insert or delete on workops.spec_comment
    deferrable initially deferred
    for each row execute function workops.spec_completion_reference();

drop trigger spec_completion_comment_update on workops.spec_comment;
create constraint trigger spec_completion_comment_update after update on workops.spec_comment
    deferrable initially deferred
    for each row when ((old.spec_id, old.content, old.status, old.visibility, old.deleted, old.parent_id)
        is distinct from (new.spec_id, new.content, new.status, new.visibility, new.deleted, new.parent_id))
    execute function workops.spec_completion_reference();

drop trigger spec_completion_document on public.documents;
create constraint trigger spec_completion_document after insert or update or delete on public.documents
    deferrable initially deferred
    for each row execute function workops.spec_completion_document();

drop trigger spec_completion_metadata on public.metadata;
create constraint trigger spec_completion_metadata after update on public.metadata
    deferrable initially deferred
    for each row when ((old.version, old.deleted) is distinct from (new.version, new.deleted))
    execute function workops.spec_completion_metadata();

drop trigger spec_completion_status on workops.status;
create constraint trigger spec_completion_status after update on workops.status
    deferrable initially deferred
    for each row when (old.category is distinct from new.category)
    execute function workops.spec_completion_status();

drop trigger spec_completion_metadata_delete on public.metadata;
create constraint trigger spec_completion_metadata_delete after delete on public.metadata
    deferrable initially deferred
    for each row execute function workops.spec_completion_metadata();

create or replace function workops.spec_completion_status() returns trigger language plpgsql as $$
begin
    perform workops.invalidate_spec_completion(array(
        select s.id from workops.spec s where s.status_id = new.id
        union
        select s.parent_spec_id from workops.spec s where s.status_id = new.id and s.deleted_at is null
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

-- Child completion uses current rows; hierarchy and status changes invalidate the parent check.
create function workops.spec_completion_child() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' and (old.parent_spec_id, old.status_id, old.deleted_at)
        is not distinct from (new.parent_spec_id, new.status_id, new.deleted_at) then
        return null;
    end if;
    perform workops.invalidate_spec_completion(array[
        case when tg_op <> 'INSERT' then old.parent_spec_id end,
        case when tg_op <> 'DELETE' then new.parent_spec_id end
    ]);
    return null;
end;
$$;
create constraint trigger spec_completion_child after insert or update or delete on workops.spec
    deferrable initially deferred
    for each row execute function workops.spec_completion_child();
