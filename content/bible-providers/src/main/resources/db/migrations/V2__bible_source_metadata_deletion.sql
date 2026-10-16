alter table bible_providers.sources
    add constraint sources_metadata
    foreign key (metadata_id) references public.metadata (id) on delete cascade;
