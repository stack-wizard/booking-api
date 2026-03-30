alter table tenant_integration_config
  add column if not exists refund_path text null;

update invoice
set invoice_type = upper(invoice_type)
where invoice_type is not null;

alter table invoice
  drop constraint if exists invoice_invoice_type_check;

alter table invoice
  add constraint invoice_invoice_type_check
  check (invoice_type in ('INVOICE', 'DEPOSIT', 'INVOICE_STORNO', 'DEPOSIT_STORNO', 'ROOM_CHARGE', 'CREDIT_NOTE'));

alter table opera_invoice_type_routing
  drop constraint if exists opera_invoice_type_routing_invoice_type_check;

alter table opera_invoice_type_routing
  add constraint opera_invoice_type_routing_invoice_type_check
  check (invoice_type in ('INVOICE', 'DEPOSIT', 'INVOICE_STORNO', 'DEPOSIT_STORNO', 'ROOM_CHARGE', 'CREDIT_NOTE'));

alter table reservation
  add column if not exists cancellation_policy_snapshot jsonb null;

create table if not exists cancellation_request (
  id                              bigserial primary key,
  tenant_id                       bigint not null,
  reservation_request_id          bigint not null references reservation_request(id) on delete cascade,
  status                          text not null,
  settlement_mode                 text not null,
  currency                        text not null,
  source_invoice_id               bigint null references invoice(id) on delete set null,
  storno_invoice_id               bigint null references invoice(id) on delete set null,
  credit_note_invoice_id          bigint null references invoice(id) on delete set null,
  penalty_invoice_id              bigint null references invoice(id) on delete set null,
  final_invoice_id                bigint null references invoice(id) on delete set null,
  source_payment_transaction_id   bigint null references payment_transaction(id) on delete set null,
  refund_payment_transaction_id   bigint null references payment_transaction(id) on delete set null,
  cancelled_amount                numeric(14,2) not null default 0,
  released_amount                 numeric(14,2) not null default 0,
  refund_amount                   numeric(14,2) not null default 0,
  penalty_amount                  numeric(14,2) not null default 0,
  credit_amount                   numeric(14,2) not null default 0,
  reservation_request_url         text null,
  note                            text null,
  failure_reason                  text null,
  created_at                      timestamptz not null default now(),
  completed_at                    timestamptz null,
  constraint cancellation_request_status_check
    check (status in ('PENDING', 'COMPLETED', 'FAILED')),
  constraint cancellation_request_settlement_mode_check
    check (settlement_mode in ('CASH_REFUND', 'CUSTOMER_CREDIT', 'NONE'))
);

create index if not exists idx_cancellation_request_tenant
  on cancellation_request (tenant_id);

create index if not exists idx_cancellation_request_request
  on cancellation_request (reservation_request_id);
