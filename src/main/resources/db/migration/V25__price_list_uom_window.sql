alter table price_list
  add column if not exists start_time time null,
  add column if not exists end_time time null;
