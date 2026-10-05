-- PostgreSQL enum labels are lowercase throughout Bosca. Rename the existing
-- values in place so upgrading does not rewrite or rebuild the tables and
-- indexes that use these types.

ALTER TYPE communications.channel RENAME VALUE 'EMAIL' TO 'email';
ALTER TYPE communications.channel RENAME VALUE 'PUSH' TO 'push';

ALTER TYPE communications.delivery_status_type RENAME VALUE 'PENDING' TO 'pending';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'SENT' TO 'sent';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'DELIVERED' TO 'delivered';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'DEFERRED' TO 'deferred';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'BOUNCED' TO 'bounced';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'DROPPED' TO 'dropped';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'OPENED' TO 'opened';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'CLICKED' TO 'clicked';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'SPAM_REPORT' TO 'spam_report';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'UNSUBSCRIBED' TO 'unsubscribed';
ALTER TYPE communications.delivery_status_type RENAME VALUE 'FAILED' TO 'failed';
