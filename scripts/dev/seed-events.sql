-- Dev seed for booking management (Faza 3/4). Not a migration; safe to re-run.
-- Usage: docker exec -i booking-postgres psql -U booking_user -d booking_db -v tenant=2 < scripts/dev/seed-events.sql
-- Also called by scripts/dev/seed-dh-sales-flow.sh when Opera hotel DH exists (never via Flyway/deploy).

\set ON_ERROR_STOP on
\if :{?tenant}
\else
  \set tenant 2
\endif

begin;

-- Price profile with a validity window (rent and catering are priced from price_list).
insert into price_profile (tenant_id, name, currency)
select :tenant, 'Events EUR', 'EUR'
where not exists (select 1 from price_profile where tenant_id = :tenant and name = 'Events EUR');

insert into price_profile_date (price_profile_id, date_from, date_to, description)
select pp.id, date '2026-01-01', date '2027-12-31', 'EVENTS'
from price_profile pp
where pp.tenant_id = :tenant and pp.name = 'Events EUR'
  and not exists (select 1 from price_profile_date d where d.price_profile_id = pp.id);

-- Next season starts empty; prices are copied into it from the Products screen.
insert into price_profile_date (price_profile_id, date_from, date_to, description)
select pp.id, date '2028-01-01', date '2028-12-31', 'EVENTS 2028'
from price_profile pp
where pp.tenant_id = :tenant and pp.name = 'Events EUR'
  and not exists (select 1 from price_profile_date d where d.price_profile_id = pp.id and d.date_from = date '2028-01-01');

-- Products: rent (per space), catering, equipment.
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
select :tenant, s.name, s.uom, 'SEALABLE_PRODUCT', s.tax, s.ord, 'Event seed'
from seed_product s
where not exists (select 1 from product p where p.tenant_id = :tenant and p.name = s.name);

insert into product_extra_uom (product_id, uom)
select p.id, u.uom
from seed_product s
join product p on p.tenant_id = :tenant and p.name = s.name
cross join lateral unnest(s.extra) as u(uom)
on conflict do nothing;

insert into price_list (product_id, uom, price, price_profile_id, price_profile_date_id)
select p.id, x.uom, x.price, pp.id, d.id
from seed_product s
join product p on p.tenant_id = :tenant and p.name = s.name
cross join lateral (
  select s.uom as uom, s.price as price where s.name <> 'Half-day DDR'
  union all
  select e.uom, e.price from unnest(s.extra, s.extra_prices) as e(uom, price)
) x
join price_profile pp on pp.tenant_id = :tenant and pp.name = 'Events EUR'
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
select :tenant, rt.id, 'EXACT', s.code, s.name, 'ACTIVE', 1, 0, p.id, true, s.ord, s.area, s.floor, s.light
from seed_space s
join resource_type rt on rt.code = s.type_code
join product p on p.tenant_id = :tenant and p.name = s.product
on conflict (tenant_id, code) do nothing;

-- OPERA combo space GR (GALRES) = Galija + restoran.
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select :tenant, parent.id, member.id, 1
from resource parent
join resource member on member.tenant_id = :tenant and member.code in ('GALIJA', 'RES')
where parent.tenant_id = :tenant and parent.code = 'GR'
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
select :tenant, r.id, s.style, s.capacity
from seed_setup s
join resource r on r.tenant_id = :tenant and r.code = s.code
on conflict (resource_id, setup_style) do nothing;

-- Booking calendar for the tenant (grid/duration rules); events use it like any other booking.
insert into booking_calendar (tenant_id, location_node_id, open_time, close_time, grid_minutes, min_duration_minutes,
                              max_duration_minutes, zone)
select :tenant, null, time '07:00', time '23:00', 30, 30, 960, 'Europe/Zagreb'
where not exists (select 1 from booking_calendar where tenant_id = :tenant);

-- Packages. DDR: package price split by percentage; Half-day DDR: sum of components.
update product set package_pricing = 'SPLIT_PERCENT'
where tenant_id = :tenant and name = 'Day Delegate Rate' and package_pricing is null;
update product set package_pricing = 'SUM'
where tenant_id = :tenant and name = 'Half-day DDR' and package_pricing is null;

create temporary table seed_component (pkg text, component text, qty int, basis text, share numeric,
                                       function_type text, start_offset int, duration int, ord int) on commit drop;
insert into seed_component values
  ('Day Delegate Rate', 'Sala1 rent', 1, 'PER_PAX', 40, 'PLENARY',      0,   480, 0),
  ('Day Delegate Rate', 'Coffee break',   2, 'PER_PAX', 20, 'COFFEE_BREAK', 90,  30,  1),
  ('Day Delegate Rate', 'Business lunch', 1, 'PER_PAX', 40, 'LUNCH',        210, 60,  2),
  ('Half-day DDR',      'Sala 2 rent', 4, 'FIXED',   null, 'PLENARY',    0,   240, 0),
  ('Half-day DDR',      'Coffee break',   1, 'PER_PAX', null, 'COFFEE_BREAK', 120, 30, 1);

insert into product_component (tenant_id, package_product_id, component_product_id, qty, qty_basis, included,
                               share_percent, function_type, start_offset_minutes, duration_minutes, display_order)
select :tenant, pkg.id, comp.id, c.qty, c.basis, true, c.share, c.function_type, c.start_offset, c.duration, c.ord
from seed_component c
join product pkg on pkg.tenant_id = :tenant and pkg.name = c.pkg
join product comp on comp.tenant_id = :tenant and comp.name = c.component
where not exists (select 1 from product_component pc where pc.package_product_id = pkg.id);

-- Phase 5: quote groups, advance invoices need a DEPOSIT product.
update product set sales_group = 'MEETING'
where tenant_id = :tenant and name like '%rent' and sales_group is null;
update product set sales_group = 'F_AND_B'
where tenant_id = :tenant and name in ('Coffee break','Business lunch','Gala dinner','Welcome drink') and sales_group is null;
update product set sales_group = 'AV'
where tenant_id = :tenant and name = 'Projector & screen' and sales_group is null;

insert into product (tenant_id, name, default_uom, product_type, tax1_percent, display_order, description)
select :tenant, 'Deposit', 'UNIT', 'DEPOSIT', 25, 900, 'Advance payment'
where not exists (select 1 from product p where p.tenant_id = :tenant and upper(p.product_type) = 'DEPOSIT');

-- Phase 4 CMS: the DDR is published for the web shop.
insert into product_package_listing (tenant_id, product_id, public_name, public_description, min_pax, max_pax,
                                     duration, default_start_time, setup_style, published)
select :tenant, p.id, 'Day Delegate Package',
       'Meeting room for the day, two coffee breaks and a business lunch.', 10, 120,
       'FULL_DAY', '09:00', 'THEATER', true
from product p
where p.tenant_id = :tenant and p.name = 'Day Delegate Rate'
on conflict (product_id) do nothing;

-- Phase 7: sales teams (Sales → MICE / Leisure & Groups), segments routed to teams, lead assignment rules.
-- Members come from Platform users after their first login, so add them in CRM Setup → Teams.
insert into crm_team (tenant_id, name)
select :tenant, 'Sales'
where not exists (select 1 from crm_team where tenant_id = :tenant and lower(name) = 'sales');

insert into crm_team (tenant_id, name, parent_team_id)
select :tenant, t.name, (select id from crm_team where tenant_id = :tenant and lower(name) = 'sales')
from (values ('MICE'), ('Leisure & Groups')) as t(name)
where not exists (select 1 from crm_team c where c.tenant_id = :tenant and lower(c.name) = lower(t.name));

insert into crm_segment (tenant_id, code, name, default_team_id, display_order)
select :tenant, s.code, s.name, (select id from crm_team where tenant_id = :tenant and name = s.team), s.ord
from (values ('CORPORATE', 'Corporate', 'MICE', 10),
             ('ASSOCIATION', 'Associations', 'MICE', 20),
             ('AGENCY', 'Agencies & DMC', 'Leisure & Groups', 30),
             ('LEISURE', 'Leisure groups', 'Leisure & Groups', 40),
             ('SOCIAL', 'Weddings & private', 'Leisure & Groups', 50)) as s(code, name, team, ord)
where not exists (select 1 from crm_segment x where x.tenant_id = :tenant and lower(x.code) = lower(s.code));

insert into crm_assignment_rule (tenant_id, name, priority, min_pax, target_team_id, strategy)
select :tenant, 'Large events (150+ pax)', 10, 150, t.id, 'LEAST_LOADED'
from crm_team t
where t.tenant_id = :tenant and t.name = 'MICE'
  and not exists (select 1 from crm_assignment_rule r where r.tenant_id = :tenant and r.name = 'Large events (150+ pax)');

insert into crm_assignment_rule (tenant_id, name, priority, segment, target_team_id, strategy)
select :tenant, 'Corporate inquiries', 20, 'CORPORATE', t.id, 'ROUND_ROBIN'
from crm_team t
where t.tenant_id = :tenant and t.name = 'MICE'
  and not exists (select 1 from crm_assignment_rule r where r.tenant_id = :tenant and r.name = 'Corporate inquiries');

commit;

select r.name, r.area_sqm, string_agg(s.setup_style || ' ' || s.capacity, ', ' order by s.capacity desc) as setups
from resource r
left join resource_setup_capacity s on s.resource_id = r.id
where r.tenant_id = :tenant and r.code like 'EVT-%'
group by r.id, r.name, r.area_sqm, r.display_order
order by r.display_order;
