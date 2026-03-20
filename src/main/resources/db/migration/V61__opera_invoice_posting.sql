alter table invoice
  add column if not exists opera_reservation_id bigint null,
  add column if not exists opera_hotel_code text null,
  add column if not exists opera_posting_status text not null default 'NOT_POSTED',
  add column if not exists opera_posted_at timestamptz null,
  add column if not exists opera_last_request_payload jsonb null,
  add column if not exists opera_last_response_payload jsonb null,
  add column if not exists opera_error_message text null;

update invoice
set opera_posting_status = 'NOT_POSTED'
where opera_posting_status is null
   or upper(opera_posting_status) not in ('NOT_POSTED', 'POSTED', 'FAILED');

update invoice
set opera_posting_status = upper(opera_posting_status)
where opera_posting_status is not null;

alter table invoice
  drop constraint if exists invoice_opera_posting_status_check;

alter table invoice
  add constraint invoice_opera_posting_status_check
  check (opera_posting_status in ('NOT_POSTED', 'POSTED', 'FAILED'));

alter table payment_transaction
  add column if not exists card_type text null;

alter table opera_fiscal_payment_mapping
  add column if not exists card_type text null,
  add column if not exists payment_method_code text null;

update opera_fiscal_payment_mapping
set card_type = upper(trim(card_type))
where card_type is not null
  and btrim(card_type) <> '';

do $$
begin
  if exists (
    select 1
    from pg_constraint
    where conname = 'opera_fiscal_payment_mapping_tenant_id_payment_type_key'
  ) then
    alter table opera_fiscal_payment_mapping
      drop constraint opera_fiscal_payment_mapping_tenant_id_payment_type_key;
  end if;
end $$;

drop index if exists opera_fiscal_payment_mapping_tenant_id_payment_type_key;
drop index if exists uq_opera_fiscal_payment_mapping_generic;
drop index if exists uq_opera_fiscal_payment_mapping_card_type;

create unique index if not exists uq_opera_fiscal_payment_mapping_generic
  on opera_fiscal_payment_mapping (tenant_id, payment_type)
  where card_type is null;

create unique index if not exists uq_opera_fiscal_payment_mapping_card_type
  on opera_fiscal_payment_mapping (tenant_id, payment_type, card_type)
  where card_type is not null;

create table if not exists opera_hotel (
  id                      bigserial primary key,
  tenant_id               bigint not null,
  hotel_code              text not null,
  name                    text null,
  default_cashier_id      bigint null,
  default_folio_window_no int null,
  active                  boolean not null default true,
  created_at              timestamptz not null default now(),
  unique (tenant_id, hotel_code)
);

create index if not exists idx_opera_hotel_tenant
  on opera_hotel (tenant_id);

create table if not exists opera_invoice_type_routing (
  id              bigserial primary key,
  tenant_id       bigint not null,
  invoice_type    text not null,
  hotel_code      text not null,
  reservation_id  bigint not null,
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  constraint opera_invoice_type_routing_invoice_type_check
    check (invoice_type in ('INVOICE', 'DEPOSIT', 'INVOICE_STORNO', 'DEPOSIT_STORNO', 'ROOM_CHARGE')),
  unique (tenant_id, invoice_type, hotel_code)
);

create index if not exists idx_opera_invoice_type_routing_tenant
  on opera_invoice_type_routing (tenant_id);

alter table tenant_integration_config
  add column if not exists app_key text null,
  add column if not exists access_token text null;

alter table tenant_integration_config
  drop constraint if exists tenant_integration_config_integration_type_check;

alter table tenant_integration_config
  add constraint tenant_integration_config_integration_type_check
  check (integration_type in ('PAYMENT', 'BOOKING', 'FISCALIZATION', 'PMS'));
