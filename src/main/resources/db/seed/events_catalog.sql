-- JDBC seed (classpath). Placeholders replaced at runtime: __TENANT_ID__ = chain (catalog, CRM), __PROPERTY_TENANT_ID__ = hotel (spaces, capacity). Not a Flyway migration.
-- Dev seed for event management (Faza 3/4). Not a migration; safe to re-run.
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
select __PROPERTY_TENANT_ID__, rt.id, 'EXACT', s.code, s.name, 'ACTIVE', 1, 0, p.id, true, s.ord, s.area, s.floor, s.light
from seed_space s
join resource_type rt on rt.code = s.type_code
join product p on p.tenant_id = __TENANT_ID__ and p.name = s.product
on conflict (tenant_id, code) do nothing;

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select __PROPERTY_TENANT_ID__, parent.id, member.id, 1
from resource parent
join resource member on member.tenant_id = __PROPERTY_TENANT_ID__ and member.code in ('EVT-HALL-A', 'EVT-HALL-B')
where parent.tenant_id = __PROPERTY_TENANT_ID__ and parent.code = 'EVT-MAIN'
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
  ('Day Delegate Rate', 'Main Hall rent', 1, 'PER_PAX', 40, 'PLENARY',      0,   480, 0),
  ('Day Delegate Rate', 'Coffee break',   2, 'PER_PAX', 20, 'COFFEE_BREAK', 90,  30,  1),
  ('Day Delegate Rate', 'Business lunch', 1, 'PER_PAX', 40, 'LUNCH',        210, 60,  2),
  ('Half-day DDR',      'Boardroom rent', 4, 'FIXED',   null, 'PLENARY',    0,   240, 0),
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
       'Meeting room for the day, two coffee breaks and a business lunch.', 10, 120,
       'FULL_DAY', '09:00', 'THEATRE', true
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
