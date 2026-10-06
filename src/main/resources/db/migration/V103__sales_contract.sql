-- Phase 5: contract on an accepted quote, its documents and the payment plan that issues invoices.

create table sales_contract (
  id               bigserial primary key,
  tenant_id        bigint not null,
  event_id         bigint not null references event(id) on delete cascade,
  quote_id         bigint not null references sales_quote(id) on delete restrict,
  contract_number  text not null,
  status           text not null default 'DRAFT' check (status in ('DRAFT','SENT','SIGNED','CANCELLED')),
  currency         text not null default 'EUR',
  total_amount     numeric(14,2) not null check (total_amount >= 0),
  terms            text null,
  sent_at          timestamptz null,
  signed_at        timestamptz null,
  signed_by_name   text null,
  cancelled_at     timestamptz null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  created_by       bigint null,
  updated_by       bigint null
);

create unique index uq_sales_contract_number on sales_contract (tenant_id, contract_number);
create unique index uq_sales_contract_open_event on sales_contract (event_id) where status <> 'CANCELLED';
create index idx_sales_contract_tenant_status on sales_contract (tenant_id, status);
create index idx_sales_contract_quote on sales_contract (quote_id);

create table sales_contract_document (
  id           bigserial primary key,
  tenant_id    bigint not null,
  contract_id  bigint not null references sales_contract(id) on delete cascade,
  kind         text not null default 'OTHER' check (kind in ('CONTRACT','SIGNED','ANNEX','OTHER')),
  file_name    text not null,
  content_type text null,
  size_bytes   bigint null,
  storage_key  text not null,
  created_by   bigint null references app_user(id) on delete set null,
  created_at   timestamptz not null default now()
);

create index idx_sales_contract_document_contract on sales_contract_document (contract_id, created_at);
create index idx_sales_contract_document_tenant on sales_contract_document (tenant_id);
create index idx_sales_contract_document_user on sales_contract_document (created_by);

create table sales_payment_milestone (
  id            bigserial primary key,
  tenant_id     bigint not null,
  contract_id   bigint not null references sales_contract(id) on delete cascade,
  kind          text not null check (kind in ('DEPOSIT','INTERIM','FINAL')),
  label         text null,
  due_date      date not null,
  percent       numeric(7,4) null check (percent is null or (percent > 0 and percent <= 100)),
  amount        numeric(14,2) not null check (amount >= 0),
  status        text not null default 'PLANNED' check (status in ('PLANNED','INVOICED','CANCELLED')),
  invoice_id    bigint null references invoice(id) on delete set null,
  display_order int not null default 0,
  created_at    timestamptz not null default now()
);

create index idx_sales_payment_milestone_contract on sales_payment_milestone (contract_id, display_order);
create index idx_sales_payment_milestone_tenant_due on sales_payment_milestone (tenant_id, status, due_date);
create index idx_sales_payment_milestone_invoice on sales_payment_milestone (invoice_id);
