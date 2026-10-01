alter table segmentation.campaigns add column ended_at timestamptz;
alter type segmentation.notification_status add value 'active' after 'sent';
