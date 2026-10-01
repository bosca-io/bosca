create type source_status as enum ('pending', 'importing', 'imported', 'failed', 'external');

alter table metadata
    add column source_status source_status;
