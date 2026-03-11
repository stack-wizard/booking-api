alter table invoice
  add column if not exists storno_id bigint null references invoice(id) on delete set null;

create unique index if not exists uq_invoice_storno_id
  on invoice (storno_id)
  where storno_id is not null;

alter table invoice_payment_allocation
  add column if not exists payment_type text;

update invoice_payment_allocation
set payment_type = 'CARD'
where payment_type is null;

alter table invoice_payment_allocation
  alter column payment_type set not null;

alter table invoice_payment_allocation
  alter column payment_intent_id drop not null;

alter table invoice_payment_allocation
  drop constraint if exists invoice_payment_allocation_amount_check;

alter table invoice_payment_allocation
  add constraint invoice_payment_allocation_amount_check check (allocated_amount <> 0);

alter table invoice_payment_allocation
  drop constraint if exists invoice_payment_allocation_invoice_id_payment_intent_id_key;

create unique index if not exists uq_invoice_payment_allocation_invoice_payment_intent_not_null
  on invoice_payment_allocation (invoice_id, payment_intent_id)
  where payment_intent_id is not null;

alter table product
  add column if not exists product_type text;

update product
set product_type = 'SEALABLE_PRODUCT'
where product_type is null;

alter table product
  alter column product_type set not null;

alter table invoice_item
  alter column unit_price_gross type numeric(18,6),
  alter column discount_amount type numeric(18,6),
  alter column price_without_tax type numeric(18,6),
  alter column tax1_amount type numeric(18,6),
  alter column tax2_amount type numeric(18,6),
  alter column nett_price type numeric(18,6),
  alter column gross_amount type numeric(18,6);
