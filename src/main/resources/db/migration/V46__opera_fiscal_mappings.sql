create table if not exists opera_fiscal_charge_mapping (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  product_id          bigint null references product(id) on delete set null,
  product_type        text null,
  trx_code            text not null,
  trx_type            text not null default 'C',
  trx_code_type       text not null default 'L',
  trx_group           text null,
  trx_sub_group       text null,
  description         text null,
  bucket_code         text null,
  bucket_type         text null,
  bucket_value        text null,
  bucket_description  text null,
  active              boolean not null default true,
  priority            int not null default 100,
  created_at          timestamptz not null default now(),
  constraint opera_fiscal_charge_mapping_trx_type_check check (trx_type in ('C', 'FC'))
);

create index if not exists idx_opera_fiscal_charge_mapping_tenant
  on opera_fiscal_charge_mapping (tenant_id);

create index if not exists idx_opera_fiscal_charge_mapping_product
  on opera_fiscal_charge_mapping (tenant_id, product_id);

create index if not exists idx_opera_fiscal_charge_mapping_product_type
  on opera_fiscal_charge_mapping (tenant_id, product_type);

create index if not exists idx_opera_fiscal_charge_mapping_active_priority
  on opera_fiscal_charge_mapping (tenant_id, active, priority);

create table if not exists opera_fiscal_payment_mapping (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  payment_type        text not null,
  trx_code            text not null,
  trx_type            text not null default 'FC',
  trx_code_type       text not null default 'O',
  trx_group           text null,
  trx_sub_group       text null,
  description         text null,
  bucket_code         text null,
  bucket_type         text null,
  bucket_value        text null,
  bucket_description  text null,
  active              boolean not null default true,
  created_at          timestamptz not null default now(),
  constraint opera_fiscal_payment_mapping_trx_type_check check (trx_type in ('C', 'FC')),
  unique (tenant_id, payment_type)
);

create index if not exists idx_opera_fiscal_payment_mapping_tenant
  on opera_fiscal_payment_mapping (tenant_id);

create table if not exists opera_fiscal_tax_mapping (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  tax_percent         numeric(7,4) not null,
  generate_trx_code   text not null,
  tax_name            text null,
  active              boolean not null default true,
  created_at          timestamptz not null default now(),
  unique (tenant_id, tax_percent)
);

create index if not exists idx_opera_fiscal_tax_mapping_tenant
  on opera_fiscal_tax_mapping (tenant_id);

create table if not exists opera_fiscal_udf_mapping (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  udf_name            text not null,
  udf_value           text null,
  active              boolean not null default true,
  sort_order          int not null default 100,
  created_at          timestamptz not null default now()
);

create index if not exists idx_opera_fiscal_udf_mapping_tenant
  on opera_fiscal_udf_mapping (tenant_id);

create index if not exists idx_opera_fiscal_udf_mapping_active_sort
  on opera_fiscal_udf_mapping (tenant_id, active, sort_order);
