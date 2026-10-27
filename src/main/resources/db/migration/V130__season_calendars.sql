-- Explicit seasons: named season calendars (per chain or hotel), non-overlapping seasons and per-season hours.

create table season_calendar (
  id bigserial primary key,
  tenant_id bigint not null,
  property_tenant_id bigint,
  name text not null,
  created_at timestamptz not null default now()
);
create index idx_season_calendar_tenant on season_calendar (tenant_id);
create index idx_season_calendar_property on season_calendar (property_tenant_id);

create table season (
  id bigserial primary key,
  tenant_id bigint not null,
  season_calendar_id bigint not null references season_calendar(id) on delete cascade,
  code text not null,
  name text not null,
  date_from date not null,
  date_to date not null check (date_to >= date_from),
  status text not null default 'OPEN' check (status in ('OPEN','CLOSED')),
  display_order integer not null default 0,
  note text,
  created_at timestamptz not null default now(),
  constraint uq_season_code unique (season_calendar_id, code),
  constraint ex_season_no_overlap exclude using gist (
    season_calendar_id with =,
    daterange(date_from, date_to, '[]') with &&
  )
);
create index idx_season_tenant on season (tenant_id);
create index idx_season_calendar on season (season_calendar_id);

create table season_operating (
  id bigserial primary key,
  tenant_id bigint not null,
  season_id bigint not null references season(id) on delete cascade,
  location_node_id bigint references location_node(id) on delete cascade,
  open_time time not null,
  close_time time not null check (close_time > open_time),
  grid_minutes int not null check (grid_minutes > 0),
  min_duration_minutes int not null default 0 check (min_duration_minutes >= 0),
  max_duration_minutes int not null default 0 check (max_duration_minutes >= 0),
  created_at timestamptz not null default now(),
  constraint uq_season_operating unique nulls not distinct (season_id, location_node_id)
);
create index idx_season_operating_tenant on season_operating (tenant_id);
create index idx_season_operating_season on season_operating (season_id);
create index idx_season_operating_location on season_operating (location_node_id);
