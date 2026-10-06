-- Phase 4: CMS listing of a package product. Images stay on product_image.

create table product_package_listing (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  product_id         bigint not null references product(id) on delete cascade,
  public_name        text null,
  public_description text null,
  valid_from         date null,
  valid_to           date null,
  min_pax            int null check (min_pax > 0),
  max_pax            int null check (max_pax > 0),
  duration           text not null default 'FULL_DAY' check (duration in ('FULL_DAY','HALF_DAY','CUSTOM')),
  default_start_time time not null default '09:00',
  setup_style        text null
                     check (setup_style is null or setup_style in ('THEATRE','CLASSROOM','U_SHAPE','BOARDROOM','BANQUET','CABARET','RECEPTION','HOLLOW_SQUARE')),
  published          boolean not null default false,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint product_package_listing_product_uk unique (product_id),
  constraint product_package_listing_validity check (valid_to is null or valid_from is null or valid_to >= valid_from),
  constraint product_package_listing_pax check (max_pax is null or min_pax is null or max_pax >= min_pax)
);

create index idx_product_package_listing_tenant on product_package_listing (tenant_id, published);
