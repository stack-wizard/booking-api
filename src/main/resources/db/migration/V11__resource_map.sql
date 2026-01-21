create table resource_map (
  id               bigserial primary key,
  tenant_id        bigint not null,
  location_node_id bigint null references location_node(id) on delete set null,
  name             text not null,
  image_url        text null,
  svg_overlay      text null,
  created_at       timestamptz not null default now()
);

create table resource_map_resource (
  id          bigserial primary key,
  resource_map_id bigint not null references resource_map(id) on delete cascade,
  resource_id bigint not null references resource(id) on delete cascade,
  polygon     text not null,
  label       text null,
  created_at  timestamptz not null default now(),
  unique (resource_map_id, resource_id)
);
