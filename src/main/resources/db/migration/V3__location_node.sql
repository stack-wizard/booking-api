create table location_node (
  id         bigserial primary key,
  tenant_id  bigint not null,

  parent_id  bigint null references location_node(id) on delete cascade,

  -- examples: PROPERTY, BUILDING, FLOOR, BEACH, REGION, ROW, SECTION, ROOM_WING, etc.
  node_type  text not null,

  code       text null,
  name       text not null,

  sort_order int not null default 0,

  attrs      jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),

  unique (tenant_id, parent_id, name)
);