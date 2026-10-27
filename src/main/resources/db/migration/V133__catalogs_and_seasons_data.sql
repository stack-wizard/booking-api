-- Data migration for catalogs and seasons, without changing behaviour:
--  1. prices: existing periods are SEASON without a season, prices have no weekday limit (column defaults of V132)
--  2. a "Default" season calendar per hotel with root resource maps; one OPEN season per distinct (merged)
--     validity range of the root maps; open-ended maps end at the end of the year and get a note for the admin
--  3. BEACH catalog (INSTANT, EXTERNAL) per hotel whose maps hold resources: every resource on its maps,
--     published, group SPOT; catalog_map = the hotel's maps (seasonless, chosen by map validity as today)
--  4. MICE catalog (INQUIRY) per tenant with package listings: PACKAGE items from the listings, plus halls
--     (resources linked to MEETING products) unpublished, group HALL

update price_profile_date set period_kind = 'SEASON' where period_kind is distinct from 'SEASON' and season_id is null;

create temp table v133_property on commit drop as
select m.tenant_id as property_id,
       coalesce((select ptm.parent_tenant_id
                 from platform_tenant_mapping ptm
                 where ptm.tenant_id = m.tenant_id and ptm.kind = 'PROPERTY' and ptm.parent_tenant_id is not null
                 limit 1), m.tenant_id) as org_id
from resource_map m
where m.parent_map_id is null
group by m.tenant_id;

insert into season_calendar (tenant_id, property_tenant_id, name)
select org_id, property_id, 'Default' from v133_property order by property_id;

with ranges as (
  select m.tenant_id as property_id,
         m.name,
         (m.valid_from is null or m.valid_to is null) as open_ended,
         coalesce(m.valid_from,
                  least(date_trunc('year', current_date)::date,
                        coalesce(m.valid_to, date_trunc('year', current_date)::date))) as d_from,
         coalesce(m.valid_to,
                  (date_trunc('year', greatest(current_date, coalesce(m.valid_from, current_date)))
                     + interval '1 year' - interval '1 day')::date) as d_to
  from resource_map m
  where m.parent_map_id is null
), ordered as (
  select r.*,
         max(r.d_to) over (partition by r.property_id order by r.d_from, r.d_to
                           rows between unbounded preceding and 1 preceding) as prev_to
  from ranges r
), grouped as (
  select o.*,
         sum(case when o.prev_to is null or o.d_from > o.prev_to then 1 else 0 end)
           over (partition by o.property_id order by o.d_from, o.d_to rows unbounded preceding) as grp
  from ordered o
), islands as (
  select property_id,
         min(d_from) as d_from,
         max(d_to) as d_to,
         bool_or(open_ended) as open_ended,
         string_agg(distinct name, ' / ') as names
  from grouped
  group by property_id, grp
)
insert into season (tenant_id, season_calendar_id, code, name, date_from, date_to, status, display_order, note)
select c.tenant_id,
       c.id,
       'SEASON_' || to_char(i.d_from, 'YYYYMMDD'),
       coalesce(i.names, 'Season'),
       i.d_from,
       i.d_to,
       'OPEN',
       row_number() over (partition by c.id order by i.d_from),
       case when i.open_ended
            then 'Created from a resource map without an end (or start) date; check the dates ' || i.d_from || ' - ' || i.d_to || '.'
       end
from islands i
join season_calendar c on c.property_tenant_id = i.property_id and c.name = 'Default';

insert into sales_catalog (tenant_id, property_tenant_id, code, name, sale_mode, season_calendar_id, request_type, currency)
select p.org_id,
       p.property_id,
       'BEACH',
       'Beach',
       'INSTANT',
       c.id,
       'EXTERNAL',
       coalesce((select upper(pp.currency) from price_profile pp where pp.tenant_id = p.org_id
                 group by upper(pp.currency) order by count(*) desc, upper(pp.currency) limit 1), 'EUR')
from v133_property p
join season_calendar c on c.property_tenant_id = p.property_id and c.name = 'Default'
where exists (select 1
              from resource_map_resource x
              join resource_map m on m.id = x.resource_map_id
              where m.tenant_id = p.property_id);

insert into catalog_item (tenant_id, catalog_id, item_kind, resource_id, item_group, display_order, published)
select sc.tenant_id, sc.id, 'RESOURCE', r.id, 'SPOT', coalesce(r.display_order, 0), true
from sales_catalog sc
join resource r on r.tenant_id = sc.property_tenant_id
where sc.code = 'BEACH'
  and exists (select 1
              from resource_map_resource x
              join resource_map m on m.id = x.resource_map_id
              where x.resource_id = r.id and m.tenant_id = sc.property_tenant_id)
order by sc.id, r.display_order, r.id;

insert into catalog_map (catalog_id, resource_map_id, season_id)
select sc.id, m.id, null
from sales_catalog sc
join resource_map m on m.tenant_id = sc.property_tenant_id
where sc.code = 'BEACH';

insert into sales_catalog (tenant_id, property_tenant_id, code, name, sale_mode, season_calendar_id, request_type, currency)
select l.tenant_id,
       null,
       'MICE',
       'MICE',
       'INQUIRY',
       null,
       'EXTERNAL',
       coalesce((select upper(pp.currency) from price_profile pp where pp.tenant_id = l.tenant_id
                 group by upper(pp.currency) order by count(*) desc, upper(pp.currency) limit 1), 'EUR')
from product_package_listing l
group by l.tenant_id;

insert into catalog_item (tenant_id, catalog_id, item_kind, product_id, item_group, public_name, public_description,
                          display_order, published, valid_from, valid_to, min_pax, max_pax, duration,
                          default_start_time, setup_style, created_at, updated_at)
select l.tenant_id, sc.id, 'PACKAGE', l.product_id, 'PACKAGE', l.public_name, l.public_description,
       coalesce(p.display_order, 0), l.published, l.valid_from, l.valid_to, l.min_pax, l.max_pax, l.duration,
       l.default_start_time, l.setup_style, l.created_at, l.updated_at
from product_package_listing l
join product p on p.id = l.product_id
join sales_catalog sc on sc.tenant_id = l.tenant_id and sc.property_tenant_id is null and sc.code = 'MICE'
order by l.id;

insert into catalog_item (tenant_id, catalog_id, item_kind, resource_id, item_group, display_order, published)
select sc.tenant_id, sc.id, 'RESOURCE', r.id, 'HALL', coalesce(r.display_order, 0), false
from sales_catalog sc
join resource r on r.tenant_id = sc.tenant_id
                or r.tenant_id in (select ptm.tenant_id from platform_tenant_mapping ptm
                                   where ptm.parent_tenant_id = sc.tenant_id)
where sc.code = 'MICE' and sc.property_tenant_id is null
  and (exists (select 1 from product p where p.id = r.product_id and p.sales_group = 'MEETING')
       or exists (select 1 from product p where p.resource_id = r.id and p.sales_group = 'MEETING'))
order by sc.id, r.display_order, r.id
on conflict do nothing;
