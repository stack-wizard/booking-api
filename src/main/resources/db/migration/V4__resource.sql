create table resource (
  id              bigserial primary key,
  tenant_id       bigint not null,

  resource_type_id bigint not null references resource_type(id) on delete restrict,

  -- POOL or EXACT (EXACT = physical asset OR a “bookable unit” like a deluxe set wrapper)
  kind            text not null check (kind in ('POOL','EXACT')),

  parent_pool_id  bigint null references resource(id) on delete set null,

  -- attach to place hierarchy (beach/floor/row/etc)
  location_id     bigint null references location_node(id) on delete set null,

  code            text null,
  name            text not null,

  status          text not null default 'ACTIVE'
                  check (status in ('ACTIVE','INACTIVE','MAINTENANCE')),

  -- counts
  unit_count      int not null default 1 check (unit_count > 0),
  
  pool_total_units int null check (pool_total_units is null or pool_total_units >= 0),

  cap_adults      int not null default 0 check (cap_adults >= 0),
  cap_children    int not null default 0 check (cap_children >= 0),
  cap_infants     int not null default 0 check (cap_infants >= 0),
  cap_total       int not null default 0 check (cap_total >= 0),

  attrs           jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),

  unique (tenant_id, code)
);
