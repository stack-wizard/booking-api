create table if not exists payment_transaction (
  id                      bigserial primary key,
  tenant_id               bigint not null,
  reservation_request_id  bigint null references reservation_request(id) on delete set null,
  payment_intent_id       bigint null references payment_intent(id) on delete set null,
  payment_type            text not null,
  status                  text not null,
  currency                text not null,
  amount                  numeric(14,2) not null,
  external_ref            text null,
  note                    text null,
  created_at              timestamptz not null default now(),
  constraint payment_transaction_amount_check check (amount > 0)
);

create index if not exists idx_payment_transaction_tenant
  on payment_transaction (tenant_id);

create index if not exists idx_payment_transaction_request
  on payment_transaction (reservation_request_id);

create index if not exists idx_payment_transaction_status
  on payment_transaction (status);

create index if not exists idx_payment_transaction_created_at
  on payment_transaction (created_at);

create unique index if not exists uq_payment_transaction_payment_intent
  on payment_transaction (payment_intent_id)
  where payment_intent_id is not null;

alter table invoice_payment_allocation
  add column if not exists payment_transaction_id bigint;

insert into payment_transaction (
  tenant_id,
  reservation_request_id,
  payment_intent_id,
  payment_type,
  status,
  currency,
  amount,
  external_ref,
  note
)
select
  pi.tenant_id,
  pi.reservation_request_id,
  pi.id,
  'CARD',
  'POSTED',
  pi.currency,
  pi.amount,
  pi.provider_payment_id,
  'Backfilled from payment_intent'
from payment_intent pi
where exists (
  select 1
  from invoice_payment_allocation a
  where a.payment_intent_id = pi.id
)
and not exists (
  select 1
  from payment_transaction pt
  where pt.payment_intent_id = pi.id
);

update invoice_payment_allocation a
set payment_transaction_id = pt.id
from payment_transaction pt
where a.payment_transaction_id is null
  and a.payment_intent_id is not null
  and pt.payment_intent_id = a.payment_intent_id;

insert into payment_transaction (
  tenant_id,
  reservation_request_id,
  payment_intent_id,
  payment_type,
  status,
  currency,
  amount,
  external_ref,
  note
)
select
  i.tenant_id,
  i.reservation_request_id,
  null,
  coalesce(a.payment_type, 'CASH'),
  'POSTED',
  i.currency,
  abs(a.allocated_amount),
  null,
  'Legacy allocation #' || a.id::text
from invoice_payment_allocation a
join invoice i on i.id = a.invoice_id
where a.payment_transaction_id is null;

update invoice_payment_allocation a
set payment_transaction_id = pt.id
from payment_transaction pt
where a.payment_transaction_id is null
  and pt.note = ('Legacy allocation #' || a.id::text);

delete from invoice_payment_allocation
where payment_transaction_id is null;

alter table invoice_payment_allocation
  alter column payment_transaction_id set not null;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'invoice_payment_allocation_payment_transaction_id_fkey'
  ) then
    alter table invoice_payment_allocation
      add constraint invoice_payment_allocation_payment_transaction_id_fkey
      foreign key (payment_transaction_id)
      references payment_transaction(id)
      on delete restrict;
  end if;
end $$;

drop index if exists uq_invoice_payment_allocation_invoice_payment_intent_not_null;
drop index if exists idx_invoice_payment_allocation_payment_intent;

alter table invoice_payment_allocation
  drop column if exists payment_intent_id;

alter table invoice_payment_allocation
  drop column if exists payment_type;

create unique index if not exists uq_invoice_payment_allocation_invoice_payment_transaction
  on invoice_payment_allocation (invoice_id, payment_transaction_id);

create index if not exists idx_invoice_payment_allocation_payment_transaction
  on invoice_payment_allocation (payment_transaction_id);
