create table if not exists payment_intent (
  id                      bigserial primary key,
  tenant_id               bigint not null,
  reservation_request_id  bigint not null references reservation_request(id) on delete cascade,
  provider                text not null,
  provider_payment_id     text null,
  provider_order_number   text not null,
  idempotency_key         text not null,
  currency                text not null,
  amount                  numeric(14,2) not null,
  status                  text not null,
  client_secret           text null,
  error_message           text null,
  completed_at            timestamptz null,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  unique (provider, provider_order_number),
  unique (idempotency_key)
);

create index if not exists idx_payment_intent_request_id on payment_intent (reservation_request_id);
create index if not exists idx_payment_intent_tenant on payment_intent (tenant_id);
create index if not exists idx_payment_intent_status on payment_intent (status);

create table if not exists payment_event (
  id                      bigserial primary key,
  payment_intent_id       bigint null references payment_intent(id) on delete set null,
  provider                text not null,
  event_type              text not null,
  provider_event_id       text null,
  payload                 jsonb not null,
  created_at              timestamptz not null default now()
);

create unique index if not exists uq_payment_event_provider_event_id
  on payment_event (provider, provider_event_id)
  where provider_event_id is not null;
