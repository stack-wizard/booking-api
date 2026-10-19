-- Rooms for a booking: a room block is a quota per room type and night (an OPERA room grid row).
-- Placed = room_resource_id set (a POOL resource of the hotel whose pool_total_units is the room count).
insert into resource_type (code, name, default_time_model)
values ('ROOM', 'Room', 'NIGHT')
on conflict (code) do nothing;

create table booking_room_block (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  booking_id            bigint not null references booking(id) on delete cascade,
  property_tenant_id    bigint null,
  room_resource_id      bigint null references resource(id) on delete restrict,
  room_type_label       text null,
  date_from             date not null,
  date_to               date not null,
  pax_per_room          int not null default 2 check (pax_per_room > 0),
  rate_price_profile_id bigint null references price_profile(id) on delete set null,
  notes                 text null,
  request_id            bigint null references reservation_request(id) on delete set null,
  created_at            timestamptz not null default now(),
  check (date_to > date_from),
  check (room_resource_id is null or property_tenant_id is not null)
);
create index idx_booking_room_block_tenant on booking_room_block (tenant_id);
create index idx_booking_room_block_booking on booking_room_block (booking_id);
create index idx_booking_room_block_resource on booking_room_block (room_resource_id) where room_resource_id is not null;
create index idx_booking_room_block_profile on booking_room_block (rate_price_profile_id) where rate_price_profile_id is not null;
create index idx_booking_room_block_request on booking_room_block (request_id) where request_id is not null;

-- date_to is the departure day; nights run from date_from to date_to - 1.
create table booking_room_block_night (
  id         bigserial primary key,
  tenant_id  bigint not null,
  block_id   bigint not null references booking_room_block(id) on delete cascade,
  night      date not null,
  qty        int not null check (qty >= 0),
  created_at timestamptz not null default now(),
  unique (block_id, night)
);
create index idx_booking_room_block_night_tenant on booking_room_block_night (tenant_id);

alter table reservation
  add column booking_room_block_id bigint null references booking_room_block(id) on delete restrict;
create index idx_reservation_booking_room_block on reservation (booking_room_block_id) where booking_room_block_id is not null;

-- Hotels whose inventory lives in OPERA get booking lines without a local allocation; the request marks
-- that the OPERA block still has to be written (the block sync itself is a separate step).
alter table reservation_request
  add column opera_sync_status text null check (opera_sync_status in ('PENDING','SYNCED','FAILED'));

alter table opera_code_map drop constraint opera_code_map_kind_check;
alter table opera_code_map add constraint opera_code_map_kind_check
  check (kind in ('EVENT_TYPE','SETUP_STYLE','FUNCTION_SPACE','RATE_CODE','ROOM_TYPE'));

create or replace function booking_promote_catalog(p_property bigint, p_org bigint)
returns void
language plpgsql
as $$
declare
  t text;
  org_tables text[] := array[
    'product', 'product_component', 'product_package_listing', 'price_profile',
    'booking', 'booking_order', 'booking_room_block', 'booking_room_block_night', 'booking_status', 'booking_status_flow', 'booking_status_history', 'portal_access_token',
    'sales_quote', 'sales_quote_version', 'sales_quote_line', 'sales_quote_approval',
    'sales_contract', 'sales_contract_document', 'sales_payment_milestone',
    'crm_account', 'crm_account_contact_role', 'crm_account_relation', 'crm_activity', 'crm_alert',
    'crm_assignment_rule', 'crm_attachment', 'crm_contact', 'crm_cost_item',
    'crm_custom_field_definition', 'crm_lead', 'crm_lookup', 'crm_opportunity', 'crm_outcome_reason',
    'crm_pipeline', 'crm_pipeline_stage', 'crm_reassignment', 'crm_segment', 'crm_stage_requirement',
    'crm_stage_transition', 'crm_team', 'crm_team_member', 'crm_team_property'
  ];
begin
  if p_property is null or p_org is null or p_property = p_org then
    return;
  end if;
  foreach t in array org_tables loop
    if exists (
      select 1 from information_schema.columns
      where table_schema = current_schema() and table_name = t and column_name = 'tenant_id'
    ) then
      execute format('update %I set tenant_id = $1 where tenant_id = $2', t) using p_org, p_property;
    end if;
  end loop;

  -- People belong to the chain and work in several hotels. The per-hotel "online" system user stays with its hotel.
  update app_user
  set tenant_id = p_org
  where tenant_id = p_property
    and username not like 'online-system-tenant-%';
end;
$$;
