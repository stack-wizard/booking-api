-- Catering, AV and other services served inside a function. Items carry price but never capacity.

create table event_function_item (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  event_function_id   bigint not null references event_function(id) on delete cascade,
  product_id          bigint not null references product(id) on delete restrict,
  package_product_id  bigint null references product(id) on delete set null,
  description         text null,
  uom                 text not null,
  qty                 int not null check (qty > 0),
  qty_basis           text not null default 'FIXED'
                      check (qty_basis in ('FIXED','PER_GUARANTEED_PAX')),
  serve_at            timestamp null,
  unit_price          numeric(12,2) not null,
  discount_amount     numeric(12,2) not null default 0,
  gross_amount        numeric(12,2) not null,
  cost_amount         numeric(12,2) null,
  dietary_notes       text null,
  allergens           text[] null,
  display_order       int not null default 0,
  created_at          timestamptz not null default now()
);

create index idx_event_function_item_function on event_function_item (event_function_id, serve_at);
create index idx_event_function_item_tenant on event_function_item (tenant_id);
create index idx_event_function_item_product on event_function_item (product_id);
create index idx_event_function_item_package on event_function_item (package_product_id);
