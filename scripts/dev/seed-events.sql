-- Dev seed for event management (Faza 3/4). Not a migration; safe to re-run.
-- Usage: docker exec -i booking-postgres psql -U booking_user -d booking_db -v tenant=2 < scripts/dev/seed-events.sql

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

-- Products: rent (per space), catering, equipment.
create temporary table seed_product (name text, uom text, extra text[], price numeric, extra_prices numeric[], tax numeric, ord int) on commit drop;
insert into seed_product values
  ('Main Hall rent',     'DAY',  array['HALF_DAY','HOUR'], 1800, array[1100, 250], 25, 10),
  ('Hall A rent',        'DAY',  array['HALF_DAY','HOUR'], 950,  array[600, 140],  25, 11),
  ('Hall B rent',        'DAY',  array['HALF_DAY','HOUR'], 950,  array[600, 140],  25, 12),
  ('Boardroom rent',     'HOUR', array['HALF_DAY','DAY'],  60,   array[220, 400],  25, 13),
  ('Breakout room rent', 'HOUR', array['HALF_DAY','DAY'],  45,   array[160, 290],  25, 14),
  ('Coffee break',       'UNIT', array[]::text[],          8,    array[]::numeric[], 13, 20),
  ('Business lunch',     'UNIT', array[]::text[],          28,   array[]::numeric[], 13, 21),
  ('Gala dinner',        'UNIT', array[]::text[],          55,   array[]::numeric[], 13, 22),
  ('Welcome drink',      'UNIT', array[]::text[],          12,   array[]::numeric[], 13, 23),
  ('Projector & screen', 'UNIT', array[]::text[],          60,   array[]::numeric[], 25, 30),
  ('Day Delegate Rate',  'UNIT', array[]::text[],          65,   array[]::numeric[], 13, 40),
  ('Half-day DDR',       'UNIT', array[]::text[],          0,    array[]::numeric[], 13, 41);

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
join price_profile_date d on d.price_profile_id = pp.id
where not exists (
  select 1 from price_list l where l.product_id = p.id and upper(l.uom) = upper(x.uom) and l.price_profile_id = pp.id
);

-- Spaces are plain resources (MEETING_ROOM / COMPOSITION) with setup capacities.
create temporary table seed_space (code text, name text, type_code text, product text, area numeric, floor text, light boolean, ord int) on commit drop;
insert into seed_space values
  ('EVT-HALL-A',   'Hall A',        'MEETING_ROOM', 'Hall A rent',        180, 'Ground', true,  2),
  ('EVT-HALL-B',   'Hall B',        'MEETING_ROOM', 'Hall B rent',        180, 'Ground', true,  3),
  ('EVT-MAIN',     'Main Hall',     'COMPOSITION',  'Main Hall rent',     360, 'Ground', true,  1),
  ('EVT-BOARD',    'Boardroom',     'MEETING_ROOM', 'Boardroom rent',     60,  '1',      true,  4),
  ('EVT-BREAKOUT', 'Breakout Room', 'MEETING_ROOM', 'Breakout room rent', 45,  '1',      false, 5);

insert into resource (tenant_id, resource_type_id, kind, code, name, status, unit_count, cap_total, product_id,
                      can_book_alone, display_order, area_sqm, floor, natural_light)
select :tenant, rt.id, 'EXACT', s.code, s.name, 'ACTIVE', 1, 0, p.id, true, s.ord, s.area, s.floor, s.light
from seed_space s
join resource_type rt on rt.code = s.type_code
join product p on p.tenant_id = :tenant and p.name = s.product
on conflict (tenant_id, code) do nothing;

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select :tenant, parent.id, member.id, 1
from resource parent
join resource member on member.tenant_id = :tenant and member.code in ('EVT-HALL-A', 'EVT-HALL-B')
where parent.tenant_id = :tenant and parent.code = 'EVT-MAIN'
on conflict (parent_resource_id, member_resource_id) do nothing;

create temporary table seed_setup (code text, style text, capacity int) on commit drop;
insert into seed_setup values
  ('EVT-MAIN', 'THEATRE', 400), ('EVT-MAIN', 'CLASSROOM', 220), ('EVT-MAIN', 'BANQUET', 300),
  ('EVT-MAIN', 'CABARET', 240), ('EVT-MAIN', 'RECEPTION', 500),
  ('EVT-HALL-A', 'THEATRE', 180), ('EVT-HALL-A', 'CLASSROOM', 100), ('EVT-HALL-A', 'BANQUET', 130), ('EVT-HALL-A', 'U_SHAPE', 50),
  ('EVT-HALL-B', 'THEATRE', 180), ('EVT-HALL-B', 'CLASSROOM', 100), ('EVT-HALL-B', 'BANQUET', 130), ('EVT-HALL-B', 'U_SHAPE', 50),
  ('EVT-BOARD', 'BOARDROOM', 16), ('EVT-BOARD', 'U_SHAPE', 14),
  ('EVT-BREAKOUT', 'BOARDROOM', 12), ('EVT-BREAKOUT', 'CLASSROOM', 20), ('EVT-BREAKOUT', 'THEATRE', 30);

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
  ('Day Delegate Rate', 'Main Hall rent', 1, 'PER_PAX', 40, 'PLENARY',      0,   480, 0),
  ('Day Delegate Rate', 'Coffee break',   2, 'PER_PAX', 20, 'COFFEE_BREAK', 90,  30,  1),
  ('Day Delegate Rate', 'Business lunch', 1, 'PER_PAX', 40, 'LUNCH',        210, 60,  2),
  ('Half-day DDR',      'Boardroom rent', 4, 'FIXED',   null, 'PLENARY',    0,   240, 0),
  ('Half-day DDR',      'Coffee break',   1, 'PER_PAX', null, 'COFFEE_BREAK', 120, 30, 1);

insert into product_component (tenant_id, package_product_id, component_product_id, qty, qty_basis, included,
                               share_percent, function_type, start_offset_minutes, duration_minutes, display_order)
select :tenant, pkg.id, comp.id, c.qty, c.basis, true, c.share, c.function_type, c.start_offset, c.duration, c.ord
from seed_component c
join product pkg on pkg.tenant_id = :tenant and pkg.name = c.pkg
join product comp on comp.tenant_id = :tenant and comp.name = c.component
where not exists (select 1 from product_component pc where pc.package_product_id = pkg.id);

commit;

select r.name, r.area_sqm, string_agg(s.setup_style || ' ' || s.capacity, ', ' order by s.capacity desc) as setups
from resource r
left join resource_setup_capacity s on s.resource_id = r.id
where r.tenant_id = :tenant and r.code like 'EVT-%'
group by r.id, r.name, r.area_sqm, r.display_order
order by r.display_order;
