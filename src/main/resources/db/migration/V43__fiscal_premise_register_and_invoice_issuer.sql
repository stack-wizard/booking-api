create table if not exists fiscal_business_premise (
  id          bigserial primary key,
  tenant_id   bigint not null,
  code        text not null,
  name        text not null,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, code)
);

create index if not exists idx_fiscal_business_premise_tenant
  on fiscal_business_premise (tenant_id);

create table if not exists fiscal_cash_register (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  business_premise_id   bigint not null references fiscal_business_premise(id) on delete restrict,
  code                  text not null,
  terminal_id           text null,
  active                boolean not null default true,
  created_at            timestamptz not null default now(),
  unique (tenant_id, business_premise_id, code)
);

create index if not exists idx_fiscal_cash_register_tenant
  on fiscal_cash_register (tenant_id);

create index if not exists idx_fiscal_cash_register_business_premise
  on fiscal_cash_register (business_premise_id);

alter table invoice
  add column if not exists issued_by_user_id bigint null references app_user(id) on delete set null;

alter table invoice
  add column if not exists issued_at timestamptz null;

alter table invoice
  add column if not exists issued_by_mode text not null default 'ONLINE_SYSTEM';

alter table invoice
  add column if not exists business_premise_id bigint null references fiscal_business_premise(id) on delete set null;

alter table invoice
  add column if not exists cash_register_id bigint null references fiscal_cash_register(id) on delete set null;

alter table invoice
  add column if not exists business_premise_code_snapshot text null;

alter table invoice
  add column if not exists cash_register_code_snapshot text null;

update invoice
set issued_at = created_at
where issued_at is null
  and upper(status) = 'ISSUED';

update invoice
set issued_by_mode = 'ONLINE_SYSTEM'
where issued_by_mode is null;

alter table invoice
  alter column issued_by_mode set not null;

alter table invoice
  drop constraint if exists invoice_issued_by_mode_check;

alter table invoice
  add constraint invoice_issued_by_mode_check
  check (issued_by_mode in ('CASHIER', 'ONLINE_SYSTEM'));

create index if not exists idx_invoice_issued_by_user
  on invoice (issued_by_user_id);

create index if not exists idx_invoice_business_premise
  on invoice (business_premise_id);

create index if not exists idx_invoice_cash_register
  on invoice (cash_register_id);

alter table app_user
  drop constraint if exists app_user_role_check;

alter table app_user
  add constraint app_user_role_check
  check (role in ('SUPER_ADMIN', 'ADMIN', 'STAFF', 'CASHIER'));
