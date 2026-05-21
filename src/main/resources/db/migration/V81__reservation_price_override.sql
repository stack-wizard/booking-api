alter table reservation
  add column if not exists price_override_enabled boolean not null default false;
