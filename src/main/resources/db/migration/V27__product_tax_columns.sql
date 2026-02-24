alter table product
  add column if not exists tax1_percent numeric(7,4) not null default 0,
  add column if not exists tax2_percent numeric(7,4) not null default 0;
