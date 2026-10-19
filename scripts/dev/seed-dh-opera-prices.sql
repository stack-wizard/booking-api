-- Local list prices for Opera-synced DH spaces and room types (org tenant 2, hotel tenant 3).
-- Safe to re-run. Does not touch Opera; only product / price_list / resource.product_id.
-- Usage:
--   PGPASSWORD=booking_pass psql -h localhost -U booking_user -d booking_db -f scripts/dev/seed-dh-opera-prices.sql

\set ON_ERROR_STOP on

begin;

-- Chain price book (INTERNAL booking holds resolve against this).
insert into price_profile (tenant_id, name, currency, reservation_request_type)
select 2, 'Events EUR', 'EUR', 'INTERNAL'
where not exists (select 1 from price_profile where tenant_id = 2 and name = 'Events EUR');

update price_profile
set reservation_request_type = coalesce(reservation_request_type, 'INTERNAL')
where tenant_id = 2 and name = 'Events EUR';

insert into price_profile_date (price_profile_id, date_from, date_to, description)
select pp.id, date '2026-01-01', date '2027-12-31', 'EVENTS'
from price_profile pp
where pp.tenant_id = 2 and pp.name = 'Events EUR'
  and not exists (select 1 from price_profile_date d where d.price_profile_id = pp.id and d.date_from = date '2026-01-01');

create temporary table seed_price (
  name text, uom text, extra text[], price numeric, extra_prices numeric[], tax numeric, ord int, sales_group text
) on commit drop;

insert into seed_price values
  ('Sala1 rent',    'DAY',   array['HALF_DAY','HOUR'], 1200, array[750, 180],  25, 10, 'MEETING'),
  ('Sala 2 rent',   'DAY',   array['HALF_DAY','HOUR'], 800,  array[500, 120],  25, 11, 'MEETING'),
  ('Galija rent',   'DAY',   array['HALF_DAY','HOUR'], 900,  array[550, 140],  25, 12, 'MEETING'),
  ('Restoran rent', 'DAY',   array['HALF_DAY','HOUR'], 700,  array[450, 100],  25, 13, 'MEETING'),
  ('GALRES rent',   'DAY',   array['HALF_DAY','HOUR'], 1600, array[1000, 250], 25, 14, 'MEETING'),
  ('DBL night',     'NIGHT', array[]::text[],          120,  array[]::numeric[], 13, 50, null),
  ('SAE night',     'NIGHT', array[]::text[],          260,  array[]::numeric[], 13, 51, null),
  ('TWC night',     'NIGHT', array[]::text[],          115,  array[]::numeric[], 13, 52, null),
  ('Coffee break',  'PAX',   array[]::text[],          12.50, array[]::numeric[], 13, 20, 'F_AND_B');

insert into product (tenant_id, name, default_uom, product_type, tax1_percent, display_order, description, sales_group)
select 2, s.name, s.uom, 'SEALABLE_PRODUCT', s.tax, s.ord, 'DH Opera local list price', s.sales_group
from seed_price s
where not exists (select 1 from product p where p.tenant_id = 2 and p.name = s.name);

update product p
set sales_group = s.sales_group,
    default_uom = s.uom,
    tax1_percent = s.tax
from seed_price s
where p.tenant_id = 2 and p.name = s.name;

-- Coffee break smoke (used on booking 42) shares the Coffee break list price / group.
update product
set sales_group = 'F_AND_B',
    default_uom = 'PAX',
    tax1_percent = 13
where tenant_id = 2 and name = 'Coffee break smoke';

insert into product_extra_uom (product_id, uom)
select p.id, u.uom
from seed_price s
join product p on p.tenant_id = 2 and p.name = s.name
cross join lateral unnest(s.extra) as u(uom)
on conflict do nothing;

insert into price_list (product_id, uom, price, price_profile_id, price_profile_date_id)
select p.id, x.uom, x.price, pp.id, d.id
from seed_price s
join product p on p.tenant_id = 2 and p.name = s.name
cross join lateral (
  select s.uom as uom, s.price as price
  union all
  select e.uom, e.price from unnest(s.extra, s.extra_prices) as e(uom, price)
) x
join price_profile pp on pp.tenant_id = 2 and pp.name = 'Events EUR'
join price_profile_date d on d.price_profile_id = pp.id and d.date_from = date '2026-01-01'
where not exists (
  select 1 from price_list l
  where l.product_id = p.id and upper(l.uom) = upper(x.uom) and l.price_profile_id = pp.id
);

-- Same unit price for the smoke coffee product.
insert into price_list (product_id, uom, price, price_profile_id, price_profile_date_id)
select p.id, 'PAX', 12.50, pp.id, d.id
from product p
join price_profile pp on pp.tenant_id = 2 and pp.name = 'Events EUR'
join price_profile_date d on d.price_profile_id = pp.id and d.date_from = date '2026-01-01'
where p.tenant_id = 2 and p.name = 'Coffee break smoke'
  and not exists (
    select 1 from price_list l
    where l.product_id = p.id and upper(l.uom) = 'PAX' and l.price_profile_id = pp.id
  );

-- Link DH resources (hotel tenant 3) to chain products.
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'SALA1' and p.tenant_id = 2 and p.name = 'Sala1 rent';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'SAL2' and p.tenant_id = 2 and p.name = 'Sala 2 rent';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'GALIJA' and p.tenant_id = 2 and p.name = 'Galija rent';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code in ('RES', 'REST') and p.tenant_id = 2 and p.name = 'Restoran rent';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'GR' and p.tenant_id = 2 and p.name = 'GALRES rent';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'DBL' and p.tenant_id = 2 and p.name = 'DBL night';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'SAE' and p.tenant_id = 2 and p.name = 'SAE night';
update resource r set product_id = p.id
from product p
where r.tenant_id = 3 and r.code = 'TWC' and p.tenant_id = 2 and p.name = 'TWC night';

-- Point smoke room block at the Events EUR profile (group rate book).
update booking_room_block b
set rate_price_profile_id = pp.id
from price_profile pp
where b.id = 7 and pp.tenant_id = 2 and pp.name = 'Events EUR';

-- Reprice active holds of booking 42 from the list (HOUR rent for the 5h meeting; NIGHT × rooms).
update reservation r
set product_id = res.product_id,
    uom = 'HOUR',
    qty = 5,
    unit_price = 250.00,
    gross_amount = 1250.00
from resource res
where r.id = 289 and res.id = r.requested_resource_id and res.code = 'GR';

update reservation r
set product_id = res.product_id,
    uom = 'NIGHT',
    qty = 1,
    unit_price = 120.00,
    gross_amount = 600.00
from resource res
where r.id = 283 and res.id = r.requested_resource_id and res.code = 'DBL';

commit;

select r.code, r.name, p.name as product, pl.uom, pl.price
from resource r
join product p on p.id = r.product_id
join price_list pl on pl.product_id = p.id
join price_profile pp on pp.id = pl.price_profile_id and pp.name = 'Events EUR'
where r.tenant_id = 3
order by r.code, pl.uom;

select id, booking_function_id, booking_room_block_id, uom, qty, units, unit_price, gross_amount, product_id
from reservation
where id in (283, 289);
