-- Platform-wide transactional outbox. Events are written in the same transaction as the business change
-- and delivered at-least-once to subscribers (webhook today). event_uuid is the consumer idempotency key.

create table integration_event (
  id             bigserial primary key,
  tenant_id      bigint not null,
  event_uuid     uuid not null,
  type           text not null,
  version        integer not null default 1,
  aggregate_type text not null,
  aggregate_id   text not null,
  occurred_at    timestamptz not null default now(),
  payload        jsonb not null,
  correlation_id text null,
  created_at     timestamptz not null default now()
);
create unique index uq_integration_event_uuid on integration_event (event_uuid);
create index idx_integration_event_tenant on integration_event (tenant_id, id desc);
create index idx_integration_event_type on integration_event (tenant_id, type, id desc);
create index idx_integration_event_aggregate on integration_event (aggregate_type, aggregate_id);

create table event_subscription (
  id           bigserial primary key,
  tenant_id    bigint not null,
  name         text not null,
  transport    text not null default 'WEBHOOK' check (transport in ('WEBHOOK')),
  endpoint_url text not null,
  secret       text not null,
  event_types  text[] not null default array['*'],
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index uq_event_subscription_name on event_subscription (tenant_id, lower(name));

create table event_delivery (
  id              bigserial primary key,
  tenant_id       bigint not null,
  event_id        bigint not null references integration_event(id) on delete cascade,
  subscription_id bigint not null references event_subscription(id) on delete cascade,
  status          text not null default 'PENDING' check (status in ('PENDING','SENT','DEAD')),
  attempts        integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  lease_until     timestamptz null,
  last_status     integer null,
  last_error      text null,
  sent_at         timestamptz null,
  created_at      timestamptz not null default now()
);
create unique index uq_event_delivery on event_delivery (event_id, subscription_id);
create index idx_event_delivery_due on event_delivery (next_attempt_at) where status = 'PENDING';
create index idx_event_delivery_subscription on event_delivery (subscription_id);
create index idx_event_delivery_tenant on event_delivery (tenant_id);

-- INTERNAL: booking-api sends guest e-mails itself; EXTERNAL_EVENTS: a client's own service sends them
-- from integration events, so booking-api stays silent.
alter table tenant_config
  add column email_delivery text not null default 'INTERNAL' check (email_delivery in ('INTERNAL','EXTERNAL_EVENTS'));
