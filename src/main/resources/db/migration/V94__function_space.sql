-- Phase 2: meeting rooms / offices as first-class function spaces over existing resource engine.

insert into resource_type (code, name, default_time_model) values
  ('MEETING_ROOM', 'Meeting Room', 'SLOT'),
  ('MEETING_OFFICE', 'Meeting Office', 'SLOT')
on conflict (code) do nothing;

insert into uom (code, name, active) values
  ('MINUTE', 'Minute', true)
on conflict (code) do nothing;

-- DAY and HOUR already seeded in V23; ensure they stay active.
update uom set active = true where code in ('DAY', 'HOUR', 'MINUTE');

create table function_space (
  id                      bigserial primary key,
  tenant_id               bigint not null,
  resource_id             bigint not null references resource(id) on delete cascade,
  area_sqm                numeric(10,2) null,
  floor                   text null,
  natural_light           boolean not null default false,
  divisible               boolean not null default false,
  min_duration_minutes    int not null default 60 check (min_duration_minutes > 0),
  default_setup_style     text null
                          check (default_setup_style is null or default_setup_style in (
                            'THEATRE','CLASSROOM','U_SHAPE','BOARDROOM',
                            'BANQUET','CABARET','RECEPTION','HOLLOW_SQUARE'
                          )),
  description             text null,
  active                  boolean not null default true,
  attrs                   jsonb not null default '{}'::jsonb,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  created_by              bigint null,
  updated_by              bigint null
);

create unique index uq_function_space_resource on function_space (resource_id);
create index idx_function_space_tenant on function_space (tenant_id);

create table function_space_setup (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  function_space_id  bigint not null references function_space(id) on delete cascade,
  setup_style        text not null
                     check (setup_style in (
                       'THEATRE','CLASSROOM','U_SHAPE','BOARDROOM',
                       'BANQUET','CABARET','RECEPTION','HOLLOW_SQUARE'
                     )),
  capacity           int not null check (capacity > 0),
  notes              text null,
  created_at         timestamptz not null default now()
);

create unique index uq_function_space_setup
  on function_space_setup (function_space_id, setup_style);
create index idx_function_space_setup_space on function_space_setup (function_space_id);
create index idx_function_space_setup_tenant on function_space_setup (tenant_id);
