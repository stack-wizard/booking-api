-- Phase 4: a package is a product with components. product_type stays the fiscal classification.

alter table product add column if not exists package_pricing text null
  check (package_pricing is null or package_pricing in ('SUM','SPLIT_PERCENT','SPLIT_FIXED'));

create table product_component (
  id                     bigserial primary key,
  tenant_id              bigint not null,
  package_product_id     bigint not null references product(id) on delete cascade,
  component_product_id   bigint not null references product(id) on delete restrict,
  qty                    int not null default 1 check (qty > 0),
  qty_basis              text not null default 'PER_PAX' check (qty_basis in ('FIXED','PER_PAX')),
  included               boolean not null default true,
  share_percent          numeric(5,2) null check (share_percent >= 0 and share_percent <= 100),
  fixed_amount           numeric(12,2) null check (fixed_amount >= 0),
  function_type          text null
                         check (function_type is null or function_type in ('PLENARY','BREAKOUT','COFFEE_BREAK','LUNCH','DINNER','RECEPTION','EXHIBITION','OTHER')),
  start_offset_minutes   int null check (start_offset_minutes >= 0),
  duration_minutes       int null check (duration_minutes > 0),
  display_order          int not null default 0,
  created_at             timestamptz not null default now(),
  constraint product_component_not_self check (package_product_id <> component_product_id)
);

create index idx_product_component_package on product_component (package_product_id, display_order);
create index idx_product_component_component on product_component (component_product_id);
create index idx_product_component_tenant on product_component (tenant_id);
