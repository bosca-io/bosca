-- Add NOT NULL constraints to time_events timestamps
alter table time_events alter column created set not null;
alter table time_events alter column modified set not null;
