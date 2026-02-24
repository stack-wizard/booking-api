create table if not exists invoice (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  invoice_type          text not null,
  invoice_number        text not null,
  invoice_date          date not null,
  customer_name         text null,
  customer_email        text null,
  customer_phone        text null,
  status                text not null default 'DRAFT',
  payment_status        text not null default 'UNPAID',
  fiscalization_status  text not null default 'NOT_REQUIRED',
  reference_table       text not null,
  reference_id          bigint not null,
  currency              text not null,
  subtotal_net          numeric(14,2) not null default 0,
  discount_total        numeric(14,2) not null default 0,
  tax1_total            numeric(14,2) not null default 0,
  tax2_total            numeric(14,2) not null default 0,
  total_gross           numeric(14,2) not null default 0,
  created_at            timestamptz not null default now(),
  unique (invoice_number),
  unique (reference_table, reference_id)
);

create table if not exists invoice_item (
  id                    bigserial primary key,
  invoice_id            bigint not null references invoice(id) on delete cascade,
  line_no               int not null,
  reservation_id        bigint null references reservation(id) on delete set null,
  product_id            bigint null references product(id) on delete set null,
  product_name          text not null,
  quantity              int not null,
  unit_price_gross      numeric(14,2) not null,
  discount_percent      numeric(7,4) not null default 0,
  discount_amount       numeric(14,2) not null default 0,
  price_without_tax     numeric(14,2) not null,
  tax1_percent          numeric(7,4) not null default 0,
  tax2_percent          numeric(7,4) not null default 0,
  tax1_amount           numeric(14,2) not null default 0,
  tax2_amount           numeric(14,2) not null default 0,
  nett_price            numeric(14,2) not null,
  gross_amount          numeric(14,2) not null,
  created_at            timestamptz not null default now(),
  unique (invoice_id, line_no)
);

create table if not exists invoice_sequence (
  id             bigserial primary key,
  tenant_id      bigint not null,
  invoice_type   text not null,
  invoice_year   int not null,
  last_number    int not null default 0,
  unique (tenant_id, invoice_type, invoice_year)
);
