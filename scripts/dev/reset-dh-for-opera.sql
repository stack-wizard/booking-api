-- One-shot local reset for Demo Hotel Group (org) + Demo Hotel DH (property), ready for OPERA-led sales.
-- Keeps: platform_tenant_mapping, tenant_integration_config (OPERA credentials), opera_hotel rows.
-- Clears: bookings, CRM deals, sales docs, local spaces/room pools, imported OPERA codes and mappings,
--         booking statuses, event types and setup styles.
-- Then: restart the API (demo seed is off in the local profile) and run Opera mapping → "Setup from OPERA".
--
-- Usage:
--   PGPASSWORD=booking_pass psql -h localhost -U booking_user -d booking_db -f scripts/dev/reset-dh-for-opera.sql

\set ON_ERROR_STOP on
begin;

create temporary table wipe_tenants (tenant_id bigint primary key) on commit drop;
insert into wipe_tenants (tenant_id)
select tenant_id from platform_tenant_mapping
 where platform_tenant_id in (
   '11111111-1111-1111-1111-111111111111'::uuid,
   '21111111-1111-1111-1111-111111111111'::uuid
 );

do $$
begin
  if not exists (select 1 from wipe_tenants) then
    raise exception 'Demo Hotel Group / DH platform tenants are not mapped';
  end if;
end $$;

create temporary table wipe_bookings (id bigint primary key) on commit drop;
insert into wipe_bookings (id)
select b.id from booking b where b.tenant_id in (select tenant_id from wipe_tenants);

create temporary table wipe_opportunities (id bigint primary key) on commit drop;
insert into wipe_opportunities (id)
select o.id from crm_opportunity o where o.tenant_id in (select tenant_id from wipe_tenants);

create temporary table wipe_quotes (id bigint primary key) on commit drop;
insert into wipe_quotes (id)
select q.id from sales_quote q where q.tenant_id in (select tenant_id from wipe_tenants);

create temporary table wipe_contracts (id bigint primary key) on commit drop;
insert into wipe_contracts (id)
select c.id from sales_contract c where c.tenant_id in (select tenant_id from wipe_tenants);

create temporary table wipe_milestones (id bigint primary key) on commit drop;
insert into wipe_milestones (id)
select m.id from sales_payment_milestone m where m.contract_id in (select id from wipe_contracts);

delete from crm_alert
 where booking_id in (select id from wipe_bookings)
    or quote_id in (select id from wipe_quotes)
    or milestone_id in (select id from wipe_milestones)
    or tenant_id in (select tenant_id from wipe_tenants);
delete from portal_access_token where booking_id in (select id from wipe_bookings);

delete from sales_payment_milestone where id in (select id from wipe_milestones);
delete from sales_contract_document where contract_id in (select id from wipe_contracts);
delete from sales_contract where id in (select id from wipe_contracts);
delete from sales_quote_approval where quote_id in (select id from wipe_quotes);
delete from sales_quote_line where quote_id in (select id from wipe_quotes);
delete from sales_quote_version where quote_id in (select id from wipe_quotes);
delete from sales_quote where id in (select id from wipe_quotes);

delete from booking_opera_block where booking_id in (select id from wipe_bookings)
   or tenant_id in (select tenant_id from wipe_tenants);
delete from booking_order where booking_id in (select id from wipe_bookings);
delete from booking_status_history where booking_id in (select id from wipe_bookings);

delete from reservation
 where booking_function_id in (select id from booking_function where booking_id in (select id from wipe_bookings))
    or booking_room_block_id in (select id from booking_room_block where booking_id in (select id from wipe_bookings))
    or tenant_id in (select tenant_id from wipe_tenants);

delete from booking_function_item
 where booking_function_id in (select id from booking_function where booking_id in (select id from wipe_bookings));
delete from booking_function where booking_id in (select id from wipe_bookings);
delete from booking_room_block_night
 where block_id in (select id from booking_room_block where booking_id in (select id from wipe_bookings));
delete from booking_room_block where booking_id in (select id from wipe_bookings);

delete from reservation_request
 where tenant_id in (select tenant_id from wipe_tenants)
   and (booking_id in (select id from wipe_bookings) or booking_id is not null);

delete from booking where id in (select id from wipe_bookings);

delete from crm_activity
 where opportunity_id in (select id from wipe_opportunities)
    or tenant_id in (select tenant_id from wipe_tenants);
delete from crm_cost_item where opportunity_id in (select id from wipe_opportunities)
   or tenant_id in (select tenant_id from wipe_tenants);
delete from crm_attachment where opportunity_id in (select id from wipe_opportunities)
   or tenant_id in (select tenant_id from wipe_tenants);
delete from crm_stage_transition where opportunity_id in (select id from wipe_opportunities);

update crm_lead set converted_opportunity_id = null
 where converted_opportunity_id in (select id from wipe_opportunities);
delete from crm_opportunity where id in (select id from wipe_opportunities);
delete from crm_lead where tenant_id in (select tenant_id from wipe_tenants);

delete from crm_account_contact_role
 where account_id in (select id from crm_account where tenant_id in (select tenant_id from wipe_tenants));
delete from crm_contact where tenant_id in (select tenant_id from wipe_tenants);
delete from crm_account where tenant_id in (select tenant_id from wipe_tenants);

-- Local spaces + room pools for the hotel (recreated from Opera-aligned seed / Sync room types).
create temporary table wipe_spaces (id bigint primary key) on commit drop;
insert into wipe_spaces (id)
select r.id
from resource r
join resource_type rt on rt.id = r.resource_type_id
where r.tenant_id in (select tenant_id from wipe_tenants)
  and (
    r.code like 'EVT-%'
    or r.code in ('SALA1', 'SAL2', 'GALIJA', 'RES', 'REST', 'GR', 'DBL', 'SAE', 'TWC', 'PI', 'PM')
    or upper(rt.code) in ('MEETING_ROOM', 'MEETING_OFFICE', 'COMPOSITION', 'ROOM', 'APARTMENT')
  );

delete from allocation
 where allocated_resource_id in (select id from wipe_spaces)
    or requested_resource_id in (select id from wipe_spaces)
    or tenant_id in (select tenant_id from wipe_tenants);

delete from reservation
 where requested_resource_id in (select id from wipe_spaces)
    or id in (select a.reservation_id from allocation a
               where a.allocated_resource_id in (select id from wipe_spaces)
                  or a.requested_resource_id in (select id from wipe_spaces));

update booking_function set resource_id = null where resource_id in (select id from wipe_spaces);
update booking_room_block set room_resource_id = null where room_resource_id in (select id from wipe_spaces);
update product set resource_id = null where resource_id in (select id from wipe_spaces);
update resource set parent_pool_id = null where parent_pool_id in (select id from wipe_spaces);

delete from resource_setup_capacity where resource_id in (select id from wipe_spaces);
delete from resource_composition
 where parent_resource_id in (select id from wipe_spaces)
    or member_resource_id in (select id from wipe_spaces);
delete from opera_code_map
 where tenant_id in (select tenant_id from wipe_tenants);
delete from resource where id in (select id from wipe_spaces);

-- Old fictional event products (new seed recreates Opera-named ones).
delete from product_package_listing
 where product_id in (select id from product where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed');
delete from product_component
 where package_product_id in (select id from product where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed')
    or component_product_id in (select id from product where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed');
delete from product_extra_uom
 where product_id in (select id from product where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed');
delete from price_list
 where product_id in (select id from product where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed');
delete from product
 where tenant_id in (select tenant_id from wipe_tenants) and description = 'Event seed';

-- Code lists / statuses — Setup from OPERA imports them again.
delete from booking_status_flow where tenant_id in (select tenant_id from wipe_tenants);
delete from booking_status where tenant_id in (select tenant_id from wipe_tenants);
delete from crm_lookup
 where tenant_id in (select tenant_id from wipe_tenants)
   and kind in ('EVENT_TYPE', 'SETUP_STYLE');
delete from opera_code
 where opera_hotel_id in (select id from opera_hotel where tenant_id in (select tenant_id from wipe_tenants));

-- DH is fully OPERA-led: spaces become OPERA events, rooms the OPERA room grid, charges go to the block PM.
update opera_hotel h
set space_inventory = 'OPERA',
    room_inventory = 'OPERA',
    billing = 'OPERA',
    default_market_code = coalesce(nullif(trim(h.default_market_code), ''), 'PKG'),
    default_source_code = coalesce(nullif(trim(h.default_source_code), ''), 'EML'),
    default_non_deduct_reservation_type = coalesce(nullif(trim(h.default_non_deduct_reservation_type), ''), 'GNON'),
    default_deduct_reservation_type = coalesce(nullif(trim(h.default_deduct_reservation_type), ''), 'GDED'),
    default_origin_code = coalesce(nullif(trim(h.default_origin_code), ''), 'EMAIL'),
    default_block_owner_code = coalesce(nullif(trim(h.default_block_owner_code), ''), 'ALL'),
    default_cashier_id = coalesce(h.default_cashier_id,
        (select o.default_cashier_id from opera_hotel o
          where o.hotel_code = h.hotel_code and o.id <> h.id and o.default_cashier_id is not null
          order by o.id limit 1)),
    default_folio_window_no = coalesce(h.default_folio_window_no, 1)
where h.tenant_id = (
  select tenant_id from platform_tenant_mapping
  where platform_tenant_id = '21111111-1111-1111-1111-111111111111'::uuid
);

-- Default OPERA transaction code for catering / rental posted to the PM until products are mapped one by one.
insert into opera_fiscal_charge_mapping (tenant_id, trx_code, description, priority)
select m.tenant_id, '50000', 'Default charge line (events)', 1000
  from platform_tenant_mapping m
 where m.platform_tenant_id = '21111111-1111-1111-1111-111111111111'::uuid
   and not exists (
     select 1 from opera_fiscal_charge_mapping c
      where c.tenant_id = m.tenant_id and c.product_id is null and c.product_type is null and c.active
   );

select 'reset-dh-for-opera done' as status,
       (select count(*) from wipe_tenants) as tenants,
       (select count(*) from wipe_bookings) as bookings_removed;

commit;
