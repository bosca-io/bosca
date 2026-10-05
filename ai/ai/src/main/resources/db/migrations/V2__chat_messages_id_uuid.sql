-- Change chat_messages.id from varchar to uuid to match Kotlin model
alter table chat_messages alter column id type uuid using id::uuid;
