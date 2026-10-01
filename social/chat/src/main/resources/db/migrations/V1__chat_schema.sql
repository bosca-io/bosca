create schema if not exists chat;

alter table public.chat_channels set schema chat;
alter table public.chat_channel_members set schema chat;
alter table public.chat_channel_permissions set schema chat;

alter table chat.chat_channels rename to channels;
alter table chat.chat_channel_members rename to channel_members;
alter table chat.chat_channel_permissions rename to channel_permissions;
