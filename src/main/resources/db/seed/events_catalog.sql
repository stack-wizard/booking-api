-- JDBC seed (classpath). Placeholders replaced at runtime: __TENANT_ID__ = chain (catalog, CRM), __PROPERTY_TENANT_ID__ = hotel (spaces, capacity). Not a Flyway migration.
-- Dev seed for booking management (Faza 3/4). Not a migration; safe to re-run.
-- Usage: docker exec -i booking-postgres psql -U booking_user -d booking_db -v tenant=2 < scripts/dev/seed-events.sql
-- Also called by scripts/dev/seed-dh-sales-flow.sh when Opera hotel DH exists (never via Flyway/deploy).



-- Price profile with a validity window (rent and catering are priced from price_list).
insert into price_profile (tenant_id, name, currency)
select __TENANT_ID__, 'Events EUR', 'EUR'
where not exists (select 1 from price_profile where tenant_id = __TENANT_ID__ and name = 'Events EUR');

insert into price_profile_date (price_profile_id, date_from, date_to, description)
select pp.id, date '2026-01-01', date '2027-12-31', 'EVENTS'
from price_profile pp
where pp.tenant_id = __TENANT_ID__ and pp.name = 'Events EUR'
  and not exists (select 1 from price_profile_date d where d.price_profile_id = pp.id);

-- Next season starts empty; prices are copied into it from the Products screen.
insert into price_profile_date (price_profile_id, date_from, date_to, description)
select pp.id, date '2028-01-01', date '2028-12-31', 'EVENTS 2028'
from price_profile pp
where pp.tenant_id = __TENANT_ID__ and pp.name = 'Events EUR'
  and not exists (select 1 from price_profile_date d where d.price_profile_id = pp.id and d.date_from = date '2028-01-01');

-- Products: rent (per space), catering, equipment.
insert into uom (code, name) values ('NIGHT', 'Night') on conflict (code) do nothing;

-- Product / space names follow OPERA hotel DH (functionSpaces + roomTypes).
create temporary table seed_product (name text, uom text, extra text[], price numeric, extra_prices numeric[], tax numeric, ord int) on commit drop;
insert into seed_product values
  ('Sala1 rent',         'DAY',  array['HALF_DAY','HOUR'], 1200, array[750, 180],  25, 10),
  ('Sala 2 rent',        'DAY',  array['HALF_DAY','HOUR'], 800,  array[500, 120],  25, 11),
  ('Galija rent',        'DAY',  array['HALF_DAY','HOUR'], 900,  array[550, 140],  25, 12),
  ('Restoran rent',      'DAY',  array['HALF_DAY','HOUR'], 700,  array[450, 100],  25, 13),
  ('GALRES rent',        'DAY',  array['HALF_DAY','HOUR'], 1600, array[1000, 250], 25, 14),
  ('Coffee break',       'UNIT', array[]::text[],          8,    array[]::numeric[], 13, 20),
  ('Business lunch',     'UNIT', array[]::text[],          28,   array[]::numeric[], 13, 21),
  ('Gala dinner',        'UNIT', array[]::text[],          55,   array[]::numeric[], 13, 22),
  ('Welcome drink',      'UNIT', array[]::text[],          12,   array[]::numeric[], 13, 23),
  ('Projector & screen', 'UNIT', array[]::text[],          60,   array[]::numeric[], 25, 30),
  ('Day Delegate Rate',  'UNIT', array[]::text[],          65,   array[]::numeric[], 13, 40),
  ('Half-day DDR',       'UNIT', array[]::text[],          0,    array[]::numeric[], 13, 41),
  ('DBL night',          'NIGHT', array[]::text[],         120,  array[]::numeric[], 13, 50),
  ('SAE night',          'NIGHT', array[]::text[],         260,  array[]::numeric[], 13, 51),
  ('TWC night',          'NIGHT', array[]::text[],         115,  array[]::numeric[], 13, 52);

insert into product (tenant_id, name, default_uom, product_type, tax1_percent, display_order, description)
select __TENANT_ID__, s.name, s.uom, 'SEALABLE_PRODUCT', s.tax, s.ord, 'Event seed'
from seed_product s
where not exists (select 1 from product p where p.tenant_id = __TENANT_ID__ and p.name = s.name);

insert into product_extra_uom (product_id, uom)
select p.id, u.uom
from seed_product s
join product p on p.tenant_id = __TENANT_ID__ and p.name = s.name
cross join lateral unnest(s.extra) as u(uom)
on conflict do nothing;

insert into price_list (product_id, uom, price, price_profile_id, price_profile_date_id)
select p.id, x.uom, x.price, pp.id, d.id
from seed_product s
join product p on p.tenant_id = __TENANT_ID__ and p.name = s.name
cross join lateral (
  select s.uom as uom, s.price as price where s.name <> 'Half-day DDR'
  union all
  select e.uom, e.price from unnest(s.extra, s.extra_prices) as e(uom, price)
) x
join price_profile pp on pp.tenant_id = __TENANT_ID__ and pp.name = 'Events EUR'
join price_profile_date d on d.price_profile_id = pp.id and d.date_from = date '2026-01-01'
where not exists (
  select 1 from price_list l where l.product_id = p.id and upper(l.uom) = upper(x.uom) and l.price_profile_id = pp.id
);

-- Function spaces = OPERA roomCode / spaceName for hotel DH (space_inventory LOCAL; mapped 1:1).
-- Room-type pools are NOT seeded here — Import codes + Sync room types creates DBL / SAE / TWC.
create temporary table seed_space (code text, name text, type_code text, product text, area numeric, floor text, light boolean, ord int) on commit drop;
insert into seed_space values
  ('SALA1',  'Sala1',    'MEETING_ROOM', 'Sala1 rent',    120, 'Ground', true,  1),
  ('SAL2',   'Sala 2',   'MEETING_ROOM', 'Sala 2 rent',   80,  'Ground', true,  2),
  ('GALIJA', 'Galija',   'MEETING_ROOM', 'Galija rent',   100, 'Ground', true,  3),
  ('RES',    'restoran', 'MEETING_ROOM', 'Restoran rent', 90,  'Ground', true,  4),
  ('REST',   'Restoran', 'MEETING_ROOM', 'Restoran rent', 90,  'Ground', true,  5),
  ('GR',     'GALRES',   'COMPOSITION',  'GALRES rent',   190, 'Ground', true,  6);

insert into resource (tenant_id, resource_type_id, kind, code, name, status, unit_count, cap_total, product_id,
                      can_book_alone, display_order, area_sqm, floor, natural_light)
select __PROPERTY_TENANT_ID__, rt.id, 'EXACT', s.code, s.name, 'ACTIVE', 1, 0, p.id, true, s.ord, s.area, s.floor, s.light
from seed_space s
join resource_type rt on rt.code = s.type_code
join product p on p.tenant_id = __TENANT_ID__ and p.name = s.product
on conflict (tenant_id, code) do nothing;

-- OPERA combo space GR (GALRES) = Galija + restoran.
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select __PROPERTY_TENANT_ID__, parent.id, member.id, 1
from resource parent
join resource member on member.tenant_id = __PROPERTY_TENANT_ID__ and member.code in ('GALIJA', 'RES')
where parent.tenant_id = __PROPERTY_TENANT_ID__ and parent.code = 'GR'
on conflict (parent_resource_id, member_resource_id) do nothing;

-- Setup styles = OPERA codes (THEATER, CLASS, USHAPE, AS, TBD).
create temporary table seed_setup (code text, style text, capacity int) on commit drop;
insert into seed_setup values
  ('SALA1',  'THEATER', 180), ('SALA1',  'CLASS', 100), ('SALA1',  'USHAPE', 40), ('SALA1',  'AS', 180),
  ('SAL2',   'THEATER', 80),  ('SAL2',   'CLASS', 50),  ('SAL2',   'USHAPE', 24), ('SAL2',   'TBD', 80),
  ('GALIJA', 'THEATER', 120), ('GALIJA', 'CLASS', 70),  ('GALIJA', 'USHAPE', 30), ('GALIJA', 'AS', 120),
  ('RES',    'THEATER', 100), ('RES',    'CLASS', 60),  ('RES',    'AS', 100),
  ('REST',   'THEATER', 100), ('REST',   'CLASS', 60),  ('REST',   'AS', 100),
  ('GR',     'THEATER', 220), ('GR',     'CLASS', 130), ('GR',     'USHAPE', 50), ('GR',     'AS', 220);

insert into resource_setup_capacity (tenant_id, resource_id, setup_style, capacity)
select __PROPERTY_TENANT_ID__, r.id, s.style, s.capacity
from seed_setup s
join resource r on r.tenant_id = __PROPERTY_TENANT_ID__ and r.code = s.code
on conflict (resource_id, setup_style) do nothing;

-- Booking calendar for the tenant (grid/duration rules); events use it like any other booking.
insert into booking_calendar (tenant_id, location_node_id, open_time, close_time, grid_minutes, min_duration_minutes,
                              max_duration_minutes, zone)
select __PROPERTY_TENANT_ID__, null, time '07:00', time '23:00', 30, 30, 960, 'Europe/Zagreb'
where not exists (select 1 from booking_calendar where tenant_id = __PROPERTY_TENANT_ID__);

-- Packages. DDR: package price split by percentage; Half-day DDR: sum of components.
update product set package_pricing = 'SPLIT_PERCENT'
where tenant_id = __TENANT_ID__ and name = 'Day Delegate Rate' and package_pricing is null;
update product set package_pricing = 'SUM'
where tenant_id = __TENANT_ID__ and name = 'Half-day DDR' and package_pricing is null;

create temporary table seed_component (pkg text, component text, qty int, basis text, share numeric,
                                       function_type text, start_offset int, duration int, ord int) on commit drop;
insert into seed_component values
  ('Day Delegate Rate', 'Sala1 rent',     1, 'PER_PAX', 40, 'PLENARY',      0,   480, 0),
  ('Day Delegate Rate', 'Coffee break',   2, 'PER_PAX', 20, 'COFFEE_BREAK', 90,  30,  1),
  ('Day Delegate Rate', 'Business lunch', 1, 'PER_PAX', 40, 'LUNCH',        210, 60,  2),
  ('Half-day DDR',      'Sala 2 rent',    1, 'FIXED',   null, 'PLENARY',    0,   240, 0),
  ('Half-day DDR',      'Coffee break',   1, 'PER_PAX', null, 'COFFEE_BREAK', 120, 30, 1);

insert into product_component (tenant_id, package_product_id, component_product_id, qty, qty_basis, included,
                               share_percent, function_type, start_offset_minutes, duration_minutes, display_order)
select __TENANT_ID__, pkg.id, comp.id, c.qty, c.basis, true, c.share, c.function_type, c.start_offset, c.duration, c.ord
from seed_component c
join product pkg on pkg.tenant_id = __TENANT_ID__ and pkg.name = c.pkg
join product comp on comp.tenant_id = __TENANT_ID__ and comp.name = c.component
where not exists (select 1 from product_component pc where pc.package_product_id = pkg.id);

-- Phase 5: quote groups, advance invoices need a DEPOSIT product.
update product set sales_group = 'MEETING'
where tenant_id = __TENANT_ID__ and name like '%rent' and sales_group is null;
update product set sales_group = 'F_AND_B'
where tenant_id = __TENANT_ID__ and name in ('Coffee break','Business lunch','Gala dinner','Welcome drink') and sales_group is null;
update product set sales_group = 'AV'
where tenant_id = __TENANT_ID__ and name = 'Projector & screen' and sales_group is null;

insert into product (tenant_id, name, default_uom, product_type, tax1_percent, display_order, description)
select __TENANT_ID__, 'Deposit', 'UNIT', 'DEPOSIT', 25, 900, 'Advance payment'
where not exists (select 1 from product p where p.tenant_id = __TENANT_ID__ and upper(p.product_type) = 'DEPOSIT');

-- Phase 4 CMS: the DDR is published for the web shop.
insert into product_package_listing (tenant_id, product_id, public_name, public_description, min_pax, max_pax,
                                     duration, default_start_time, setup_style, published)
select __TENANT_ID__, p.id, 'Day Delegate Package',
       'Sala1 for the day, two coffee breaks and a business lunch.', 10, 120,
       'FULL_DAY', '09:00', 'THEATER', true
from product p
where p.tenant_id = __TENANT_ID__ and p.name = 'Day Delegate Rate'
on conflict (product_id) do nothing;

-- Phase 7: sales teams (Sales → MICE / Leisure & Groups), segments routed to teams, lead assignment rules.
-- Members come from Platform users after their first login, so add them in CRM Setup → Teams.
insert into crm_team (tenant_id, name)
select __TENANT_ID__, 'Sales'
where not exists (select 1 from crm_team where tenant_id = __TENANT_ID__ and lower(name) = 'sales');

insert into crm_team (tenant_id, name, parent_team_id)
select __TENANT_ID__, t.name, (select id from crm_team where tenant_id = __TENANT_ID__ and lower(name) = 'sales')
from (values ('MICE'), ('Leisure & Groups')) as t(name)
where not exists (select 1 from crm_team c where c.tenant_id = __TENANT_ID__ and lower(c.name) = lower(t.name));

insert into crm_segment (tenant_id, code, name, default_team_id, display_order)
select __TENANT_ID__, s.code, s.name, (select id from crm_team where tenant_id = __TENANT_ID__ and name = s.team), s.ord
from (values ('CORPORATE', 'Corporate', 'MICE', 10),
             ('ASSOCIATION', 'Associations', 'MICE', 20),
             ('AGENCY', 'Agencies & DMC', 'Leisure & Groups', 30),
             ('LEISURE', 'Leisure groups', 'Leisure & Groups', 40),
             ('SOCIAL', 'Weddings & private', 'Leisure & Groups', 50)) as s(code, name, team, ord)
where not exists (select 1 from crm_segment x where x.tenant_id = __TENANT_ID__ and lower(x.code) = lower(s.code));

insert into crm_assignment_rule (tenant_id, name, priority, min_pax, target_team_id, strategy)
select __TENANT_ID__, 'Large events (150+ pax)', 10, 150, t.id, 'LEAST_LOADED'
from crm_team t
where t.tenant_id = __TENANT_ID__ and t.name = 'MICE'
  and not exists (select 1 from crm_assignment_rule r where r.tenant_id = __TENANT_ID__ and r.name = 'Large events (150+ pax)');

insert into crm_assignment_rule (tenant_id, name, priority, segment, target_team_id, strategy)
select __TENANT_ID__, 'Corporate inquiries', 20, 'CORPORATE', t.id, 'ROUND_ROBIN'
from crm_team t
where t.tenant_id = __TENANT_ID__ and t.name = 'MICE'
  and not exists (select 1 from crm_assignment_rule r where r.tenant_id = __TENANT_ID__ and r.name = 'Corporate inquiries');

-- ─── Align local catalog with imported OPERA codes for hotel DH ─────────────
-- Requires a prior Import codes for the property's opera_hotel. Safe to re-run.

-- Hotel modes: spaces LOCAL (allocation), rooms OPERA (block grid), billing OPERA (charges → block PM).
update opera_hotel h
set space_inventory = 'LOCAL',
    room_inventory = 'OPERA',
    billing = 'OPERA',
    default_market_code = coalesce(nullif(trim(h.default_market_code), ''), 'PKG'),
    default_source_code = coalesce(nullif(trim(h.default_source_code), ''), 'EML'),
    default_non_deduct_reservation_type = coalesce(nullif(trim(h.default_non_deduct_reservation_type), ''), 'GNON'),
    default_deduct_reservation_type = coalesce(nullif(trim(h.default_deduct_reservation_type), ''), 'GDED'),
    default_origin_code = coalesce(nullif(trim(h.default_origin_code), ''), 'EMAIL'),
    default_block_owner_code = coalesce(nullif(trim(h.default_block_owner_code), ''), 'ALL')
where h.tenant_id = __PROPERTY_TENANT_ID__
  and h.hotel_code = 'DH';

-- SETUP_STYLE / EVENT_TYPE code lists from OPERA (deactivate fictional defaults that clash).
update crm_lookup l
set active = false
where l.tenant_id = __TENANT_ID__
  and l.kind in ('SETUP_STYLE', 'EVENT_TYPE')
  and not exists (
    select 1 from opera_code oc
    join opera_hotel oh on oh.id = oc.opera_hotel_id
    where oh.tenant_id = __PROPERTY_TENANT_ID__
      and oc.active
      and ((l.kind = 'SETUP_STYLE' and oc.kind = 'SETUP_STYLE')
        or (l.kind = 'EVENT_TYPE' and oc.kind = 'EVENT_TYPE'))
      and upper(oc.code) = upper(l.code)
  );

insert into crm_lookup (tenant_id, kind, code, label, display_order, active)
select __TENANT_ID__,
       case when oc.kind = 'SETUP_STYLE' then 'SETUP_STYLE' else 'EVENT_TYPE' end,
       oc.code,
       coalesce(nullif(trim(oc.description), ''), oc.code),
       10 + row_number() over (partition by oc.kind order by oc.code) * 10,
       true
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.active
  and oc.kind in ('SETUP_STYLE', 'EVENT_TYPE')
  and not exists (
    select 1 from crm_lookup x
    where x.tenant_id = __TENANT_ID__
      and x.kind = case when oc.kind = 'SETUP_STYLE' then 'SETUP_STYLE' else 'EVENT_TYPE' end
      and upper(x.code) = upper(oc.code)
  );

update crm_lookup l
set label = coalesce(nullif(trim(oc.description), ''), oc.code),
    active = true
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.active
  and oc.kind in ('SETUP_STYLE', 'EVENT_TYPE')
  and l.tenant_id = __TENANT_ID__
  and l.kind = oc.kind
  and upper(l.code) = upper(oc.code);

-- Room-type pools from OPERA ROOM_TYPE (skip pseudo / meetingRoom), same as Sync room types.
insert into resource (tenant_id, resource_type_id, kind, code, name, status, unit_count, pool_total_units,
                      cap_total, can_book_alone, display_order, opera_room_id, product_id)
select oh.tenant_id, rt.id, 'POOL', oc.code,
       coalesce(nullif(trim(oc.description), ''), oc.code),
       'ACTIVE', 1,
       greatest(0, coalesce((oc.attrs->>'numberOfRooms')::int, 0)),
       0, true, 20 + row_number() over (order by oc.code),
       oc.code,
       (select p.id from product p
         where p.tenant_id = __TENANT_ID__ and p.name = oc.code || ' night' limit 1)
from opera_hotel oh
join opera_code oc on oc.opera_hotel_id = oh.id and oc.kind = 'ROOM_TYPE' and oc.active
join resource_type rt on rt.code = 'ROOM'
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and coalesce(oc.attrs->>'pseudo', 'false') not in ('true', 't', '1', 'Y', 'y')
  and coalesce(oc.attrs->>'meetingRoom', 'false') not in ('true', 't', '1', 'Y', 'y')
  and not exists (
    select 1 from resource r where r.tenant_id = oh.tenant_id and upper(r.code) = upper(oc.code)
  );

update resource r
set name = coalesce(nullif(trim(oc.description), ''), oc.code),
    kind = 'POOL',
    pool_total_units = greatest(0, coalesce((oc.attrs->>'numberOfRooms')::int, 0)),
    opera_room_id = oc.code,
    status = 'ACTIVE'
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.kind = 'ROOM_TYPE'
  and oc.active
  and r.tenant_id = oh.tenant_id
  and upper(r.code) = upper(oc.code)
  and coalesce(oc.attrs->>'pseudo', 'false') not in ('true', 't', '1', 'Y', 'y')
  and coalesce(oc.attrs->>'meetingRoom', 'false') not in ('true', 't', '1', 'Y', 'y');

insert into opera_code_map (tenant_id, opera_hotel_id, kind, local_ref, opera_code)
select oh.tenant_id, oh.id, 'ROOM_TYPE', r.id::text, oc.code
from opera_hotel oh
join opera_code oc on oc.opera_hotel_id = oh.id and oc.kind = 'ROOM_TYPE' and oc.active
join resource r on r.tenant_id = oh.tenant_id and upper(r.code) = upper(oc.code) and r.kind = 'POOL'
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and coalesce(oc.attrs->>'pseudo', 'false') not in ('true', 't', '1', 'Y', 'y')
  and coalesce(oc.attrs->>'meetingRoom', 'false') not in ('true', 't', '1', 'Y', 'y')
  and not exists (
    select 1 from opera_code_map m
    where m.opera_hotel_id = oh.id and m.kind = 'ROOM_TYPE' and m.local_ref = r.id::text
  );

-- Map function spaces by matching resource.code ↔ OPERA FUNCTION_SPACE code.
insert into opera_code_map (tenant_id, opera_hotel_id, kind, local_ref, opera_code)
select oh.tenant_id, oh.id, 'FUNCTION_SPACE', r.id::text, oc.code
from opera_hotel oh
join resource r on r.tenant_id = oh.tenant_id
join opera_code oc on oc.opera_hotel_id = oh.id and oc.kind = 'FUNCTION_SPACE' and oc.active
                  and upper(oc.code) = upper(r.code)
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and not exists (
    select 1 from opera_code_map m
    where m.opera_hotel_id = oh.id and m.kind = 'FUNCTION_SPACE' and m.local_ref = r.id::text
  );

update opera_code_map m
set opera_code = oc.code
from opera_hotel oh
join resource r on r.tenant_id = oh.tenant_id
join opera_code oc on oc.opera_hotel_id = oh.id and oc.kind = 'FUNCTION_SPACE' and oc.active
                  and upper(oc.code) = upper(r.code)
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and m.opera_hotel_id = oh.id
  and m.kind = 'FUNCTION_SPACE'
  and m.local_ref = r.id::text;

-- Map setup / event types by code.
insert into opera_code_map (tenant_id, opera_hotel_id, kind, local_ref, opera_code)
select oh.tenant_id, oh.id, oc.kind, oc.code, oc.code
from opera_hotel oh
join opera_code oc on oc.opera_hotel_id = oh.id and oc.active and oc.kind in ('SETUP_STYLE', 'EVENT_TYPE')
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and not exists (
    select 1 from opera_code_map m
    where m.opera_hotel_id = oh.id and m.kind = oc.kind and m.local_ref = oc.code
  );

-- Map Events EUR → FJBB (override-friendly group rate); fall back to BARBB then first RATE_CODE.
insert into opera_code_map (tenant_id, opera_hotel_id, kind, local_ref, opera_code)
select oh.tenant_id, oh.id, 'RATE_CODE', pp.id::text,
       coalesce(
         (select oc.code from opera_code oc
           where oc.opera_hotel_id = oh.id and oc.kind = 'RATE_CODE' and oc.active and oc.code = 'FJBB' limit 1),
         (select oc.code from opera_code oc
           where oc.opera_hotel_id = oh.id and oc.kind = 'RATE_CODE' and oc.active and oc.code = 'BARBB' limit 1),
         (select oc.code from opera_code oc
           where oc.opera_hotel_id = oh.id and oc.kind = 'RATE_CODE' and oc.active order by oc.code limit 1)
       )
from opera_hotel oh
join price_profile pp on pp.tenant_id = __TENANT_ID__ and pp.name = 'Events EUR'
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and exists (select 1 from opera_code oc where oc.opera_hotel_id = oh.id and oc.kind = 'RATE_CODE' and oc.active)
  and not exists (
    select 1 from opera_code_map m
    where m.opera_hotel_id = oh.id and m.kind = 'RATE_CODE' and m.local_ref = pp.id::text
  );

-- Prefer FJBB when Events EUR was previously mapped to something else.
update opera_code_map m
set opera_code = 'FJBB'
from opera_hotel oh
join price_profile pp on pp.tenant_id = __TENANT_ID__ and pp.name = 'Events EUR'
where m.opera_hotel_id = oh.id
  and oh.tenant_id = __PROPERTY_TENANT_ID__
  and oh.hotel_code = 'DH'
  and m.kind = 'RATE_CODE'
  and m.local_ref = pp.id::text
  and exists (
    select 1 from opera_code oc
    where oc.opera_hotel_id = oh.id and oc.kind = 'RATE_CODE' and oc.active and oc.code = 'FJBB'
  );

-- Booking statuses from OPERA block statuses (phase from cateringStatusType / roomStatusType).
insert into booking_status (tenant_id, code, label, phase, color, is_start, display_order, active, opera_code)
select __TENANT_ID__,
       upper(regexp_replace(oc.code, '[^A-Za-z0-9_]+', '_', 'g')),
       coalesce(nullif(trim(oc.description), ''), oc.code),
       case
         when coalesce(oc.attrs->>'roomStatusType', oc.attrs->>'cateringStatusType') = 'Lost' then 'LOST'
         when coalesce(oc.attrs->>'roomStatusType', oc.attrs->>'cateringStatusType') = 'Refused' then 'TURNED_DOWN'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Inquiry' then 'INQUIRY'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'NonDeductInv' then 'TENTATIVE'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'DeductInv' then 'DEFINITE'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Actual' then 'ACTUAL'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Cancel' then 'CANCELLED'
         when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Waitlist' then 'INQUIRY'
         else 'INQUIRY'
       end,
       case lower(oc.attrs->>'color')
         when 'blue' then '#2563EB'
         when 'brown' then '#92400E'
         when 'gray' then '#6B7280'
         when 'green' then '#16A34A'
         when 'cyan' then '#0891B2'
         when 'yellow' then '#CA8A04'
         when 'red' then '#DC2626'
         else null
       end,
       coalesce((oc.attrs->>'starting')::boolean, false)
         and coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Inquiry',
       100 + coalesce((oc.attrs->>'orderBy')::int, 0) * 10,
       true,
       oc.code
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.kind = 'BLOCK_STATUS'
  and oc.active
  and not exists (
    select 1 from booking_status s
    where s.tenant_id = __TENANT_ID__
      and upper(s.code) = upper(regexp_replace(oc.code, '[^A-Za-z0-9_]+', '_', 'g'))
  );

update booking_status s
set label = coalesce(nullif(trim(oc.description), ''), oc.code),
    phase = case
      when coalesce(oc.attrs->>'roomStatusType', oc.attrs->>'cateringStatusType') = 'Lost' then 'LOST'
      when coalesce(oc.attrs->>'roomStatusType', oc.attrs->>'cateringStatusType') = 'Refused' then 'TURNED_DOWN'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Inquiry' then 'INQUIRY'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'NonDeductInv' then 'TENTATIVE'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'DeductInv' then 'DEFINITE'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Actual' then 'ACTUAL'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Cancel' then 'CANCELLED'
      when coalesce(oc.attrs->>'cateringStatusType', oc.attrs->>'roomStatusType') = 'Waitlist' then 'INQUIRY'
      else s.phase
    end,
    opera_code = oc.code,
    active = true
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.kind = 'BLOCK_STATUS'
  and oc.active
  and s.tenant_id = __TENANT_ID__
  and (s.opera_code = oc.code or upper(s.code) = upper(regexp_replace(oc.code, '[^A-Za-z0-9_]+', '_', 'g')));

-- OPERA next-status edges where phase rules allow the move.
insert into booking_status_flow (tenant_id, from_status_id, to_status_id)
select __TENANT_ID__, fs.id, ts.id
from opera_code oc
join opera_hotel oh on oh.id = oc.opera_hotel_id
join booking_status fs on fs.tenant_id = __TENANT_ID__ and fs.opera_code = oc.code
cross join lateral jsonb_array_elements_text(coalesce(oc.attrs->'next', '[]'::jsonb)) as nxt(code)
join booking_status ts on ts.tenant_id = __TENANT_ID__ and ts.opera_code = nxt.code
where oh.tenant_id = __PROPERTY_TENANT_ID__
  and oc.kind = 'BLOCK_STATUS'
  and oc.active
  and fs.id <> ts.id
  and (
    (fs.phase = 'INQUIRY' and ts.phase in ('TENTATIVE', 'DEFINITE', 'TURNED_DOWN', 'LOST', 'CANCELLED'))
    or (fs.phase = 'TENTATIVE' and ts.phase in ('DEFINITE', 'TURNED_DOWN', 'LOST', 'CANCELLED', 'ACTUAL'))
    or (fs.phase = 'DEFINITE' and ts.phase in ('ACTUAL', 'CANCELLED', 'LOST'))
    or (fs.phase = 'ACTUAL' and ts.phase in ('CANCELLED'))
  )
on conflict (from_status_id, to_status_id) do nothing;
