create table if not exists payment_card_type (
  id         bigserial primary key,
  tenant_id   bigint not null,
  code        text not null,
  name        text null,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, code)
);

create index if not exists idx_payment_card_type_tenant
  on payment_card_type (tenant_id);

update payment_transaction
set card_type = upper(trim(card_type))
where card_type is not null
  and btrim(card_type) <> '';

update payment_transaction
set card_type = null
where card_type is not null
  and btrim(card_type) = '';

update opera_fiscal_payment_mapping
set card_type = upper(trim(card_type))
where card_type is not null
  and btrim(card_type) <> '';

update opera_fiscal_payment_mapping
set card_type = null
where card_type is not null
  and btrim(card_type) = '';

insert into payment_card_type (tenant_id, code, name, active)
select distinct existing.tenant_id,
                existing.card_type,
                existing.card_type,
                true
from (
  select tenant_id, card_type
  from payment_transaction
  where card_type is not null

  union

  select tenant_id, card_type
  from opera_fiscal_payment_mapping
  where card_type is not null
) existing
on conflict (tenant_id, code) do nothing;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'payment_transaction_card_type_fkey'
  ) then
    alter table payment_transaction
      add constraint payment_transaction_card_type_fkey
      foreign key (tenant_id, card_type)
      references payment_card_type (tenant_id, code);
  end if;
end $$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'opera_fiscal_payment_mapping_card_type_fkey'
  ) then
    alter table opera_fiscal_payment_mapping
      add constraint opera_fiscal_payment_mapping_card_type_fkey
      foreign key (tenant_id, card_type)
      references payment_card_type (tenant_id, code);
  end if;
end $$;
