alter table payment_transaction
  drop constraint if exists payment_transaction_amount_check;

alter table payment_transaction
  add column if not exists transaction_type text,
  add column if not exists refund_type text null,
  add column if not exists source_payment_transaction_id bigint null references payment_transaction(id) on delete set null,
  add column if not exists credit_note_invoice_id bigint null references invoice(id) on delete set null;

update payment_transaction
set transaction_type = 'CHARGE'
where transaction_type is null;

update payment_transaction
set amount = abs(amount)
where transaction_type = 'CHARGE'
  and amount < 0;

update payment_transaction
set amount = -abs(amount)
where transaction_type = 'REFUND'
  and amount > 0;

alter table payment_transaction
  alter column transaction_type set not null;

alter table payment_transaction
  drop constraint if exists payment_transaction_transaction_type_check;

alter table payment_transaction
  add constraint payment_transaction_transaction_type_check
  check (transaction_type in ('CHARGE', 'REFUND'));

alter table payment_transaction
  drop constraint if exists payment_transaction_refund_type_check;

alter table payment_transaction
  add constraint payment_transaction_refund_type_check
  check (refund_type is null or refund_type in ('CANCELLATION', 'MANUAL'));

alter table payment_transaction
  drop constraint if exists payment_transaction_amount_check;

alter table payment_transaction
  add constraint payment_transaction_amount_check
  check (
    amount <> 0
    and (
      (transaction_type = 'CHARGE' and amount > 0)
      or (transaction_type = 'REFUND' and amount < 0)
    )
  );

create index if not exists idx_payment_transaction_transaction_type
  on payment_transaction (transaction_type);

create index if not exists idx_payment_transaction_source_payment
  on payment_transaction (source_payment_transaction_id);

create index if not exists idx_payment_transaction_credit_note_invoice
  on payment_transaction (credit_note_invoice_id);

alter table invoice_payment_allocation
  add column if not exists allocation_type text;

update invoice_payment_allocation
set allocation_type = 'SETTLEMENT'
where allocation_type is null;

alter table invoice_payment_allocation
  alter column allocation_type set not null;

alter table invoice_payment_allocation
  drop constraint if exists invoice_payment_allocation_allocation_type_check;

alter table invoice_payment_allocation
  add constraint invoice_payment_allocation_allocation_type_check
  check (allocation_type in ('SETTLEMENT', 'REFUND_RELEASE', 'REALLOCATION'));
