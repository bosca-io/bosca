-- Seed a default "Create Task" form for Work Ops.
-- The form uses workops-select controls for project, task type, and
-- priority, plus plain text fields for summary and description.
-- Admins can customize this form in the Form Builder; the key
-- 'workops.create-task' is referenced by the admin UI.

insert into form_schemas (
    id, type, key, name, description,
    schema, ui_schema, configuration,
    version, public, published, created, modified
) values (
    'a1b2c3d4-0001-4000-8000-000000000001',
    'work_ops',
    'workops.create-task',
    'Create Task',
    'Default form for creating Work Ops tasks. Customize this form in the Form Builder.',
    '{
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "properties": {
        "projectId":           { "type": "string" },
        "taskTypeId":          { "type": "string" },
        "priorityId":          { "type": "string" },
        "summary":             { "type": "string", "minLength": 1, "maxLength": 255 },
        "descriptionMarkdown": { "type": "string" }
      },
      "required": ["projectId", "summary"]
    }'::jsonb,
    '{
      "version": 1,
      "layout": [
        {
          "type": "field",
          "property": "projectId",
          "control": "workops-select",
          "label": "Project",
          "workOpsEntity": "projects",
          "placeholder": "Select a project…"
        },
        {
          "type": "row",
          "children": [
            {
              "type": "field",
              "property": "taskTypeId",
              "control": "workops-select",
              "label": "Task Type",
              "workOpsEntity": "taskTypes",
              "col": 6
            },
            {
              "type": "field",
              "property": "priorityId",
              "control": "workops-select",
              "label": "Priority",
              "workOpsEntity": "priorities",
              "col": 6
            }
          ]
        },
        {
          "type": "field",
          "property": "summary",
          "control": "text-input",
          "label": "Summary",
          "placeholder": "What needs to be done?"
        },
        {
          "type": "field",
          "property": "descriptionMarkdown",
          "control": "textarea",
          "label": "Description",
          "placeholder": "Describe the task (Markdown supported)…",
          "rows": 5
        }
      ]
    }'::jsonb,
    '{
      "workOps": {
        "fieldMappings": {
          "summary": "summary",
          "descriptionMarkdown": "descriptionMarkdown"
        }
      }
    }'::jsonb,
    1,
    false,
    true,
    now(),
    now()
) on conflict (key) do nothing;
