-- Phase 5: quote generated from an event. Lines are a commercial copy; the event stays the operational truth.

alter table product add column if not exists sales_group text null
  check (sales_group is null or sales_group in ('MEETING','F_AND_B','AV','EXTRAS'));

create table sales_quote (
  id               bigserial primary key,
  tenant_id        bigint not null,
  event_id         bigint not null references event(id) on delete cascade,
  opportunity_id   bigint null references crm_opportunity(id) on delete set null,
  account_id       bigint not null references crm_account(id) on delete restrict,
  quote_number     text not null,
  status           text not null default 'DRAFT'
                   check (status in ('DRAFT','PENDING_APPROVAL','APPROVED','SENT','ACCEPTED','REJECTED','EXPIRED','SUPERSEDED')),
  currency         text not null default 'EUR',
  valid_until      date null,
  version          int not null default 0 check (version >= 0),
  total_base       numeric(14,2) not null default 0,
  total_offered    numeric(14,2) not null default 0,
  total_tax        numeric(14,2) not null default 0,
  discount_percent numeric(7,4) not null default 0,
  notes            text null,
  terms            text null,
  sent_at          timestamptz null,
  decided_at       timestamptz null,
  decided_by_name  text null,
  decision_note    text null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  created_by       bigint null,
  updated_by       bigint null
);

create unique index uq_sales_quote_number on sales_quote (tenant_id, quote_number);
create index idx_sales_quote_tenant_status on sales_quote (tenant_id, status);
create index idx_sales_quote_event on sales_quote (event_id);
create index idx_sales_quote_opportunity on sales_quote (opportunity_id);
create index idx_sales_quote_account on sales_quote (account_id);
create index idx_sales_quote_valid_until on sales_quote (valid_until) where status = 'SENT';

create table sales_quote_line (
  id                bigserial primary key,
  tenant_id         bigint not null,
  quote_id          bigint not null references sales_quote(id) on delete cascade,
  line_group        text not null check (line_group in ('MEETING','F_AND_B','AV','EXTRAS','DISCOUNT')),
  product_id        bigint null references product(id) on delete set null,
  event_function_id bigint null references event_function(id) on delete set null,
  description       text not null,
  service_date      date null,
  uom               text null,
  qty               int not null default 1 check (qty > 0),
  base_rate         numeric(12,2) not null default 0 check (base_rate >= 0),
  offered_rate      numeric(12,2) not null default 0,
  tax1_percent      numeric(7,4) not null default 0,
  tax2_percent      numeric(7,4) not null default 0,
  amount_base       numeric(14,2) not null default 0,
  amount_offered    numeric(14,2) not null default 0,
  display_order     int not null default 0,
  created_at        timestamptz not null default now(),
  constraint sales_quote_line_rate_sign check (line_group = 'DISCOUNT' or offered_rate >= 0),
  constraint sales_quote_line_discount_sign check (line_group <> 'DISCOUNT' or offered_rate <= 0)
);

create index idx_sales_quote_line_quote on sales_quote_line (quote_id, display_order);
create index idx_sales_quote_line_product on sales_quote_line (product_id);
create index idx_sales_quote_line_function on sales_quote_line (event_function_id);
create index idx_sales_quote_line_tenant on sales_quote_line (tenant_id);

create table sales_quote_version (
  id            bigserial primary key,
  tenant_id     bigint not null,
  quote_id      bigint not null references sales_quote(id) on delete cascade,
  version       int not null check (version > 0),
  snapshot      jsonb not null,
  total_offered numeric(14,2) not null,
  sent_by       bigint null references app_user(id) on delete set null,
  created_at    timestamptz not null default now()
);

create unique index uq_sales_quote_version on sales_quote_version (quote_id, version);
create index idx_sales_quote_version_tenant on sales_quote_version (tenant_id);
create index idx_sales_quote_version_user on sales_quote_version (sent_by);

create table sales_quote_approval (
  id                bigserial primary key,
  tenant_id         bigint not null,
  quote_id          bigint not null references sales_quote(id) on delete cascade,
  status            text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED')),
  discount_percent  numeric(7,4) not null,
  threshold_percent numeric(7,4) not null,
  requested_by      bigint null references app_user(id) on delete set null,
  decided_by        bigint null references app_user(id) on delete set null,
  decided_at        timestamptz null,
  note              text null,
  created_at        timestamptz not null default now()
);

create index idx_sales_quote_approval_quote on sales_quote_approval (quote_id, created_at);
create index idx_sales_quote_approval_tenant_status on sales_quote_approval (tenant_id, status);
create index idx_sales_quote_approval_requested on sales_quote_approval (requested_by);
create index idx_sales_quote_approval_decided on sales_quote_approval (decided_by);
