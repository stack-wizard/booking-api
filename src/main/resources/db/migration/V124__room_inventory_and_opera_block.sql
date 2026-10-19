-- Spaces and rooms can be led by different systems: LOCAL allocation vs OPERA block room grid.
alter table opera_hotel
  add column if not exists room_inventory text not null default 'LOCAL';

alter table opera_hotel
  drop constraint if exists opera_hotel_room_inventory_check;

alter table opera_hotel
  add constraint opera_hotel_room_inventory_check
  check (room_inventory in ('LOCAL', 'OPERA'));

-- Defaults for the OPERA block header when the booking does not carry its own market/source/guarantee.
alter table opera_hotel
  add column if not exists default_market_code text,
  add column if not exists default_source_code text,
  add column if not exists default_non_deduct_reservation_type text,
  add column if not exists default_deduct_reservation_type text;

-- One OPERA block per booking × hotel (mirrors the per-hotel reservation_request).
create table if not exists booking_opera_block (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  booking_id         bigint not null references booking(id) on delete cascade,
  opera_hotel_id     bigint not null references opera_hotel(id) on delete restrict,
  property_tenant_id bigint not null,
  request_id         bigint null references reservation_request(id) on delete set null,
  block_id           text null,
  block_code         text null,
  status_code        text null,
  pm_reservation_id  text null,
  sync_status        text not null,
  last_error         text null,
  last_request       jsonb null,
  last_response      jsonb null,
  synced_at          timestamptz null,
  created_at         timestamptz not null default now(),
  constraint booking_opera_block_sync_status_check
    check (sync_status in ('PENDING', 'SYNCED', 'FAILED')),
  constraint uq_booking_opera_block unique (booking_id, opera_hotel_id)
);

create index if not exists idx_booking_opera_block_tenant on booking_opera_block(tenant_id);
create index if not exists idx_booking_opera_block_booking on booking_opera_block(booking_id);
create index if not exists idx_booking_opera_block_hotel on booking_opera_block(opera_hotel_id);
create index if not exists idx_booking_opera_block_request on booking_opera_block(request_id);
