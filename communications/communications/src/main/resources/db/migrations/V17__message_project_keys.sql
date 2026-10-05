-- Preserve registry metadata and version pins for the renamed first-party project. If an
-- installation already registered the new key, retain its explicit values and fill only gaps.
update communications.bml_message_projects target
set description = coalesce(target.description, legacy.description),
    repository_id = coalesce(target.repository_id, legacy.repository_id),
    pinned_version = coalesce(target.pinned_version, legacy.pinned_version),
    modified = greatest(target.modified, legacy.modified)
from communications.bml_message_projects legacy
where target.key = 'bosca-messages'
  and legacy.key = 'bosca-emails';

delete from communications.bml_message_projects legacy
where legacy.key = 'bosca-emails'
  and exists (
      select 1
      from communications.bml_message_projects target
      where target.key = 'bosca-messages'
  );

update communications.bml_message_projects
set key = 'bosca-messages',
    modified = now()
where key = 'bosca-emails';

-- Communications can be migrated independently in tests and isolated services, so the platform
-- configuration table is optional here. In a full installation, carry the existing branding row
-- forward so Studio and send-time rendering resolve the same configuration after the rename.
do $$
begin
    if to_regclass('public.configurations') is not null then
        update public.configurations
        set key = 'bosca.messages.branding'
        where key = 'bosca.emails.branding'
          and not exists (
              select 1
              from public.configurations
              where key = 'bosca.messages.branding'
          );
    end if;
end
$$;
