-- Seed data (manual run, not managed by Flyway)

-- resource
-- prerequisites: resource_type + location_node
insert into resource_type (tenant_id, code, name, default_time_model)
values
  (1, 'SUNBED', 'Sunbed', null),
  (1, 'DECK_CABANA', 'Deck Cabana', null),
  (1, 'BALDAHIN', 'Baldahin', null),
  (1, 'APARTMENT', 'Apartment', null),
  (1, 'COMPOSITION', 'Composition', null)
on conflict (tenant_id, code) do nothing;

insert into location_node (tenant_id, parent_id, node_type, code, name, sort_order)
values (1, null, 'BEACH', 'BEACH_1', 'Beach Area 1', 1)
on conflict (tenant_id, parent_id, name) do nothing;

-- 62 sunbeds S01-S62
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select
  1,
  rt.id,
  'EXACT',
  loc.id,
  'S' || lpad(gs::text, 2, '0'),
  'Sunbed ' || gs,
  'ACTIVE',
  1, 1, 0, 0, 1
from generate_series(1, 62) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'SUNBED'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- baldahins B1-B5
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'B' || gs, 'Baldahin ' || gs, 'ACTIVE', 1, 2, 0, 0, 2
from generate_series(1, 5) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'BALDAHIN'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- apartments A1-A6
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'A' || gs, 'Apartment ' || gs, 'ACTIVE', 1, 2, 0, 0, 2
from generate_series(1, 6) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'APARTMENT'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- 3 Deck Duo (DD1-DD3) as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'DD' || gs, 'Deck Duo ' || gs, 'ACTIVE', 1, 2, 0, 0, 2
from generate_series(1, 3) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'COMPOSITION'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- 1 Deck Cabana (C1)
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'C1', 'Deck Cabana 1', 'ACTIVE', 1, 2, 0, 0, 2
from resource_type rt
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
where rt.tenant_id = 1 and rt.code = 'DECK_CABANA'
on conflict (tenant_id, code) do nothing;

-- 5 Peninsulas (P1-P5) as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'P' || gs, 'Peninsula ' || gs, 'ACTIVE', 1, 4, 0, 0, 4
from generate_series(1, 5) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'COMPOSITION'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- 5 Luxury Sunbeds (L1-L5) as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'L' || gs, 'Luxury Sunbed ' || gs, 'ACTIVE', 1, 2, 0, 0, 2
from generate_series(1, 5) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'COMPOSITION'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- 1 Grand Luxury (GL1) as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'GL1', 'Grand Luxury 1', 'ACTIVE', 1, 6, 0, 0, 6
from resource_type rt
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
where rt.tenant_id = 1 and rt.code = 'COMPOSITION'
on conflict (tenant_id, code) do nothing;

-- Bespoke Luxury BL1-BL5 as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'BL' || gs, 'Bespoke Luxury ' || gs, 'ACTIVE', 1, 6, 0, 0, 6
from generate_series(1, 5) gs
join resource_type rt on rt.tenant_id = 1 and rt.code = 'COMPOSITION'
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
on conflict (tenant_id, code) do nothing;

-- Bespoke Deluxe BD1 as COMPOSITION
insert into resource (tenant_id, resource_type_id, kind, location_id, code, name, status,
                      unit_count, cap_adults, cap_children, cap_infants, cap_total)
select 1, rt.id, 'EXACT', loc.id, 'BD1', 'Bespoke Deluxe 1', 'ACTIVE', 1, 8, 0, 0, 8
from resource_type rt
left join location_node loc on loc.tenant_id = 1 and loc.code = 'BEACH_1'
where rt.tenant_id = 1 and rt.code = 'COMPOSITION'
on conflict (tenant_id, code) do nothing;

-- resource_composition
-- Deck Duo: DD1=S01+S02, DD2=S03+S04, DD3=S05+S06
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'DD1' and m.code in ('S01','S02');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'DD2' and m.code in ('S03','S04');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'DD3' and m.code in ('S05','S06');

-- Peninsulas: P1..P5 = 1 baldahin + 4 sunbeds
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'P1' and m.code in ('B1','S07','S08','S09','S10');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'P2' and m.code in ('B2','S11','S12','S13','S14');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'P3' and m.code in ('B3','S15','S16','S17','S18');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'P4' and m.code in ('B4','S19','S20','S21','S22');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'P5' and m.code in ('B5','S23','S24','S25','S26');

-- Luxury Sunbed: L1..L5 = 1 apartment + 2 sunbeds
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'L1' and m.code in ('A1','S27','S28');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'L2' and m.code in ('A2','S29','S30');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'L3' and m.code in ('A3','S31','S32');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'L4' and m.code in ('A4','S33','S34');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'L5' and m.code in ('A5','S35','S36');

-- Grand Luxury: GL1 = 1 apartment + 6 sunbeds
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'GL1' and m.code in ('A6','S37','S38','S39','S40','S41','S42');

-- Bespoke Luxury = Luxury + Peninsula (BL1..BL5 map L1..L5 + P1..P5)
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BL1' and m.code in ('L1','P1');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BL2' and m.code in ('L2','P2');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BL3' and m.code in ('L3','P3');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BL4' and m.code in ('L4','P4');

insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BL5' and m.code in ('L5','P5');

-- Bespoke Deluxe = Grand Luxury + Peninsula (BD1 = GL1 + P1)
insert into resource_composition (tenant_id, parent_resource_id, member_resource_id, qty)
select 1, p.id, m.id, 1
from resource p join resource m on m.tenant_id = 1
where p.tenant_id = 1 and p.code = 'BD1' and m.code in ('GL1','P1');

-- product (product points to specific resource)
insert into product (tenant_id, name, resource_id, default_uom)
values
  (1, 'Deck Duo', null, 'DAY'),
  (1, 'Deck Cabana', null, 'DAY'),
  (1, 'Peninsula', null, 'DAY'),
  (1, 'Luxury Sunbed', null, 'DAY'),
  (1, 'Grand Luxury', null, 'DAY'),
  (1, 'Bespoke Luxury', null, 'DAY'),
  (1, 'Bespoke Deluxe', null, 'DAY');

-- product_uom (extra uoms)
insert into product_extra_uom (product_id, uom)
select p.id, 'HOUR' from product p where p.tenant_id = 1;

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Deck Duo')
where tenant_id = 1 and code in ('DD1','DD2','DD3');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Deck Cabana')
where tenant_id = 1 and code in ('C1');

update resource
set color_hex = '#F1C40F'
where tenant_id = 1 and code in ('P1','P2','P3','P4','P5');

update resource
set color_hex = '#3498DB'
where tenant_id = 1 and code in ('BL1','BL2','BL3','BL4','BL5','BD1');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Peninsula')
where tenant_id = 1 and code in ('P1','P2','P3','P4','P5');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Luxury Sunbed')
where tenant_id = 1 and code in ('L1','L2','L3','L4','L5');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Grand Luxury')
where tenant_id = 1 and code in ('GL1');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Bespoke Luxury')
where tenant_id = 1 and code in ('BL1','BL2','BL3','BL4','BL5');

update resource
set product_id = (select id from product where tenant_id = 1 and name = 'Bespoke Deluxe')
where tenant_id = 1 and code in ('BD1');

-- price_profile
insert into price_profile (tenant_id, name, currency)
values (1, 'Default EUR', 'EUR')
on conflict (tenant_id, name) do nothing;

-- price_profile_date
insert into price_profile_date (price_profile_id, date_from, date_to, description)
select p.id, d.date_from, d.date_to, d.description
from price_profile p
join (values
  ('2026-05-01'::date, '2026-06-14'::date, 'PRE_SEASON'),
  ('2026-06-15'::date, '2026-09-01'::date, 'SEASON'),
  ('2026-09-02'::date, '2026-10-15'::date, 'POST_SEASON')
) as d(date_from, date_to, description)
  on p.tenant_id = 1 and p.name = 'Default EUR';

-- price_list (linked to profile + date)
with u(product_name, uom, price, season) as (
  values
    ('Deck Duo','DAY', 60.00, 'PRE_SEASON'),
    ('Deck Duo','DAY', 75.00, 'SEASON'),
    ('Deck Duo','DAY', 55.00, 'POST_SEASON'),
    ('Deck Duo','HOUR', 12.00, 'PRE_SEASON'),
    ('Deck Duo','HOUR', 15.00, 'SEASON'),
    ('Deck Duo','HOUR', 11.00, 'POST_SEASON'),

    ('Deck Cabana','DAY', 80.00, 'PRE_SEASON'),
    ('Deck Cabana','DAY', 95.00, 'SEASON'),
    ('Deck Cabana','DAY', 70.00, 'POST_SEASON'),
    ('Deck Cabana','HOUR', 16.00, 'PRE_SEASON'),
    ('Deck Cabana','HOUR', 20.00, 'SEASON'),
    ('Deck Cabana','HOUR', 14.00, 'POST_SEASON'),

    ('Peninsula','DAY', 140.00, 'PRE_SEASON'),
    ('Peninsula','DAY', 170.00, 'SEASON'),
    ('Peninsula','DAY', 125.00, 'POST_SEASON'),
    ('Peninsula','HOUR', 28.00, 'PRE_SEASON'),
    ('Peninsula','HOUR', 34.00, 'SEASON'),
    ('Peninsula','HOUR', 25.00, 'POST_SEASON'),

    ('Luxury Sunbed','DAY', 110.00, 'PRE_SEASON'),
    ('Luxury Sunbed','DAY', 135.00, 'SEASON'),
    ('Luxury Sunbed','DAY', 100.00, 'POST_SEASON'),
    ('Luxury Sunbed','HOUR', 22.00, 'PRE_SEASON'),
    ('Luxury Sunbed','HOUR', 27.00, 'SEASON'),
    ('Luxury Sunbed','HOUR', 20.00, 'POST_SEASON'),

    ('Grand Luxury','DAY', 180.00, 'PRE_SEASON'),
    ('Grand Luxury','DAY', 220.00, 'SEASON'),
    ('Grand Luxury','DAY', 165.00, 'POST_SEASON'),
    ('Grand Luxury','HOUR', 36.00, 'PRE_SEASON'),
    ('Grand Luxury','HOUR', 44.00, 'SEASON'),
    ('Grand Luxury','HOUR', 33.00, 'POST_SEASON'),

    ('Bespoke Luxury','DAY', 240.00, 'PRE_SEASON'),
    ('Bespoke Luxury','DAY', 290.00, 'SEASON'),
    ('Bespoke Luxury','DAY', 215.00, 'POST_SEASON'),
    ('Bespoke Luxury','HOUR', 48.00, 'PRE_SEASON'),
    ('Bespoke Luxury','HOUR', 58.00, 'SEASON'),
    ('Bespoke Luxury','HOUR', 43.00, 'POST_SEASON'),

    ('Bespoke Deluxe','DAY', 300.00, 'PRE_SEASON'),
    ('Bespoke Deluxe','DAY', 360.00, 'SEASON'),
    ('Bespoke Deluxe','DAY', 275.00, 'POST_SEASON'),
    ('Bespoke Deluxe','HOUR', 60.00, 'PRE_SEASON'),
    ('Bespoke Deluxe','HOUR', 72.00, 'SEASON'),
    ('Bespoke Deluxe','HOUR', 55.00, 'POST_SEASON')
)
insert into price_list (product_id, uom, price, price_profile_id, price_profile_date_id)
select p.id, u.uom, u.price, prof.id, pd.id
from u
join product p on p.tenant_id = 1 and p.name = u.product_name
join price_profile prof on prof.tenant_id = 1 and prof.name = 'Default EUR'
join price_profile_date pd on pd.price_profile_id = prof.id and pd.description = u.season;

-- service_schedule (booking_calendar)
insert into booking_calendar (tenant_id, location_node_id, open_time, close_time,
                              grid_minutes, min_duration_minutes, max_duration_minutes, zone)
values
  (1, null, '09:00', '19:00', 30, 60, 600, 'Europe/Zagreb'),
  (1, (select id from location_node where tenant_id = 1 and code = 'BEACH_1'),
   '09:00', '19:00', 30, 60, 600, 'Europe/Zagreb');

-- map data
insert into resource_map (tenant_id, location_node_id, name, image_url, svg_overlay)
values
  (1, (select id from location_node where tenant_id = 1 and code = 'BEACH_1'),
   'Beach Area 1 Map', '/maps/beach-1.png', null);

insert into resource_map_resource (resource_map_id, resource_id, polygon, label)
select map.id,
       r.id,
       case r.code
         when 'S25' then 'M64,86 L97,86 L97,121 L64,121 Z'
         when 'S26' then 'M103,90 L140,90 L140,133 L103,133 Z'
         when 'P3' then 'M349,202 L335,305 L430,322 L443,221 Z'
       end,
       r.code
from resource_map map
join resource r on r.tenant_id = 1
where map.tenant_id = 1
  and map.name = 'Beach Area 1 Map'
  and r.code in ('S25','S26','P3')
on conflict (resource_map_id, resource_id) do nothing;

-- app_user (super admin + regular admin)
insert into app_user (tenant_id, username, password_hash, role)
values
  (null, 'superadmin', '$2a$10$NuxNEz3wT3/dFmnoay1Zwu3Ek27TR72.Og6D4uZPIH4M5rAd5lXE.', 'SUPER_ADMIN'),
  (1, 'admin1', '$2a$10$tEIqW6jMHt/nglw5VP4RCugx3SSYYoawx4mAQoYJraY4KET3revXK', 'ADMIN')
on conflict (username) do nothing;

-- api_token (tenant 1)
-- token: tenant1-nextjs-2026
insert into api_token (tenant_id, name, token_hash, active)
values (1, 'nextjs', 'fdd95bfcaac83c228ff1e29040b90794b3d67eee6e998b33377fe29abf3172c7', true)
on conflict (token_hash) do nothing;
```
