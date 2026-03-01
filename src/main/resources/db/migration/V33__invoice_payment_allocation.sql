create table if not exists invoice_payment_allocation (
  id                  bigserial primary key,
  invoice_id          bigint not null references invoice(id) on delete cascade,
  payment_intent_id   bigint not null references payment_intent(id) on delete restrict,
  allocated_amount    numeric(14,2) not null,
  created_at          timestamptz not null default now(),
  unique (invoice_id, payment_intent_id),
  constraint invoice_payment_allocation_amount_check check (allocated_amount > 0)
);

create index if not exists idx_invoice_payment_allocation_invoice on invoice_payment_allocation (invoice_id);
create index if not exists idx_invoice_payment_allocation_payment_intent on invoice_payment_allocation (payment_intent_id);
