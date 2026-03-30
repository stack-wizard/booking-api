create table if not exists cancellation_policy (
  id                                      bigserial primary key,
  tenant_id                               bigint not null,
  name                                    text not null,
  description                             text null,
  active                                  boolean not null default true,
  priority                                int not null default 100,
  scope_type                              text not null,
  scope_id                                bigint null,
  cutoff_days_before_start                int not null default 14,
  before_cutoff_release_type              text not null default 'FULL',
  before_cutoff_release_value             numeric(14,2) not null default 100,
  before_cutoff_allow_cash_refund         boolean not null default true,
  before_cutoff_allow_customer_credit     boolean not null default false,
  before_cutoff_default_settlement_mode   text null,
  after_cutoff_release_type               text not null default 'NONE',
  after_cutoff_release_value              numeric(14,2) not null default 0,
  after_cutoff_allow_cash_refund          boolean not null default false,
  after_cutoff_allow_customer_credit      boolean not null default false,
  after_cutoff_default_settlement_mode    text null,
  effective_from                          timestamptz null,
  effective_to                            timestamptz null,
  created_at                              timestamptz not null default now(),
  constraint cancellation_policy_scope_type_check
    check (scope_type in ('TENANT', 'PRODUCT')),
  constraint cancellation_policy_cutoff_days_check
    check (cutoff_days_before_start >= 0),
  constraint cancellation_policy_before_release_type_check
    check (before_cutoff_release_type in ('FULL', 'PERCENT', 'FIXED', 'NONE')),
  constraint cancellation_policy_after_release_type_check
    check (after_cutoff_release_type in ('FULL', 'PERCENT', 'FIXED', 'NONE')),
  constraint cancellation_policy_before_default_settlement_mode_check
    check (before_cutoff_default_settlement_mode is null or before_cutoff_default_settlement_mode in ('CASH_REFUND', 'CUSTOMER_CREDIT')),
  constraint cancellation_policy_after_default_settlement_mode_check
    check (after_cutoff_default_settlement_mode is null or after_cutoff_default_settlement_mode in ('CASH_REFUND', 'CUSTOMER_CREDIT')),
  constraint cancellation_policy_scope_id_check
    check (
      (scope_type = 'TENANT' and scope_id is null)
      or (scope_type = 'PRODUCT' and scope_id is not null)
    )
);

create index if not exists idx_cancellation_policy_tenant
  on cancellation_policy (tenant_id);

create index if not exists idx_cancellation_policy_scope
  on cancellation_policy (tenant_id, scope_type, scope_id);

alter table reservation_request
  add column if not exists cancellation_policy_text text null;

alter table reservation
  add column if not exists cancellation_policy_id bigint null references cancellation_policy(id) on delete set null,
  add column if not exists cancellation_policy_text text null;
