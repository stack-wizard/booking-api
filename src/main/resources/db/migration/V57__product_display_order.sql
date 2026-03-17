alter table product
  add column if not exists display_order int not null default 0;

create index if not exists idx_product_tenant_display_order
  on product (tenant_id, display_order, name, id);
