alter table reservation_request
  add column if not exists extension_count integer not null default 0;
