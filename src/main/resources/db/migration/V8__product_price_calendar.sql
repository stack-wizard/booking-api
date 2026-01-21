create table product (
  id                bigserial primary key,
  tenant_id         bigint not null,
  name              text not null,
  resource_ref_kind text not null check (resource_ref_kind in ('RESOURCE','RESOURCE_TYPE')),
  resource_ref_id   text not null,
  default_uom       text not null,
  created_at        timestamptz not null default now()
);

create table product_extra_uom (
  product_id bigint not null references product(id) on delete cascade,
  uom        text not null,
  primary key (product_id, uom)
);

create table price_list (
  id         bigserial primary key,
  product_id bigint not null references product(id) on delete cascade,
  uom        text not null,
  price      numeric(12,2) not null,
  currency   text not null,
  created_at timestamptz not null default now(),
  unique (product_id, uom, currency)
);

create table booking_calendar (
  id                     bigserial primary key,
  tenant_id              bigint not null,
  location_node_id       bigint null references location_node(id) on delete cascade,
  open_time              time not null,
  close_time             time not null check (close_time > open_time),
  grid_minutes           int not null default 30 check (grid_minutes > 0),
  min_duration_minutes   int not null default 0 check (min_duration_minutes >= 0),
  max_duration_minutes   int not null default 0 check (max_duration_minutes >= 0),
  zone                   text not null default 'UTC',
  created_at             timestamptz not null default now()
);

alter table allocation
  add column product_id bigint null,
  add column uom text null,
  add column qty int null check (qty > 0);
