-- Sales catalogs: what is sold together on one web surface (BEACH, MICE, ...), its items, seasons and maps.

create table sales_catalog (
  id bigserial primary key,
  tenant_id bigint not null,
  property_tenant_id bigint,
  code text not null,
  name text not null,
  sale_mode text not null check (sale_mode in ('INSTANT','INQUIRY')),
  season_calendar_id bigint references season_calendar(id) on delete restrict,
  request_type text not null default 'EXTERNAL' check (request_type in ('INTERNAL','EXTERNAL','WALKIN','INHOUSE')),
  currency text not null default 'EUR',
  active boolean not null default true,
  created_at timestamptz not null default now(),
  constraint uq_sales_catalog_code unique nulls not distinct (tenant_id, property_tenant_id, code)
);
create index idx_sales_catalog_tenant on sales_catalog (tenant_id);
create index idx_sales_catalog_calendar on sales_catalog (season_calendar_id);

create table catalog_item (
  id bigserial primary key,
  tenant_id bigint not null,
  catalog_id bigint not null references sales_catalog(id) on delete cascade,
  item_kind text not null check (item_kind in ('RESOURCE','PRODUCT','PACKAGE')),
  resource_id bigint references resource(id) on delete cascade,
  product_id bigint references product(id) on delete cascade,
  item_group text,
  public_name text,
  public_description text,
  display_order integer not null default 0,
  published boolean not null default false,
  valid_from date,
  valid_to date,
  min_pax integer check (min_pax > 0),
  max_pax integer check (max_pax > 0),
  duration text check (duration in ('FULL_DAY','HALF_DAY','CUSTOM')),
  default_start_time time,
  setup_style text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((item_kind = 'RESOURCE') = (resource_id is not null)),
  check ((item_kind in ('PRODUCT','PACKAGE')) = (product_id is not null)),
  check (valid_to is null or valid_from is null or valid_to >= valid_from),
  check (max_pax is null or min_pax is null or max_pax >= min_pax)
);
create unique index uq_catalog_item_resource on catalog_item (catalog_id, resource_id) where resource_id is not null;
create unique index uq_catalog_item_product on catalog_item (catalog_id, product_id) where product_id is not null;
create index idx_catalog_item_tenant on catalog_item (tenant_id);
create index idx_catalog_item_catalog on catalog_item (catalog_id);
create index idx_catalog_item_resource on catalog_item (resource_id);
create index idx_catalog_item_product on catalog_item (product_id);

create table catalog_item_season (
  catalog_item_id bigint not null references catalog_item(id) on delete cascade,
  season_id bigint not null references season(id) on delete cascade,
  primary key (catalog_item_id, season_id)
);
create index idx_catalog_item_season_season on catalog_item_season (season_id);

create table catalog_map (
  catalog_id bigint not null references sales_catalog(id) on delete cascade,
  resource_map_id bigint not null references resource_map(id) on delete cascade,
  season_id bigint references season(id) on delete cascade,
  primary key (catalog_id, resource_map_id)
);
create index idx_catalog_map_map on catalog_map (resource_map_id);
create index idx_catalog_map_season on catalog_map (season_id);
