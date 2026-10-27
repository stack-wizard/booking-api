-- Price periods belong to a season and have a kind; a price can be limited to weekdays (Mon=1 ... Sun=64).

alter table price_profile_date
  add column season_id bigint references season(id) on delete set null,
  add column period_kind text not null default 'SEASON'
    check (period_kind in ('SEASON','SUB_PERIOD','DATES'));
create index idx_price_profile_date_season on price_profile_date (season_id);

alter table price_list
  add column days_of_week smallint check (days_of_week between 1 and 127);
