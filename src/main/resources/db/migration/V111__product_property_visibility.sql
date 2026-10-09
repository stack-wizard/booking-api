-- Shared catalog: a chain product is offered in every hotel (ALL, minus hotels that switch it off) or only in the
-- hotels that switch it on (SELECTED). Name, UOM and tax always come from the product; prices can be overridden
-- per hotel through a price profile that carries property_tenant_id.

alter table product
  add column if not exists property_visibility text not null default 'ALL'
    check (property_visibility in ('ALL', 'SELECTED'));

create table product_property (
  id          bigserial primary key,
  tenant_id   bigint not null,                                  -- the hotel
  product_id  bigint not null references product (id) on delete cascade,
  visible     boolean not null,
  sort_order  int not null default 0,
  created_at  timestamptz not null default now(),
  constraint product_property_hotel_product_uk unique (tenant_id, product_id)
);

create index idx_product_property_tenant on product_property (tenant_id);
create index idx_product_property_product on product_property (product_id);

-- null = chain-wide price, set = price that applies in that hotel only (wins over the chain price).
alter table price_profile
  add column if not exists property_tenant_id bigint null
    references platform_tenant_mapping (tenant_id) on delete restrict;

create index if not exists idx_price_profile_property on price_profile (property_tenant_id);
