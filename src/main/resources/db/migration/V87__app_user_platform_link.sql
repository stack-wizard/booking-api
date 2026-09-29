-- Link local app_user rows to Mikos Platform users (JWT sub = platform_user_id).
alter table app_user
  add column if not exists platform_user_id uuid;

create unique index if not exists uq_app_user_platform_user_id
  on app_user (platform_user_id)
  where platform_user_id is not null;

-- Passwords live on the Platform; local hash optional (kept for online-system / legacy rows).
alter table app_user
  alter column password_hash drop not null;
