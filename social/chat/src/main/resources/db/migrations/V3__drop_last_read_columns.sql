-- Last-read tracking moved to the chat-read-state NATS KV bucket
-- (see bosca.chat.state.ChatReadStateStore). The relational columns
-- haven't been read or written since the KV refactor landed, so drop
-- them to make the schema match the model.
alter table chat.channel_members
    drop column if exists last_read_at,
    drop column if exists last_read_sequence;
