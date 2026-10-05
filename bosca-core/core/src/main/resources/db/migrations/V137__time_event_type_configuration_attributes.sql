-- Attribute definitions for time event types, following the same pattern as
-- data template attributes. Each row defines a field that events of a given
-- type can carry, along with its data type, UI widget, and display order.

create table time_event_type_attributes
(
    type_id           varchar           not null,
    key               varchar           not null,
    name              varchar           not null,
    description       varchar           not null,
    supplementary_key varchar,
    configuration     jsonb,
    type              attribute_type    not null,
    ui                attribute_ui_type not null,
    list              boolean           not null default false,
    sort              int               not null,
    tools             jsonb,
    primary key (type_id, key),
    foreign key (type_id) references time_event_types (id) on delete cascade
);

-- Seed attributes for built-in time event types

insert into time_event_type_attributes (type_id, key, name, description, type, ui, sort) values
    ('chapter', 'title', 'Title', 'Chapter title displayed in navigation', 'string', 'input', 0),
    ('caption', 'text', 'Text', 'Caption or subtitle text content', 'string', 'textarea', 0),
    ('caption', 'language', 'Language', 'Language code for the caption text (e.g. en, es)', 'string', 'input', 1),
    ('title', 'title', 'Title', 'Display title to show at this point', 'string', 'input', 0),
    ('action', 'actionType', 'Action Type', 'Type of action to trigger during playback', 'string', 'input', 0);
