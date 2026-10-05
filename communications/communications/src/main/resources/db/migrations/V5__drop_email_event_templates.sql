-- Drops the legacy BX event -> template mapping. Events don't map to email templates:
-- an event is a trigger; template selection is pipeline composition (the Send Email
-- Template node). Nothing ever consumed these rows at send time -- the table only ever
-- backed an admin CRUD surface, which is removed with it.

DROP TABLE IF EXISTS communications.email_event_templates;
