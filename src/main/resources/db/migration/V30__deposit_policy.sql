create table if not exists deposit_policy (
  id                bigserial primary key,
  tenant_id         bigint not null,
  name              text not null,
  active            boolean not null default true,
  priority          int not null default 100,
  scope_type        text not null,
  scope_id          bigint null,
  deposit_type      text not null,
  deposit_value     numeric(14,2) not null,
  currency          text null,
  effective_from    timestamptz null,
  effective_to      timestamptz null,
  created_at        timestamptz not null default now(),
  constraint deposit_policy_scope_type_check check (scope_type in ('TENANT', 'PRODUCT')),
  constraint deposit_policy_deposit_type_check check (deposit_type in ('PERCENT', 'FIXED', 'FULL')),
  constraint deposit_policy_deposit_value_check check (deposit_value >= 0),
  constraint deposit_policy_effective_range_check check (
    effective_to is null or effective_from is null or effective_to >= effective_from
  )
);

create index if not exists idx_deposit_policy_tenant on deposit_policy (tenant_id);
create index if not exists idx_deposit_policy_scope on deposit_policy (scope_type, scope_id);
create index if not exists idx_deposit_policy_active on deposit_policy (active);
