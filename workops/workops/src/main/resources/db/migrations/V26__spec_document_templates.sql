-- Work Ops — Spec & Requirement document template seeds
--
-- Creates metadata entries for the Spec and Requirement document
-- templates. The template content defines the default Tiptap document
-- structure that new specs and requirements start with. Template
-- containers define the editable sections visible in the UI.
--
-- Templates are metadata items in the content system, referenced by
-- document_templates (metadata_id, version). The workops migration
-- owns these because the template structure is workops-domain-specific.

-- Stable well-known UUIDs for the two templates.
-- Spec template:        a0000000-0000-0000-0000-000000000001
-- Requirement template: a0000000-0000-0000-0000-000000000002

-- Create metadata entries for the templates.
insert into metadata (id, name, content_type, language_tag, workflow_state_id)
values
    ('a0000000-0000-0000-0000-000000000001', 'Spec Document Template', 'bosca/v-document-template', 'en', 'published'),
    ('a0000000-0000-0000-0000-000000000002', 'Requirement Document Template', 'bosca/v-document-template', 'en', 'published')
on conflict (id) do nothing;

-- Create document template definitions with default content.
insert into document_templates (metadata_id, version, configuration, schema, content, default_attributes)
values
    (
        'a0000000-0000-0000-0000-000000000001', 1, null, null,
        '{
            "document": {
                "type": "doc",
                "content": [
                    {"type": "heading", "attrs": {"level": 1}, "content": [{"type": "text", "text": "Overview"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Goals"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Non-Goals"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Technical Approach"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Dependencies"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Open Questions"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Risks"}]},
                    {"type": "paragraph", "content": []}
                ]
            }
        }'::jsonb,
        null
    ),
    (
        'a0000000-0000-0000-0000-000000000002', 1, null, null,
        '{
            "document": {
                "type": "doc",
                "content": [
                    {"type": "heading", "attrs": {"level": 1}, "content": [{"type": "text", "text": "Description"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Acceptance Criteria"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Constraints"}]},
                    {"type": "paragraph", "content": []},
                    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "References"}]},
                    {"type": "paragraph", "content": []}
                ]
            }
        }'::jsonb,
        null
    )
on conflict (metadata_id, version) do nothing;

-- Spec template containers — define the editable sections.
insert into document_template_containers (metadata_id, version, id, name, description, sort, type)
values
    ('a0000000-0000-0000-0000-000000000001', 1, 'overview',           'Overview',           'High-level summary of what this spec covers',          0, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'goals',              'Goals',              'What this spec aims to achieve',                       1, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'non_goals',          'Non-Goals',          'What is explicitly out of scope',                      2, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'technical_approach', 'Technical Approach', 'Implementation strategy and architecture decisions',    3, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'dependencies',       'Dependencies',       'Systems, services, or specs this depends on',          4, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'open_questions',     'Open Questions',     'Unresolved decisions or unknowns',                     5, 'standard'),
    ('a0000000-0000-0000-0000-000000000001', 1, 'risks',              'Risks',              'Known risks and mitigation strategies',                6, 'standard')
on conflict (metadata_id, version, id) do nothing;

-- Requirement template containers.
insert into document_template_containers (metadata_id, version, id, name, description, sort, type)
values
    ('a0000000-0000-0000-0000-000000000002', 1, 'description',         'Description',         'Detailed description of the requirement',        0, 'standard'),
    ('a0000000-0000-0000-0000-000000000002', 1, 'acceptance_criteria', 'Acceptance Criteria', 'Conditions that must be met for completion',     1, 'standard'),
    ('a0000000-0000-0000-0000-000000000002', 1, 'constraints',         'Constraints',         'Technical or business constraints',              2, 'standard'),
    ('a0000000-0000-0000-0000-000000000002', 1, 'references',          'References',          'Links to related specs, tasks, or resources',    3, 'standard')
on conflict (metadata_id, version, id) do nothing;

-- Spec template attributes — structured data fields extracted alongside content.
insert into document_template_attributes (metadata_id, version, key, name, description, type, ui, list, sort)
values
    ('a0000000-0000-0000-0000-000000000001', 1, 'status',       'Status',        'Current status of the spec',             'string',  'input',    false, 0),
    ('a0000000-0000-0000-0000-000000000001', 1, 'complexity',   'Complexity',    'Estimated complexity level',             'string',  'input',    false, 1),
    ('a0000000-0000-0000-0000-000000000001', 1, 'target_date',  'Target Date',   'Target completion date',                 'date',    'input',    false, 2),
    ('a0000000-0000-0000-0000-000000000001', 1, 'stakeholders', 'Stakeholders',  'Profiles involved in this spec',         'profile', 'profile',  true,  3)
on conflict (metadata_id, version, key) do nothing;

-- Requirement template attributes.
insert into document_template_attributes (metadata_id, version, key, name, description, type, ui, list, sort)
values
    ('a0000000-0000-0000-0000-000000000002', 1, 'testable',    'Testable',     'Whether this requirement is testable',    'string',  'input',    false, 0),
    ('a0000000-0000-0000-0000-000000000002', 1, 'complexity',  'Complexity',   'Estimated complexity level',              'string',  'input',    false, 1),
    ('a0000000-0000-0000-0000-000000000002', 1, 'linked_tasks','Linked Tasks', 'Tasks implementing this requirement',     'metadata','metadata', true,  2)
on conflict (metadata_id, version, key) do nothing;
