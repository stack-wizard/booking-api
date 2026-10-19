-- JDBC wipe (classpath). Placeholders: __ORG_TENANT_ID__, __PROPERTY_TENANT_ID__.
-- Removes the previous DH demo sales flow (and its EVT-* spaces) so the seed can run from scratch
-- under parent/child tenancy. Catalog products with description 'Event seed' are left; events_catalog
-- is idempotent and will fill gaps after promote.

-- Seeded events may sit on the old hotel tenant_id (before V110) or on the organization.
create temporary table wipe_tenants (tenant_id bigint primary key) on commit drop;
insert into wipe_tenants (tenant_id) values (__ORG_TENANT_ID__)
on conflict do nothing;
insert into wipe_tenants (tenant_id) values (__PROPERTY_TENANT_ID__)
on conflict do nothing;

create temporary table wipe_bookings (id bigint primary key) on commit drop;
insert into wipe_bookings (id)
select e.id from booking e
join wipe_tenants t on t.tenant_id = e.tenant_id
where e.attrs->>'seed' = 'DH_SALES_FLOW';

create temporary table wipe_opportunities (id bigint primary key) on commit drop;
insert into wipe_opportunities (id)
select o.id from crm_opportunity o
join wipe_tenants t on t.tenant_id = o.tenant_id
where o.attrs->>'seed' = 'DH_SALES_FLOW';

create temporary table wipe_quotes (id bigint primary key) on commit drop;
insert into wipe_quotes (id)
select q.id from sales_quote q
where q.booking_id in (select id from wipe_bookings)
   or (q.quote_number = 'Q-DH-SEED-001' and q.tenant_id in (select tenant_id from wipe_tenants));

create temporary table wipe_contracts (id bigint primary key) on commit drop;
insert into wipe_contracts (id)
select c.id from sales_contract c
where c.booking_id in (select id from wipe_bookings)
   or c.quote_id in (select id from wipe_quotes)
   or (c.contract_number = 'C-DH-SEED-001' and c.tenant_id in (select tenant_id from wipe_tenants));

create temporary table wipe_milestones (id bigint primary key) on commit drop;
insert into wipe_milestones (id)
select m.id from sales_payment_milestone m where m.contract_id in (select id from wipe_contracts);

-- Alerts before milestones (FK), contracts before quotes (restrict).
delete from crm_alert
 where booking_id in (select id from wipe_bookings)
    or quote_id in (select id from wipe_quotes)
    or milestone_id in (select id from wipe_milestones);
delete from portal_access_token where booking_id in (select id from wipe_bookings);

delete from sales_payment_milestone where id in (select id from wipe_milestones);
delete from sales_contract_document where contract_id in (select id from wipe_contracts);
delete from sales_contract where id in (select id from wipe_contracts);

delete from sales_quote_approval where quote_id in (select id from wipe_quotes);
delete from sales_quote_line where quote_id in (select id from wipe_quotes);
delete from sales_quote_version where quote_id in (select id from wipe_quotes);
delete from sales_quote where id in (select id from wipe_quotes);

delete from booking_opera_block where booking_id in (select id from wipe_bookings);
delete from booking_order where booking_id in (select id from wipe_bookings);
delete from booking_status_history where booking_id in (select id from wipe_bookings);
delete from booking_function_item
 where booking_function_id in (select id from booking_function where booking_id in (select id from wipe_bookings));
-- reservation.booking_function_id is ON DELETE RESTRICT; allocation follows the reservation.
delete from reservation
 where booking_function_id in (select id from booking_function where booking_id in (select id from wipe_bookings));
delete from reservation
 where booking_room_block_id in (select id from booking_room_block where booking_id in (select id from wipe_bookings));
update booking_function set resource_id = null where booking_id in (select id from wipe_bookings);
delete from booking_function where booking_id in (select id from wipe_bookings);
delete from booking where id in (select id from wipe_bookings);

delete from crm_activity
 where opportunity_id in (select id from wipe_opportunities)
    or (tenant_id in (select tenant_id from wipe_tenants)
        and subject = 'Site visit with Ana — hall and breakout rooms');
delete from crm_cost_item where opportunity_id in (select id from wipe_opportunities);
delete from crm_attachment where opportunity_id in (select id from wipe_opportunities);
delete from crm_stage_transition where opportunity_id in (select id from wipe_opportunities);

update crm_lead set converted_opportunity_id = null
 where converted_opportunity_id in (select id from wipe_opportunities);
delete from crm_lead
 where tenant_id in (select tenant_id from wipe_tenants) and attrs->>'seed' = 'DH_SALES_FLOW';
delete from crm_opportunity where id in (select id from wipe_opportunities);

delete from crm_account_contact_role
 where contact_id in (
   select id from crm_contact
   where tenant_id in (select tenant_id from wipe_tenants) and attrs->>'seed' = 'DH_SALES_FLOW'
 );
delete from crm_contact
 where tenant_id in (select tenant_id from wipe_tenants) and attrs->>'seed' = 'DH_SALES_FLOW';
delete from crm_account
 where tenant_id in (select tenant_id from wipe_tenants) and attrs->>'seed' = 'DH_SALES_FLOW';

-- Demo spaces: legacy EVT-* plus OPERA-aligned SALA1/GALIJA/… (and optional room pools if reseeded).
create temporary table wipe_spaces (id bigint primary key) on commit drop;
insert into wipe_spaces (id)
select id from resource
where tenant_id in (select tenant_id from wipe_tenants)
  and (code like 'EVT-%'
    or code in ('SALA1', 'SAL2', 'GALIJA', 'RES', 'REST', 'GR', 'DBL', 'SAE', 'TWC'));

create temporary table wipe_space_requests (id bigint primary key) on commit drop;
insert into wipe_space_requests (id)
select distinct r.request_id from reservation r
 where r.requested_resource_id in (select id from wipe_spaces)
    or r.id in (select a.reservation_id from allocation a
                 where a.allocated_resource_id in (select id from wipe_spaces)
                    or a.requested_resource_id in (select id from wipe_spaces));
delete from reservation
 where requested_resource_id in (select id from wipe_spaces)
    or id in (select a.reservation_id from allocation a
               where a.allocated_resource_id in (select id from wipe_spaces)
                  or a.requested_resource_id in (select id from wipe_spaces));
delete from reservation_request q
 where q.id in (select id from wipe_space_requests)
   and q.booking_id is not null
   and not exists (select 1 from reservation r where r.request_id = q.id);
update booking_function set resource_id = null where resource_id in (select id from wipe_spaces);
update booking_room_block set room_resource_id = null where room_resource_id in (select id from wipe_spaces);
update product set resource_id = null where resource_id in (select id from wipe_spaces);
update resource set parent_pool_id = null where parent_pool_id in (select id from wipe_spaces);

delete from resource_setup_capacity where resource_id in (select id from wipe_spaces);
delete from resource_composition
 where parent_resource_id in (select id from wipe_spaces)
    or member_resource_id in (select id from wipe_spaces);
delete from opera_code_map
 where tenant_id in (select tenant_id from wipe_tenants)
   and kind = 'FUNCTION_SPACE'
   and local_ref in (select id::text from wipe_spaces);
delete from resource where id in (select id from wipe_spaces);
